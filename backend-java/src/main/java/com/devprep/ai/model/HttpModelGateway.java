package com.devprep.ai.model;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.config.DevPrepProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** OpenAI-compatible HTTP adapter with bounded bodies and no credential-bearing redirects. */
@Component
public class HttpModelGateway implements ModelGateway {
    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    private static final String INSTRUCTIONS =
            "你是 DevPrep 的计算机学习辅导员。只根据给出的资料回答；资料不足时明确说资料中没有证据。"
            + "简洁解释概念、边界和常见误区。用 [1]、[2] 标注支持结论的资料编号，不得虚构来源。";

    private final ModelSettingsService settings;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Clock clock;
    private final Duration cacheDuration;
    private final Map<ModelProvider, CachedCatalog> catalogs = new ConcurrentHashMap<>();
    private final AtomicLong cacheEpoch = new AtomicLong();

    @Autowired
    public HttpModelGateway(ModelSettingsService settings, DevPrepProperties properties,
                            ObjectMapper mapper, Clock clock) {
        this(settings, properties, mapper, clock, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build());
    }

    public HttpModelGateway(ModelSettingsService settings, DevPrepProperties properties,
                            ObjectMapper mapper, Clock clock, HttpClient client) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Provider clients must not follow redirects");
        }
        this.settings = settings;
        this.mapper = mapper;
        this.clock = clock;
        this.client = client;
        this.cacheDuration = Duration.ofSeconds(Math.max(0, properties.getModelCacheSeconds()));
        settings.onChange(() -> {
            cacheEpoch.incrementAndGet();
            catalogs.clear();
        });
    }

    @Override
    public List<String> discover(String providerId, boolean refresh) {
        ModelProvider provider = settings.load().providers().get(providerId);
        if (provider == null) throw ApiException.notFound("provider_not_found", "找不到这个模型 Provider。");
        CachedCatalog cached = catalogs.get(provider);
        Instant now = clock.instant();
        if (!refresh && cached != null && now.isBefore(cached.createdAt().plus(cacheDuration))) {
            return cached.models();
        }
        long epoch = cacheEpoch.get();
        HttpRequest request = request(provider, provider.modelsPath()).GET().build();
        JsonNode decoded = exchange(request, provider.timeoutSeconds(), true);
        JsonNode values = decoded.isObject()
                ? (decoded.has("data") ? decoded.get("data") : decoded.get("models")) : decoded;
        if (values == null || !values.isArray()) {
            throw upstream("invalid_catalog", "模型列表应为数组或包含 data 数组的 JSON。");
        }
        TreeSet<String> identifiers = new TreeSet<>();
        for (JsonNode value : values) {
            JsonNode identifier = value.isObject() ? value.get("id") : value;
            if (identifier != null && identifier.isTextual()) {
                String model = identifier.textValue().strip();
                if (!model.isEmpty() && ModelText.length(model) <= 160) {
                    try { ModelText.utf8(model); }
                    catch (CharacterCodingException ex) {
                        throw upstream("invalid_catalog", "模型列表包含无效的 Unicode 字符。");
                    }
                    identifiers.add(model);
                }
            }
        }
        List<String> models = identifiers.stream().limit(500).toList();
        if (models.isEmpty()) {
            throw upstream("empty_catalog", "网关没有返回可用模型；可以在配置里手动填写模型名。");
        }
        if (epoch == cacheEpoch.get()) catalogs.put(provider, new CachedCatalog(clock.instant(), models));
        return models;
    }

    @Override
    public String generate(ModelConfiguration configuration, String question, List<Map<String, Object>> passages) {
        String model = configuration.model();
        if (model == null || ModelText.length(model) > 160) {
            throw ApiException.badRequest("invalid_model", "模型名必须是短文本。");
        }
        ModelProvider provider = configuration.providers().get(configuration.modelProvider());
        if (provider == null || model.isBlank()) {
            throw ApiException.badRequest("model_not_configured", "先在模型设置中配置 Provider 和模型。");
        }
        if (question == null || question.isBlank() || ModelText.length(question) > 4000) {
            throw ApiException.badRequest("invalid_question", "问题不能为空，且不能超过 4000 个字符。");
        }
        List<Map<String, Object>> usable = new ArrayList<>();
        if (passages != null) {
            for (Map<String, Object> passage : passages) {
                if (passage != null && passage.get("content") instanceof String text && !text.isBlank()) {
                    usable.add(passage);
                    if (usable.size() == 6) break;
                }
            }
        }
        if (usable.isEmpty()) {
            throw ApiException.badRequest("no_sources", "资料库里没有找到相关内容；请先导入参考资料。");
        }
        StringBuilder sourceText = new StringBuilder();
        for (int index = 0; index < usable.size(); index++) {
            Map<String, Object> passage = usable.get(index);
            if (index > 0) sourceText.append("\n\n");
            sourceText.append('[').append(index + 1).append("] ")
                    .append(passage.getOrDefault("title", "")).append(" / ")
                    .append(passage.getOrDefault("heading", "")).append('\n')
                    .append(ModelText.truncate((String) passage.get("content"), 5000));
        }
        String input = "参考资料：\n" + sourceText + "\n\n学习者问题：" + question.strip();
        try { ModelText.utf8(input); }
        catch (CharacterCodingException ex) {
            throw ApiException.badRequest("invalid_question", "问题或参考资料包含无效的 Unicode 字符。");
        }
        boolean responses = provider.wireApi().equals("responses");
        Map<String, Object> body = responses
                ? Map.of("model", model, "instructions", INSTRUCTIONS, "input", input, "store", false)
                : Map.of("model", model, "stream", false, "messages", List.of(
                        Map.of("role", "system", "content", INSTRUCTIONS),
                        Map.of("role", "user", "content", input)));
        byte[] encoded;
        try { encoded = mapper.writeValueAsBytes(body); }
        catch (JsonProcessingException ex) {
            throw ApiException.badRequest("invalid_question", "问题或参考资料无法编码。");
        }
        HttpRequest request = request(provider, responses ? "/responses" : "/chat/completions")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded)).build();
        return parseGeneration(exchange(request, provider.timeoutSeconds(), false), responses);
    }

    @Override
    public AgentReply agentTurn(ModelConfiguration configuration, String instructions,
                               List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        return agentRequest(configuration, instructions, messages, tools, null);
    }

    @Override
    public AgentReply agentStream(ModelConfiguration configuration, String instructions,
                                 List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> delta) {
        return agentRequest(configuration, instructions, messages, tools, delta);
    }

    private AgentReply agentRequest(ModelConfiguration configuration, String instructions,
                                   List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> delta) {
        ModelProvider provider = configuration.providers().get(configuration.modelProvider());
        if (provider == null || configuration.model().isBlank()) {
            throw ApiException.badRequest("model_not_configured", "先在模型设置中配置 Provider 和模型。");
        }
        boolean responses = provider.wireApi().equals("responses");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", configuration.model());
        if (responses) {
            body.put("instructions", instructions);
            body.put("store", false);
            List<Map<String, Object>> input = new ArrayList<>();
            for (Map<String, Object> message : messages) {
                if ("tool".equals(message.get("role"))) {
                    input.add(Map.of("type", "function_call_output", "call_id", message.get("tool_call_id"), "output", message.get("content")));
                } else {
                    if (message.get("content") instanceof String content && !content.isBlank()) {
                        input.add(Map.of("role", message.get("role"), "content", content));
                    }
                    if (message.get("tool_calls") instanceof List<?> calls) for (Object raw : calls) {
                        Map<?, ?> call = (Map<?, ?>) raw;
                        Map<?, ?> function = (Map<?, ?>) call.get("function");
                        input.add(Map.of("type", "function_call", "call_id", call.get("id"),
                                "name", function.get("name"), "arguments", function.get("arguments")));
                    }
                }
            }
            body.put("input", input);
            body.put("tools", tools.stream().map(tool -> {
                Map<?, ?> function = (Map<?, ?>) tool.get("function");
                return Map.of("type", "function", "name", function.get("name"),
                        "description", function.get("description"), "parameters", function.get("parameters"));
            }).toList());
        } else {
            List<Map<String, Object>> input = new ArrayList<>();
            input.add(Map.of("role", "system", "content", instructions));
            input.addAll(messages);
            body.put("messages", input);
            body.put("tools", tools);
        }
        body.put("stream", delta != null);
        body.put("parallel_tool_calls", false);
        byte[] encoded;
        try { encoded = mapper.writeValueAsBytes(body); }
        catch (JsonProcessingException ex) { throw ApiException.badRequest("invalid_message", "对话无法编码。"); }
        HttpRequest request = request(provider, responses ? "/responses" : "/chat/completions")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(encoded)).build();
        if (delta != null) return exchangeAgentStream(request, provider.timeoutSeconds(), responses, delta);
        JsonNode result = exchange(request, provider.timeoutSeconds(), false);
        return parseAgent(result, responses);
    }

    private static AgentReply parseAgent(JsonNode result, boolean responses) {
        checkGenerationStatus(result);
        List<AgentReply.ToolCall> calls = new ArrayList<>();
        if (responses) {
            for (JsonNode output : result.path("output")) if ("function_call".equals(output.path("type").asText())) {
                calls.add(new AgentReply.ToolCall(output.path("call_id").asText(), output.path("name").asText(), output.path("arguments").asText()));
            }
        } else {
            for (JsonNode call : result.path("choices").path(0).path("message").path("tool_calls")) {
                calls.add(new AgentReply.ToolCall(call.path("id").asText(), call.path("function").path("name").asText(), call.path("function").path("arguments").asText()));
            }
        }
        if (calls.size() > 4 || calls.stream().anyMatch(call -> call.id().isBlank() || call.name().isBlank() || call.arguments().length() > 4000)) {
            throw invalidResponse("模型返回的工具调用无效。");
        }
        String text="";
        if (calls.isEmpty()) text=parseGeneration(result, responses);
        else if (!responses) {
            JsonNode message=result.path("choices").path(0).path("message");
            if (truthy(message.get("refusal"))) throw refusal();
            if ("length".equals(result.path("choices").path(0).path("finish_reason").asText())) throw upstream("incomplete_response", "模型回复被截断，请重试。");
            text=message.path("content").asText("");
        }
        return new AgentReply(text, List.copyOf(calls));
    }

    private AgentReply exchangeAgentStream(HttpRequest request, int timeoutSeconds, boolean responses, Consumer<String> delta) {
        CompletableFuture<HttpResponse<AgentReply>> pending=client.sendAsync(request, info -> {
            checkStatus(info.statusCode(),false);
            return new AgentSubscriber(info.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"), responses, delta);
        });
        try { return pending.get(timeoutSeconds, TimeUnit.SECONDS).body(); }
        catch (InterruptedException ex) { pending.cancel(true); Thread.currentThread().interrupt(); throw upstream("agent_interrupted", "本轮回答已中断。"); }
        catch (TimeoutException ex) { pending.cancel(true); throw upstream("provider_unavailable", "模型网关请求超时，请重试。"); }
        catch (ExecutionException ex) {
            for (Throwable cause=ex;cause!=null;cause=cause.getCause()) if (cause instanceof ApiException api) throw api;
            throw upstream("provider_unavailable", "无法连接模型网关，请检查地址和网络。");
        }
    }

    /** Decode complete SSE frames, even when UTF-8 characters cross transport chunks. */
    private final class AgentSubscriber implements HttpResponse.BodySubscriber<AgentReply> {
        private final CompletableFuture<AgentReply> body=new CompletableFuture<>();
        private final boolean sse, responses;
        private final Consumer<String> delta;
        private final ByteArrayOutputStream line=new ByteArrayOutputStream(), jsonBytes=new ByteArrayOutputStream();
        private final StringBuilder frame=new StringBuilder(), text=new StringBuilder();
        private final Map<Integer, ToolParts> calls=new LinkedHashMap<>();
        private JsonNode completed;
        private Flow.Subscription subscription;
        private int bytes;
        private boolean terminal;
        AgentSubscriber(boolean sse, boolean responses, Consumer<String> delta) { this.sse=sse;this.responses=responses;this.delta=delta; }
        @Override public CompletionStage<AgentReply> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription=value;
            body.whenComplete((result,error) -> { if (body.isCancelled()) value.cancel(); });
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            try {
                for (ByteBuffer buffer:buffers) {
                    if (buffer.remaining()>MAX_RESPONSE_BYTES-bytes) throw upstream("response_too_large", "模型回复超过允许大小。");
                    bytes+=buffer.remaining();
                    while (buffer.hasRemaining()) {
                        byte value=buffer.get();
                        if (!sse) { jsonBytes.write(value); continue; }
                        if (value!='\n') { line.write(value); continue; }
                        String row=ModelText.decode(line.toByteArray()); line.reset();
                        if (row.endsWith("\r")) row=row.substring(0,row.length()-1);
                        if (row.isEmpty()) { event(frame.toString()); frame.setLength(0); }
                        else frame.append(row).append('\n');
                    }
                }
                subscription.request(1);
            } catch (Exception ex) { body.completeExceptionally(ex); subscription.cancel(); }
        }
        private void emit(String value) { if (!value.isEmpty()) { text.append(value); delta.accept(value); } }
        private void event(String raw) throws JsonProcessingException {
            StringBuilder data=new StringBuilder();
            for (String row:raw.split("\n")) if (row.startsWith("data:")) { if (!data.isEmpty()) data.append('\n'); data.append(row.substring(5).stripLeading()); }
            if (data.isEmpty()) return;
            if ("[DONE]".contentEquals(data)) { terminal=true; return; }
            JsonNode value=mapper.readTree(data.toString());
            if (value==null || !value.isObject()) throw invalidResponse("模型流事件格式无效。");
            if (truthy(value.get("error"))) throw upstream("provider_generation_failed", "模型生成未成功，请重试。");
            if (responses) {
                String type=value.path("type").asText();
                if ("response.output_text.delta".equals(type)) emit(value.path("delta").asText());
                if ("response.refusal.delta".equals(type)) throw refusal();
                if ("response.completed".equals(type)) { completed=value.path("response"); checkGenerationStatus(completed); terminal=true; }
                if (List.of("response.failed","response.incomplete","error").contains(type)) throw upstream("incomplete_response", "模型回复未完成，请重试。");
            } else {
                JsonNode choice=value.path("choices").path(0), part=choice.path("delta");
                if (truthy(part.get("refusal"))) throw refusal();
                emit(part.path("content").asText(""));
                for (JsonNode call:part.path("tool_calls")) {
                    int index=call.path("index").asInt(-1);
                    if (index<0 || index>3) throw invalidResponse("模型返回的工具调用无效。");
                    ToolParts parts=calls.computeIfAbsent(index, ignored -> new ToolParts());
                    parts.id.append(call.path("id").asText(""));
                    parts.name.append(call.path("function").path("name").asText(""));
                    parts.arguments.append(call.path("function").path("arguments").asText(""));
                    if (parts.arguments.length()>4000) throw invalidResponse("工具参数过长。");
                }
                String finish=choice.path("finish_reason").asText("");
                if ("length".equals(finish)) throw upstream("incomplete_response", "模型回复被截断，请重试。");
                if ("content_filter".equals(finish)) throw refusal();
                if ("stop".equals(finish) || "tool_calls".equals(finish)) terminal=true;
            }
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() {
            if (body.isDone()) return;
            try {
                AgentReply reply;
                if (!sse) {
                    reply=parseAgent(mapper.readTree(ModelText.decode(jsonBytes.toByteArray())),responses);
                    emit(reply.text());
                } else {
                    if (line.size()>0) frame.append(ModelText.decode(line.toByteArray())).append('\n');
                    if (!frame.isEmpty()) event(frame.toString());
                    if (!terminal || (responses && completed==null)) throw upstream("incomplete_response", "连接结束前回答尚未完成，请重试。");
                    if (responses) {
                        reply=parseAgent(completed,true);
                        if (text.isEmpty()) emit(reply.text());
                        reply=new AgentReply(text.toString(),reply.calls());
                    } else {
                        List<AgentReply.ToolCall> tools=new ArrayList<>();
                        for (ToolParts parts:calls.values()) {
                            if (parts.id.isEmpty() || parts.name.isEmpty()) throw invalidResponse("模型返回的工具调用无效。");
                            tools.add(new AgentReply.ToolCall(parts.id.toString(),parts.name.toString(),parts.arguments.toString()));
                        }
                        if (tools.isEmpty() && text.toString().isBlank()) throw upstream("empty_response", "模型没有返回可显示的文本。");
                        reply=new AgentReply(text.toString(),List.copyOf(tools));
                    }
                }
                body.complete(reply);
            } catch (Exception ex) { body.completeExceptionally(ex); }
        }
    }
    private static class ToolParts { final StringBuilder id=new StringBuilder(), name=new StringBuilder(), arguments=new StringBuilder(); }

    private HttpRequest.Builder request(ModelProvider provider, String path) {
        String apiKey = settings.apiKey(provider);
        if (!provider.envKey().isEmpty() && apiKey.isEmpty()) {
            throw ApiException.badRequest("missing_api_key", "请先设置环境变量 " + provider.envKey() + "。");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(provider.endpoint(path)))
                .timeout(Duration.ofSeconds(provider.timeoutSeconds()))
                .header("Accept", "application/json");
        if (!apiKey.isEmpty()) {
            try { builder.header("Authorization", "Bearer " + apiKey); }
            catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("invalid_api_key", "API Key 环境变量包含无效字符，请重新设置。");
            }
        }
        return builder;
    }

    private JsonNode exchange(HttpRequest request, int timeoutSeconds, boolean catalog) {
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, information -> {
            checkStatus(information.statusCode(), catalog);
            return new BoundedSubscriber(catalog);
        });
        byte[] body;
        try {
            // This deadline includes reading the body, including gateways that stall after their headers.
            body = pending.get(timeoutSeconds, TimeUnit.SECONDS).body();
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw upstream("provider_unavailable", "无法连接模型网关，请检查地址和网络。");
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw upstream("provider_unavailable", "模型网关请求超时，请稍后重试。");
        } catch (ExecutionException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof ApiException api) throw api;
            }
            throw upstream("provider_unavailable", "无法连接模型网关，请检查地址和网络。");
        }
        try {
            JsonNode result = mapper.readTree(ModelText.decode(body));
            if (result == null) throw new IOExceptionWithoutDetails();
            return result;
        } catch (JsonProcessingException | CharacterCodingException | IOExceptionWithoutDetails ex) {
            throw upstream(catalog ? "invalid_catalog" : "invalid_response",
                    catalog ? "网关返回的模型列表不是有效 JSON。" : "模型网关返回的内容无法解析。");
        }
    }

    private static void checkStatus(int status, boolean catalog) {
        if (status >= 200 && status < 300) return;
        if (status >= 300 && status < 400) {
            throw upstream("provider_redirect", "模型接口发生重定向，请在设置中填写最终网关地址。");
        }
        if (status == 401 || status == 403) {
            throw new ApiException(401, "provider_auth", "网关拒绝了当前凭据，请检查 API Key 和访问权限。");
        }
        if (status == 429) {
            throw new ApiException(429, "provider_rate_limit", "模型网关暂时限流，请稍后再试。");
        }
        if (catalog && status == 404) {
            throw upstream("models_endpoint_not_found", "模型列表接口返回 404；请检查 base_url 是否包含正确的 /v1 路径。");
        }
        throw upstream("provider_unavailable", (catalog ? "模型列表请求失败" : "模型生成失败") + "（HTTP " + status + "）。");
    }

    private static String parseGeneration(JsonNode result, boolean responses) {
        if (!result.isObject()) throw invalidResponse("模型网关返回的 JSON 结构无效。");
        checkGenerationStatus(result);
        String text;
        if (responses) {
            JsonNode flat = result.get("output_text");
            if (flat != null && !flat.isNull() && !flat.isTextual()) {
                throw invalidResponse("模型文本结构无效。");
            }
            // Gateways may include both forms. A flat text field cannot hide a refusal or partial message.
            JsonNode outputs = result.get("output");
            if (outputs != null && !outputs.isArray()) throw invalidResponse("模型输出应为数组。");
            List<String> parts = new ArrayList<>();
            if (outputs != null) {
                for (JsonNode output : outputs) {
                    if (!output.isObject()) continue;
                    checkGenerationStatus(output);
                    if (output.path("type").asText("").equals("refusal")) throw refusal();
                    JsonNode content = output.get("content");
                    if (content != null && !content.isArray()) throw invalidResponse("模型文本结构无效。");
                    if (content == null) continue;
                    for (JsonNode item : content) {
                        if (!item.isObject()) continue;
                        checkGenerationStatus(item);
                        String type = item.path("type").asText("");
                        if (type.equals("refusal")) throw refusal();
                        if (type.equals("output_text") || type.equals("text")) {
                            JsonNode part = item.get("text");
                            if (part == null || !part.isTextual()) throw invalidResponse("模型文本结构无效。");
                            parts.add(part.textValue());
                        }
                    }
                }
            }
            text = flat == null || flat.isNull() || flat.textValue().isEmpty()
                    ? String.join("\n", parts) : flat.textValue();
        } else {
            JsonNode choices = result.get("choices");
            if (choices != null && !choices.isArray()) throw invalidResponse("模型 choices 结构无效。");
            JsonNode choice = choices == null || choices.isEmpty() ? null : choices.get(0);
            if (choice != null && !choice.isObject()) throw invalidResponse("模型 choices 结构无效。");
            String finishReason = choice == null ? "" : choice.path("finish_reason").asText("");
            if (finishReason.equals("length")) {
                throw upstream("incomplete_response", "模型回复被长度限制截断，请重试或更换模型。");
            }
            if (finishReason.equals("content_filter")) throw refusal();
            JsonNode message = choice == null ? null : choice.get("message");
            if (message != null && !message.isObject()) throw invalidResponse("模型 message 结构无效。");
            if (message != null && truthy(message.get("refusal"))) throw refusal();
            JsonNode content = message == null ? null : message.get("content");
            if (content != null && content.isArray()) {
                List<String> parts = new ArrayList<>();
                for (JsonNode item : content) {
                    if (!item.isObject()) continue;
                    if (item.path("type").asText("").equals("refusal")) throw refusal();
                    JsonNode part = item.get("text");
                    if (part != null && !part.isTextual()) throw invalidResponse("模型文本结构无效。");
                    parts.add(part == null ? "" : part.textValue());
                }
                text = String.join("\n", parts);
            } else {
                if (content != null && !content.isTextual()) throw invalidResponse("模型文本结构无效。");
                text = content == null ? "" : content.textValue();
            }
        }
        if (text.isBlank()) throw upstream("empty_response", "模型没有返回可显示的文本。");
        try { ModelText.utf8(text); }
        catch (CharacterCodingException ex) { throw invalidResponse("模型回复包含无效的 Unicode 字符。"); }
        return text.strip();
    }

    private static void checkGenerationStatus(JsonNode value) {
        String status = value.path("status").asText("");
        if (truthy(value.get("error")) || status.equals("failed") || status.equals("cancelled")) {
            throw upstream("provider_generation_failed", "模型生成未成功，请检查模型名或稍后重试。");
        }
        if (status.equals("incomplete") || status.equals("in_progress") || status.equals("queued")) {
            throw upstream("incomplete_response", "模型回复未完成，请重试或更换模型。");
        }
    }

    private static boolean truthy(JsonNode value) {
        if (value == null || value.isNull()) return false;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.doubleValue() != 0;
        if (value.isTextual()) return !value.textValue().isEmpty();
        return !value.isEmpty();
    }

    private static ApiException invalidResponse(String message) {
        return upstream("invalid_response", message);
    }

    private static ApiException refusal() {
        return new ApiException(422, "provider_refusal", "模型拒绝生成该回答，请调整问题。");
    }

    private static ApiException upstream(String code, String message) {
        return new ApiException(502, code, message);
    }

    private record CachedCatalog(Instant createdAt, List<String> models) { }

    private static final class IOExceptionWithoutDetails extends Exception { }

    private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final boolean catalog;
        private Flow.Subscription subscription;
        private boolean finished;

        private BoundedSubscriber(boolean catalog) { this.catalog = catalog; }

        @Override
        public CompletionStage<byte[]> getBody() { return body; }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (finished) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    finished = true;
                    body.completeExceptionally(upstream(catalog ? "catalog_too_large" : "response_too_large",
                            catalog ? "模型列表响应过大。" : "模型回复超过允许大小。"));
                    subscription.cancel();
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            finished = true;
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (!finished) {
                finished = true;
                body.complete(bytes.toByteArray());
            }
        }
    }
}
