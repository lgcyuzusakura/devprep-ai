package com.devprep.ai.model;

import com.devprep.ai.api.ApiErrorHandler;
import com.devprep.ai.api.ModelController;
import com.devprep.ai.config.DevPrepProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ModelControllerTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private ModelGateway gateway;

    @BeforeEach
    void setUp() {
        DevPrepProperties properties = new DevPrepProperties();
        properties.setConfigFile(temporary.resolve("settings.toml").toString());
        ModelSettingsService settings = new ModelSettingsService(properties, name -> null, temporary.resolve("codex"));
        gateway = mock(ModelGateway.class);
        mvc = MockMvcBuilders.standaloneSetup(new ModelController(settings, gateway))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @Test
    void settingsRoundTripReturnsTheFrontendDataShape() throws Exception {
        String toml = "model = \"manual-model\"\nmodel_provider = \"local\"\n"
                + "[model_providers.local]\nbase_url = \"http://127.0.0.1:3000/v1\"\n";
        mvc.perform(post("/api/settings").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("toml", toml))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.model").value("manual-model"))
                .andExpect(jsonPath("$.providers[0].id").value("local"))
                .andExpect(jsonPath("$.providers[0].wire_api").value("responses"))
                .andExpect(jsonPath("$.providers[0].has_api_key").value(false))
                .andExpect(jsonPath("$.providers[0].timeout_seconds").value(15));
        mvc.perform(get("/api/settings"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.model_provider").value("local"))
                .andExpect(jsonPath("$.toml").isString()).andExpect(jsonPath("$.providers").isArray());
    }

    @Test
    void invalidTypedOrMalformedConfigurationReturnsAJsonUserError() throws Exception {
        mvc.perform(post("/api/settings").contentType(MediaType.APPLICATION_JSON).content("{\"toml\":123}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_config"));
        mvc.perform(post("/api/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toml\":\"model = \\\"unterminated\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_toml"));
    }

    @Test
    void modelsPassThroughProviderAndRefreshContract() throws Exception {
        when(gateway.discover("local", true)).thenReturn(List.of("a-model", "z-model"));
        mvc.perform(get("/api/models").param("provider", "local").param("refresh", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("local"))
                .andExpect(jsonPath("$.models[0]").value("a-model"));
        verify(gateway).discover("local", true);
    }

    @Test
    void codexImportIsATomlPreviewAndDoesNotSaveIt() throws Exception {
        Path codex = temporary.resolve("codex");
        Files.createDirectories(codex);
        Files.writeString(codex.resolve("config.toml"), "model = \"fixture-model\"\n");
        mvc.perform(post("/api/settings/import-codex").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.toml").isString());
        mvc.perform(get("/api/settings"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.model").value(""));
    }

    @Test
    void settingsApiNeverReturnsAnInlineProviderSecret() throws Exception {
        String secret = "synthetic-inline-secret-must-not-leak";
        String toml = "model = \"manual-model\"\nmodel_provider = \"local\"\n"
                + "[model_providers.local]\nbase_url = \"http://127.0.0.1:3000/v1\"\n"
                + "env_key = \"LOCAL_API_KEY\"\napi_key = \"" + secret + "\"\n";

        String response = mvc.perform(post("/api/settings").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("toml", toml))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertFalse(response.contains(secret));
        assertTrue(response.contains("LOCAL_API_KEY"));
    }
}
