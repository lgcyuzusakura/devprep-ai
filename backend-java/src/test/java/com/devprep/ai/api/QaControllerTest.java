package com.devprep.ai.api;

import com.devprep.ai.domain.QaResult;
import com.devprep.ai.service.QaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QaControllerTest {
    private QaService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(QaService.class);
        mvc = MockMvcBuilders.standaloneSetup(new QaController(service))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @Test
    void answerEndpointValidatesQuestionShapeBeforeCallingService() throws Exception {
        mvc.perform(post("/api/qa").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_json"));
        mvc.perform(post("/api/qa").contentType(MediaType.APPLICATION_JSON).content("{\"question\":12}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_question"));
        verifyNoInteractions(service);
    }

    @Test
    void answerEndpointReturnsTheCitationAndGroundingContract() throws Exception {
        when(service.answer("Explain virtual threads")).thenReturn(new QaResult("[1] They are lightweight.",
                List.of(new QaResult.Citation("chunk-1", "material-1", "Java", "Concurrency", "课程资料",
                        "Virtual threads are lightweight.", 4)), "test-model", true));

        mvc.perform(post("/api/qa").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Explain virtual threads\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("[1] They are lightweight."))
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.model_used").value("test-model"))
                .andExpect(jsonPath("$.citations[0].id").value("chunk-1"));
        verify(service).answer("Explain virtual threads");
    }
}
