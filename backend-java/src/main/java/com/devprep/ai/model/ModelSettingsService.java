package com.devprep.ai.model;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.config.DevPrepProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

@Service
public class ModelSettingsService {
    @org.springframework.beans.factory.annotation.Value("${devprep.member-model:gpt-6.1-sol}")
    private String memberModel = "gpt-6.1-sol";
    private static final int MAX_CONFIG_BYTES = 100_000;
    private final Path settingsFile;
    private final Function<String, String> environment;
    private final Path defaultCodexHome;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    @Autowired
    public ModelSettingsService(DevPrepProperties properties) {
        this(properties, System::getenv, Path.of(System.getProperty("user.home"), ".codex"));
    }

    /** An explicit environment and home keep tests independent of local credentials. */
    public ModelSettingsService(DevPrepProperties properties, Function<String, String> environment,
                                Path defaultCodexHome) {
        String configured = properties.getConfigFile();
        settingsFile = (configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".devprep-ai", "settings.toml")
                : Path.of(configured)).toAbsolutePath().normalize();
        this.environment = environment;
        this.defaultCodexHome = defaultCodexHome;
    }

    public synchronized ModelConfiguration load() {
        if (!Files.exists(settingsFile)) return ModelConfiguration.empty();
        TomlParseResult parsed;
        try {
            if (Files.size(settingsFile) > MAX_CONFIG_BYTES) throw new IOException("Config too large");
            parsed = Toml.parse(Files.readString(settingsFile));
            if (parsed.hasErrors()) throw new IOException("Invalid TOML");
        } catch (IOException | IllegalArgumentException ex) {
            throw new ApiException(500, "config_unreadable", "设置文件无法读取；请在设置中修复 TOML。");
        }
        ModelConfiguration configuration = normalize(parsed);
        if (!com.devprep.ai.auth.WorkspaceContext.isAdmin()) {
            return new ModelConfiguration(memberModel, configuration.modelProvider(), configuration.providers());
        }
        return configuration;
    }

    public Map<String, Object> publicSnapshot() {
        return publicSnapshot(load());
    }

    public Map<String, Object> publicSnapshot(ModelConfiguration configuration) {
        List<Map<String, Object>> providers = new ArrayList<>();
        for (ModelProvider provider : configuration.providers().values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", provider.id());
            item.put("name", provider.name());
            item.put("base_url", provider.baseUrl());
            item.put("wire_api", provider.wireApi());
            item.put("env_key", provider.envKey());
            item.put("models_path", provider.modelsPath());
            item.put("timeout_seconds", provider.timeoutSeconds());
            item.put("has_api_key", !provider.envKey().isEmpty() && !apiKey(provider).isEmpty());
            providers.add(item);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("toml", serialize(configuration));
        snapshot.put("model", configuration.model());
        snapshot.put("model_provider", configuration.modelProvider());
        snapshot.put("providers", providers);
        return snapshot;
    }

    public synchronized Map<String, Object> save(String raw) {
        ModelConfiguration configuration = parse(raw);
        Path temporary = settingsFile.resolveSibling(settingsFile.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(settingsFile.getParent());
            Files.write(temporary, ModelText.utf8(serialize(configuration)));
            try {
                Files.move(temporary, settingsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new ApiException(500, "config_unwritable", "模型设置无法保存，请检查设置目录是否可写。");
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException ignored) { }
        }
        changeListeners.forEach(Runnable::run);
        return publicSnapshot(configuration);
    }

    public void onChange(Runnable listener) {
        changeListeners.add(listener);
    }

    public ModelConfiguration parse(String raw) {
        if (raw == null) throw invalid("toml 必须是文本。");
        try {
            if (ModelText.utf8(raw).length > MAX_CONFIG_BYTES) {
                throw new ApiException(413, "invalid_config", "模型配置不能超过 100 KB。");
            }
        } catch (CharacterCodingException ex) {
            throw invalid("模型配置包含无效的 Unicode 字符。");
        }
        TomlParseResult parsed = Toml.parse(raw);
        if (parsed.hasErrors()) {
            // Do not echo TOML values: a malformed draft can contain a pasted secret.
            throw ApiException.badRequest("invalid_toml", "TOML 格式有误，请检查引号、字段类型和表名称。");
        }
        return normalize(parsed);
    }

    public String importCodexConfig() {
        String override = environment.apply("CODEX_HOME");
        Path codexHome = override == null || override.isBlank() ? defaultCodexHome : expandedHome(override);
        Path path = codexHome.resolve("config.toml");
        TomlParseResult parsed;
        try {
            if (!Files.exists(path)) {
                throw ApiException.notFound("codex_config_not_found", "没有找到本机 Codex config.toml。");
            }
            if (Files.size(path) > 1_000_000) throw new IOException("Codex config too large");
            parsed = Toml.parse(Files.readString(path));
            if (parsed.hasErrors()) throw new IOException("Invalid TOML");
        } catch (IOException | IllegalArgumentException ex) {
            throw ApiException.badRequest("codex_config_invalid", "本机 Codex TOML 配置无法解析。");
        }
        String model = text(parsed.get("model"), "", 160, "model 必须是短文本。");
        String providerId = text(parsed.get("model_provider"), "", 64, "model_provider 必须是短文本。");
        Object table = parsed.get("model_providers");
        if (table != null && !(table instanceof TomlTable)) {
            throw ApiException.badRequest("codex_config_invalid", "本机 Codex Provider 配置无效。");
        }
        Map<String, ModelProvider> providers = new LinkedHashMap<>();
        if (table instanceof TomlTable tables && tables.keySet().contains(providerId)) {
            Object raw = tables.get(providerId);
            if (!(raw instanceof TomlTable provider)) {
                throw ApiException.badRequest("codex_config_invalid", "本机 Codex Provider 配置无效。");
            }
            // Only the selected provider and this explicit allowlist leave the Codex file.
            validateProviderId(providerId);
            providers.put(providerId, provider(providerId, provider, true));
        }
        if (!providerId.isEmpty() && !providers.containsKey(providerId)) {
            throw invalid("选中的 model_provider 不在配置列表中。");
        }
        return serialize(new ModelConfiguration(model, providerId, providers));
    }

    public String apiKey(ModelProvider provider) {
        if (provider.envKey().isEmpty()) return "";
        String value = environment.apply(provider.envKey());
        return value == null ? "" : value;
    }

    private ModelConfiguration normalize(TomlTable parsed) {
        String model = text(parsed.get("model"), "", 160, "model 必须是短文本。");
        String selected = text(parsed.get("model_provider"), "", 64, "model_provider 必须是短文本。");
        Object rawTables = parsed.get("model_providers");
        if (rawTables != null && !(rawTables instanceof TomlTable)) {
            throw invalid("model_providers 必须是 TOML 表。");
        }
        Map<String, ModelProvider> providers = new LinkedHashMap<>();
        if (rawTables instanceof TomlTable tables) {
            for (String providerId : tables.keySet()) {
                validateProviderId(providerId);
                Object raw = tables.get(providerId);
                if (!(raw instanceof TomlTable table)) throw invalid("Provider 名称或配置无效。");
                providers.put(providerId, provider(providerId, table, false));
            }
        }
        if (!selected.isEmpty() && !providers.containsKey(selected)) {
            throw invalid("选中的 model_provider 不在配置列表中。");
        }
        return new ModelConfiguration(model, selected, providers);
    }

    private ModelProvider provider(String providerId, TomlTable table, boolean importing) {
        String name = text(table.get("name"), providerId, 100, "Provider name 必须是短文本。");
        String baseUrl = validateBaseUrl(table.get("base_url"));
        String wireApi = text(table.get("wire_api"), "responses", 30, "wire_api 支持 responses 或 chat_completions。");
        if (wireApi.equals("chat")) wireApi = "chat_completions";
        if (!wireApi.equals("responses") && !wireApi.equals("chat_completions")) {
            throw invalid("wire_api 支持 responses 或 chat_completions。");
        }
        Object auth = table.get("requires_openai_auth");
        if (auth != null && !(auth instanceof Boolean)) throw invalid("requires_openai_auth 必须是布尔值。");
        String envKey = text(table.get("env_key"), Boolean.TRUE.equals(auth) ? "OPENAI_API_KEY" : "", 64,
                "env_key 应为环境变量名称，例如 OPENAI_API_KEY。");
        if (!envKey.isEmpty() && !envKey.matches("[A-Z_][A-Z0-9_]{0,63}")) {
            throw invalid("env_key 应为环境变量名称，例如 OPENAI_API_KEY。");
        }
        String modelsPath = importing ? "/models" : validateModelsPath(table.get("models_path"));
        Object timeoutValue = importing ? 15L : table.get("timeout_seconds");
        long timeout = timeoutValue == null ? 15 : timeoutValue instanceof Long number ? number : -1;
        if (timeout < 2 || timeout > 120) throw invalid("timeout_seconds 范围为 2 到 120 秒。");
        return new ModelProvider(providerId, name, baseUrl, wireApi, envKey, modelsPath, (int) timeout);
    }

    private static void validateProviderId(String providerId) {
        if (!providerId.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")) throw invalid("Provider 名称或配置无效。");
    }

    static String validateBaseUrl(Object value) {
        if (!(value instanceof String raw) || ModelText.length(raw) > 500) {
            throw invalid("Provider 的 base_url 无效。");
        }
        String baseUrl = raw.strip();
        if (baseUrl.codePoints().anyMatch(ModelText::forbiddenUrlCharacter)) {
            throw invalid("base_url 不能包含空白或控制字符。");
        }
        try {
            URI uri = new URI(baseUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw invalid("base_url 需要是有效的 http/https 地址，且不能包含凭据。");
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw invalid("请不要把密钥放进 base_url 的 query 或 fragment。");
            }
            return baseUrl.replaceFirst("/+$", "");
        } catch (URISyntaxException ex) {
            throw invalid("Provider 的地址或端口无效。");
        }
    }

    static String validateModelsPath(Object value) {
        String path = text(value, "/models", 500, "models_path 必须是以 / 开始的站内路径。");
        String decoded;
        try {
            // URI decoding preserves '+', unlike form URLDecoder.
            decoded = new URI(path).getPath();
        } catch (URISyntaxException ex) {
            throw invalid("models_path 必须是以 / 开始的站内路径。");
        }
        if (!path.startsWith("/") || path.contains("//") || decoded == null
                || !decoded.startsWith("/") || decoded.contains("//") || decoded.contains("..")
                || decoded.contains("/./") || decoded.endsWith("/.")
                // Reject another encoded layer; reverse proxies can decode a path more than once.
                || decoded.indexOf('%') >= 0
                || decoded.indexOf('?') >= 0 || decoded.indexOf('#') >= 0
                || decoded.codePoints().anyMatch(character -> ModelText.forbiddenUrlCharacter(character)
                        || Character.isISOControl(character))
                || path.codePoints().anyMatch(character -> ModelText.forbiddenUrlCharacter(character)
                        || Character.isISOControl(character))
                || path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw invalid("models_path 必须是以 / 开始的站内路径。");
        }
        return path;
    }

    private static String text(Object value, String fallback, int maximum, String message) {
        if (value == null) return fallback;
        if (!(value instanceof String text) || ModelText.length(text) > maximum) throw invalid(message);
        try { ModelText.utf8(text); }
        catch (CharacterCodingException ex) { throw invalid("模型配置包含无效的 Unicode 字符。"); }
        return text;
    }

    public String serialize(ModelConfiguration configuration) {
        StringBuilder output = new StringBuilder();
        output.append("model = ").append(quoted(configuration.model())).append('\n');
        output.append("model_provider = ").append(quoted(configuration.modelProvider())).append('\n');
        for (ModelProvider provider : configuration.providers().values()) {
            output.append("\n[model_providers.").append(provider.id()).append("]\n");
            output.append("name = ").append(quoted(provider.name())).append('\n');
            output.append("base_url = ").append(quoted(provider.baseUrl())).append('\n');
            output.append("wire_api = ").append(quoted(provider.wireApi())).append('\n');
            output.append("env_key = ").append(quoted(provider.envKey())).append('\n');
            output.append("models_path = ").append(quoted(provider.modelsPath())).append('\n');
            output.append("timeout_seconds = ").append(provider.timeoutSeconds()).append('\n');
        }
        return output.toString();
    }

    private static String quoted(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        value.codePoints().forEach(character -> {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\f' -> escaped.append("\\f");
                case '\r' -> escaped.append("\\r");
                default -> {
                    if (character < 32 || character == 127) escaped.append(String.format("\\u%04X", character));
                    else escaped.appendCodePoint(character);
                }
            }
        });
        return escaped.append('"').toString();
    }

    private static Path expandedHome(String value) {
        if (value.equals("~")) return Path.of(System.getProperty("user.home"));
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home"), value.substring(2));
        }
        return Path.of(value);
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("invalid_config", message);
    }
}
