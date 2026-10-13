package com.devprep.ai.service;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.data.JsonCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
public class AlgorithmService {
    private final Map<String, JsonNode> problems = new LinkedHashMap<>();
    private final JsonNode templates;
    private JsonNode collectionDefinitions;
    private final ObjectMapper mapper;
    private final JsonCodec json;
    private final JdbcTemplate jdbc;
    private final String runnerUrl;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Semaphore active = new Semaphore(2);
    public AlgorithmService(ObjectMapper mapper, JsonCodec json, JdbcTemplate jdbc, @Value("${devprep.runner-url:}") String runnerUrl,
                            @Value("${devprep.algorithm-file:}") String algorithmFile) throws java.io.IOException {
        this.mapper = mapper; this.json = json; this.jdbc = jdbc; this.runnerUrl = runnerUrl;
        this.collectionDefinitions = mapper.createArrayNode();
        try (var stream = new ClassPathResource("seed/algorithms.json").getInputStream()) {
            for (JsonNode problem : mapper.readTree(stream).path("problems")) problems.put(problem.path("id").asText(), problem);
        }
        try (var stream = new ClassPathResource("seed/algorithm-templates.json").getInputStream()) { templates = mapper.readTree(stream); }
        var index = new ClassPathResource("seed/leetcode-index.json");
        if (index.exists()) try (var stream = index.getInputStream()) {
            JsonNode catalog = mapper.readTree(stream);
            collectionDefinitions = catalog.path("collections");
            for (JsonNode item : catalog.path("problems")) {
                ObjectNode problem = item.deepCopy();
                problem.put("description", "打开 LeetCode 原题学习，或运行导入脚本添加本地中文题面。");
                problem.put("official", true); problem.put("judge_scope", "examples");
                problem.putArray("examples"); problem.putArray("tests"); problem.putArray("hints"); problem.putArray("runnable_languages");
                problem.put("editorial", "通过原题链接查看官方题解。");
                problem.set("templates", templates);
                problem.set("language_notes", mapper.valueToTree(Map.of("java", "在原题页面运行官方测试", "go", "在原题页面运行官方测试", "python", "在原题页面运行官方测试", "javascript", "在原题页面运行官方测试")));
                problems.put(item.path("id").asText(), problem);
            }
        }
        if (!algorithmFile.isBlank()) try (var stream = java.nio.file.Files.newInputStream(java.nio.file.Path.of(algorithmFile))) {
            JsonNode catalog = mapper.readTree(stream);
            if (catalog.path("collections").isArray()) collectionDefinitions = catalog.path("collections");
            for (JsonNode problem : catalog.path("problems")) problems.put(problem.path("id").asText(), problem);
        }
    }
    public List<Map<String, Object>> collections() {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, String> titles = new LinkedHashMap<>();
        for (JsonNode definition : collectionDefinitions) titles.put(definition.path("id").asText(), definition.path("title").asText());
        titles.putIfAbsent("hot100", "LeetCode Hot 100");
        titles.putIfAbsent("first100", "题号 1–100");
        for (JsonNode problem : problems.values()) for (JsonNode collection : problem.path("collections")) titles.putIfAbsent(collection.asText(), collection.asText());
        titles.forEach((id, title) -> {
            long count = problems.values().stream().filter(problem -> {
                for (JsonNode collection : problem.path("collections")) if (id.equals(collection.asText())) return true;
                return false;
            }).count();
            if (count > 0) result.add(Map.of("id", id, "title", title, "count", count));
        });
        return result;
    }
    public List<Map<String, Object>> list() {
        return problems.values().stream().map(problem -> {
            Boolean solved = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM algorithm_submissions WHERE problem_id=? AND result_json LIKE ?)", Boolean.class, problem.path("id").asText(), "%\"status\":\"accepted\"%");
            Map<String, Object> result = new LinkedHashMap<>();
            for (String field : List.of("id", "title", "tag", "difficulty")) result.put(field, problem.path(field).asText());
            result.put("number", problem.path("number").asInt(0)); result.put("collections", problem.path("collections"));
            result.put("solved", Boolean.TRUE.equals(solved)); result.put("official", problem.path("official").asBoolean());
            return result;
        }).toList();
    }
    public ObjectNode detail(String id) {
        ObjectNode node = require(id).deepCopy();
        node.remove("tests"); node.remove("editorial"); node.remove("runner_templates"); node.remove("solution_markers");
        if (!node.has("templates")) node.set("templates", templates);
        return node;
    }
    public Map<String, Object> editorial(String id) { return Map.of("explanation", require(id).path("editorial").asText(), "source", require(id).path("source").asText()); }
    public List<Map<String, Object>> history(String id) {
        require(id);
        return jdbc.query("SELECT id,language,code,result_json,created_at FROM algorithm_submissions WHERE problem_id=? ORDER BY seq DESC LIMIT 30",
                (rs, n) -> Map.of("id", rs.getString("id"), "language", rs.getString("language"), "code", rs.getString("code"), "result", json.readTree(rs.getString("result_json")), "created_at", rs.getString("created_at")), id);
    }
    public Map<String, Object> judge(String id, String language, String code, boolean submit) {
        JsonNode problem = require(id);
        if (!List.of("java", "go", "python", "javascript").contains(language)) throw ApiException.badRequest("invalid_language", "支持 Java、Go、Python 和 JavaScript。");
        if (problem.path("official").asBoolean() && (!problem.path("runnable_languages").toString().contains("\"" + language + "\"") || problem.path("examples").isEmpty())) {
            throw ApiException.badRequest("official_judge", "本题的复杂接口请到 LeetCode 原题运行和提交。");
        }
        if (code == null || code.isBlank() || code.length() > 30000) throw ApiException.badRequest("invalid_code", "代码需要为1–30000个字符。");
        if (runnerUrl.isBlank()) throw new ApiException(503, "runner_not_configured", "代码运行服务未启动，请使用 Docker Compose 启动。");
        if (!active.tryAcquire()) throw new ApiException(429, "runner_busy", "判题正在处理其他提交，请稍后再试。");
        try {
            String submittedCode = code;
            String executable = code;
            if (problem.path("official").asBoolean()) {
                String wrapper = problem.path("runner_templates").path(language).asText();
                String marker = problem.path("solution_markers").path(language).asText();
                if (marker.isEmpty() || !wrapper.contains(marker)) throw new IllegalStateException("Missing function wrapper");
                if (language.equals("java") || language.equals("go")) {
                    var imports = java.util.regex.Pattern.compile("(?m)^\\s*import\\s+(?:\\([^)]*\\)|[^\\n;]+;?)\\s*$").matcher(code);
                    StringBuilder extra = new StringBuilder();
                    while (imports.find()) extra.append(imports.group().strip()).append('\n');
                    code = imports.replaceAll("");
                    executable = wrapper.replace(marker, code);
                    if (language.equals("java")) executable = extra + executable;
                    else executable = executable.replaceFirst("package main", "package main\n" + java.util.regex.Matcher.quoteReplacement(extra.toString()));
                } else executable = wrapper.replace(marker, code);
            }
            List<JsonNode> cases = new ArrayList<>();
            problem.path("examples").forEach(cases::add);
            if (submit) problem.path("tests").forEach(cases::add);
            var request = HttpRequest.newBuilder(URI.create(runnerUrl + "/run")).timeout(Duration.ofSeconds(80))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.write(Map.of("language", language, "code", executable, "inputs", cases.stream().map(c -> c.get("input")).toList())))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 200000) throw new IllegalStateException("Runner response invalid");
            JsonNode output = mapper.readTree(response.body());
            Map<String, Object> result = new LinkedHashMap<>();
            List<Map<String, Object>> results = new ArrayList<>();
            int passed = 0;
            String status = output.path("status").asText();
            if ("completed".equals(status)) {
                JsonNode runs = output.path("cases");
                if (!runs.isArray() || runs.size() != cases.size()) throw new IllegalStateException("Incomplete runner results");
                for (int i = 0; i < cases.size(); i++) {
                    JsonNode run = runs.get(i); boolean ok = false; JsonNode actual = null;
                    if ("ok".equals(run.path("status").asText())) try { actual = mapper.readTree(run.path("stdout").asText().strip()); ok = compare(problem, cases.get(i), actual); } catch (Exception ignored) { }
                    if (ok) passed++;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("index", i + 1); row.put("passed", ok); row.put("hidden", i >= problem.path("examples").size());
                    row.put("status", ok ? "accepted" : "ok".equals(run.path("status").asText()) ? "wrong_answer" : run.path("status").asText());
                    row.put("duration_ms", run.path("duration_ms").asInt());
                    if (i < problem.path("examples").size()) { row.put("input", cases.get(i).get("input")); row.put("expected", cases.get(i).get("expected")); row.put("actual", actual); row.put("stderr", run.path("stderr").asText()); }
                    results.add(row);
                }
                status = passed == cases.size() ? "accepted" : results.stream().anyMatch(r -> "timeout".equals(r.get("status"))) ? "timeout" : results.stream().anyMatch(r -> "runtime_error".equals(r.get("status"))) ? "runtime_error" : "wrong_answer";
            }
            result.put("status", status); result.put("passed", passed); result.put("total", cases.size()); result.put("cases", results);
            result.put("compiler_output", output.path("compiler_output").asText()); result.put("language", language); result.put("submitted", submit);
            result.put("judge_scope", problem.path("judge_scope").asText("local_suite"));
            if (submit) {
                String submissionId = UUID.randomUUID().toString(); result.put("id", submissionId);
                jdbc.update("INSERT INTO algorithm_submissions(id,problem_id,language,code,result_json,created_at) VALUES(?,?,?,?,?,?)", submissionId, id, language, submittedCode, json.write(result), Instant.now().toString());
            }
            return result;
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new ApiException(503, "runner_interrupted", "判题已中断。"); }
        catch (java.io.IOException | IllegalStateException ex) { throw new ApiException(503, "runner_unavailable", "代码运行服务暂时不可用，请检查 Docker 容器。"); }
        finally { active.release(); }
    }
    private JsonNode require(String id) { JsonNode problem = problems.get(id); if (problem == null) throw ApiException.notFound("problem_not_found", "找不到这道算法题。"); return problem; }
    private static boolean compare(JsonNode problem, JsonNode test, JsonNode actual) {
        JsonNode expected = test.get("expected");
        int number = problem.path("number").asInt();
        if (number == 5 && actual != null && actual.isTextual()) {
            String value = actual.asText();
            return value.length() == expected.asText().length() && test.path("input").path("s").asText().contains(value)
                    && value.equals(new StringBuilder(value).reverse().toString());
        }
        if (Set.of(15,18,39,40,49,78,90).contains(number) && actual != null && actual.isArray()) return canonical(expected).equals(canonical(actual));
        if (Set.of(1,17,22,46,47,51,77,93,95,131,347).contains(number) && actual != null && actual.isArray()) return topLevelCanonical(expected).equals(topLevelCanonical(actual));
        return equal(expected, actual);
    }
    private static String topLevelCanonical(JsonNode node) { List<String> values = new ArrayList<>(); node.forEach(value -> values.add(value.toString())); Collections.sort(values); return values.toString(); }
    private static String canonical(JsonNode node) {
        if (!node.isArray()) return node.toString();
        List<String> values = new ArrayList<>(); node.forEach(value -> values.add(canonical(value))); Collections.sort(values);
        return values.toString();
    }
    static boolean equal(JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null) return expected == actual;
        if (expected.isNumber() && actual.isNumber()) return Math.abs(expected.asDouble() - actual.asDouble()) <= 1e-9;
        if (expected.isArray() && actual.isArray() && expected.size() == actual.size()) { for (int i=0;i<expected.size();i++) if (!equal(expected.get(i), actual.get(i))) return false; return true; }
        return expected.equals(actual);
    }
}
