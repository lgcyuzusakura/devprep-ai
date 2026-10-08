package com.devprep.ai.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiErrorHandlerTest {
    private final ApiErrorHandler handler = new ApiErrorHandler();

    @Test
    void preservesStructuredApplicationErrorStatusAndCode() {
        ResponseEntity<Map<String, Object>> response = handler.handleApi(
                new ApiException(422, "provider_refusal", "模型拒绝生成该回答，请调整问题。"));

        assertEquals(422, response.getStatusCode().value());
        assertEquals(Map.of("code", "provider_refusal", "message", "模型拒绝生成该回答，请调整问题。"), response.getBody().get("error"));
    }

    @Test
    void unexpectedFailuresDoNotLeakExceptionDetails() {
        ResponseEntity<Map<String, Object>> response = handler.handleUnexpected(
                new IllegalStateException("private token and database connection details"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals(Map.of("code", "internal_error", "message", "本地数据暂时不可用。"), response.getBody().get("error"));
        assertFalse(response.getBody().toString().contains("private token"));
    }
}
