package com.devprep.ai.api;

import com.devprep.ai.domain.AttemptResult;
import com.devprep.ai.domain.Dashboard;
import com.devprep.ai.domain.HistoryAttempt;
import com.devprep.ai.domain.PracticeQuestion;
import com.devprep.ai.domain.PracticeQueue;
import com.devprep.ai.domain.QuestionReference;
import com.devprep.ai.domain.TopicSummary;
import com.devprep.ai.service.LearningService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LearningController {
    private final LearningService learning;
    public LearningController(LearningService learning) { this.learning = learning; }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("ok", true, "version", "0.2.0"); }

    @GetMapping("/dashboard")
    public Dashboard dashboard() { return learning.dashboard(); }

    @GetMapping("/knowledge")
    public Map<String, List<TopicSummary>> knowledge() { return Map.of("topics", learning.knowledge()); }

    @GetMapping("/practice/next")
    public PracticeQuestion next(@RequestParam(name = "knowledge_point_id", required = false) String pointId) {
        return learning.nextQuestion(pointId);
    }

    @PostMapping("/practice/queue")
    public PracticeQueue queue(@RequestBody JsonNode body) {
        if (!body.isObject()) throw ApiException.badRequest("invalid_json", "请求内容必须是 JSON 对象。");
        for (String field : List.of("knowledge_point_id", "domain", "bank", "question_type", "mode"))
            if (body.has(field) && !body.path(field).isTextual()) throw ApiException.badRequest("invalid_scope", "练习范围必须为文本。");
        List<String> excluded = new ArrayList<>();
        if (body.has("exclude_ids")) {
            if (!body.path("exclude_ids").isArray() || body.path("exclude_ids").size() > 200)
                throw ApiException.badRequest("invalid_exclusions", "单轮练习最多排除 200 道题。");
            for (JsonNode id : body.path("exclude_ids")) {
                if (!id.isTextual()) throw ApiException.badRequest("invalid_exclusions", "题目编号必须为文本。");
                excluded.add(id.textValue());
            }
        }
        return learning.practiceQueue(body.path("knowledge_point_id").asText(""), body.path("domain").asText(""),
                body.path("bank").asText(""), body.path("question_type").asText("single_choice"), body.path("mode").asText("new"), excluded);
    }

    @GetMapping("/practice/banks")
    public Object banks() { return Map.of("banks", learning.practiceBanks()); }

    @GetMapping("/practice/reference")
    public QuestionReference reference(@RequestParam(name = "question_id", defaultValue = "") String id) { return learning.reference(id); }

    @GetMapping("/history")
    public Map<String, List<HistoryAttempt>> history(@RequestParam(defaultValue = "30") String limit) {
        return Map.of("attempts", learning.history(limit));
    }

    @PostMapping("/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public AttemptResult attempt(@RequestBody JsonNode payload) {
        if (!payload.isObject()) throw ApiException.badRequest("invalid_json", "请求内容必须是 JSON 对象。");
        JsonNode id = payload.get("question_id");
        JsonNode answer = payload.get("answer");
        JsonNode selfAssessment = payload.get("self_assessment");
        return learning.submitAttempt(id != null && id.isTextual() ? id.textValue() : null,
                answer != null && answer.isTextual() ? answer.textValue() : null, rubricHits(payload.get("rubric_hits")),
                selfAssessment != null && selfAssessment.isBoolean() ? selfAssessment.booleanValue() : null);
    }

    private List<Boolean> rubricHits(JsonNode node) {
        if (node == null || !node.isArray()) return null;
        List<Boolean> hits = new ArrayList<>();
        for (JsonNode value : node) { if (!value.isBoolean()) return null; hits.add(value.booleanValue()); }
        return hits;
    }
}
