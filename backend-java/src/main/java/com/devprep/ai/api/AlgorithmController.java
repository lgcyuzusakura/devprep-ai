package com.devprep.ai.api;

import com.devprep.ai.service.AlgorithmService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/algorithms")
public class AlgorithmController {
    private final AlgorithmService algorithms;
    public AlgorithmController(AlgorithmService algorithms) { this.algorithms = algorithms; }
    @GetMapping public Map<String, Object> list() { return Map.of("problems", algorithms.list(), "collections", algorithms.collections()); }
    @GetMapping("/{id}") public Object detail(@PathVariable String id) { return algorithms.detail(id); }
    @GetMapping("/{id}/editorial") public Object editorial(@PathVariable String id) { return algorithms.editorial(id); }
    @GetMapping("/{id}/submissions") public Object history(@PathVariable String id) { return Map.of("submissions", algorithms.history(id)); }
    @PostMapping("/{id}/run") public Object run(@PathVariable String id, @RequestBody JsonNode body) { return judge(id, body, false); }
    @PostMapping("/{id}/submit") public Object submit(@PathVariable String id, @RequestBody JsonNode body) { return judge(id, body, true); }
    private Object judge(String id, JsonNode body, boolean submit) {
        if (!body.path("language").isTextual() || !body.path("code").isTextual()) throw ApiException.badRequest("invalid_code", "language与code必须为文本。");
        return algorithms.judge(id, body.path("language").asText(), body.path("code").asText(), submit);
    }
}
