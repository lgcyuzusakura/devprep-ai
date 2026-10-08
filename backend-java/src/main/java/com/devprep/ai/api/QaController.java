package com.devprep.ai.api;

import com.devprep.ai.domain.QaResult;
import com.devprep.ai.service.QaService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QaController {
    private final QaService service;

    public QaController(QaService service) { this.service = service; }

    @PostMapping("/api/qa")
    public QaResult answer(@RequestBody JsonNode payload) {
        if (!payload.isObject()) throw ApiException.badRequest("invalid_json", "请求内容必须是 JSON 对象。");
        JsonNode question = payload.get("question");
        if (question == null || !question.isTextual()) {
            throw ApiException.badRequest("invalid_question", "question 必须是文本。");
        }
        return service.answer(question.textValue());
    }
}
