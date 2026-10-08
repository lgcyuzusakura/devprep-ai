package com.devprep.ai.model;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.config.DevPrepProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ModelSettingsServiceTest {
    @TempDir Path temporary;
    private ModelSettingsService settings;

    @BeforeEach
    void setUp() {
        DevPrepProperties properties = new DevPrepProperties();
        properties.setConfigFile(temporary.resolve("data/settings.toml").toString());
        settings = new ModelSettingsService(properties, Map.of("EXAMPLE_TOKEN", "synthetic-fixture-secret")::get,
                temporary.resolve("codex"));
    }

    @Test
    void missingConfigurationIsAnEmptyEditableSnapshot() {
        assertEquals(ModelConfiguration.empty(), settings.load());
        assertEquals("", settings.publicSnapshot().get("model"));
        assertEquals("model = \"\"\nmodel_provider = \"\"\n", settings.publicSnapshot().get("toml"));
    }

    @Test
    void savesNormalizedAllowlistedTomlAndPreservesUnicodeAndEscapes() throws Exception {
        String raw = """
                model = "example-model"
                model_provider = "example"
                api_key = "synthetic-do-not-export"
                [projects.workspace]
                trust_level = "trusted"
                [model_providers.example]
                name = "Quoted \\"name\\"\\twith\\rcontrol\\ncharacters\\\\path 和中文"
                base_url = "https://gateway.example/v1/"
                wire_api = "chat"
                env_key = "EXAMPLE_TOKEN"
                api_key = "synthetic-do-not-export"
                """;
        AtomicInteger changes = new AtomicInteger();
        settings.onChange(changes::incrementAndGet);
        Map<String, Object> saved = settings.save(raw);
        String normalized = (String) saved.get("toml");
        assertFalse(normalized.contains("synthetic"));
        assertFalse(normalized.contains("projects"));
        assertEquals(1, changes.get());
        assertEquals(settings.parse(normalized), settings.load());
        ModelProvider provider = settings.load().providers().get("example");
        assertEquals("https://gateway.example/v1", provider.baseUrl());
        assertEquals("chat_completions", provider.wireApi());
        assertEquals("Quoted \"name\"\twith\rcontrol\ncharacters\\path 和中文", provider.name());
        assertEquals(15, provider.timeoutSeconds());
        assertEquals("/models", provider.modelsPath());
        assertFalse(saved.toString().contains("synthetic-fixture-secret"));
        assertTrue(saved.toString().contains("has_api_key=true"));
        try (var files = Files.list(temporary.resolve("data"))) {
            assertEquals(1, files.count(), "No temporary settings file is left after replacement");
        }
    }

    @Test
    void defaultsUseOnlyTheExplicitEnvironmentVariable() {
        ModelConfiguration noAuth = settings.parse(provider(""));
        ModelProvider provider = noAuth.providers().get("example");
        assertEquals("", provider.envKey());
        assertEquals("", settings.apiKey(provider));
        assertEquals("responses", provider.wireApi());
        assertEquals("OPENAI_API_KEY", settings.parse(provider("requires_openai_auth = true\n"))
                .providers().get("example").envKey());
        assertEquals("EXAMPLE_TOKEN", settings.parse(provider("requires_openai_auth = true\nenv_key = \"EXAMPLE_TOKEN\"\n"))
                .providers().get("example").envKey());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://gateway.example/v1", "https://user:pass@gateway.example/v1",
            "https://gateway.example/v1?token=x", "https://gateway.example/v1#token",
            "https://gateway.example:invalid/v1", "https://gateway.example:70000/v1",
            "https://gateway.example:0/v1", "http://[invalid/v1", "https://gate way.example/v1",
            "https://gateway.example/\nv1", "https://gateway.example\\other"
    })
    void rejectsCredentialBearingOrMalformedBaseUrls(String url) {
        assertCode("invalid_config", () -> ModelSettingsService.validateBaseUrl(url));
    }

    @Test
    void permitsPublicHttpsAndLocalGatewayHttpAndAppendsTheApiPrefix() {
        assertEquals("https://gateway.example/v1", ModelSettingsService.validateBaseUrl("https://gateway.example/v1/"));
        assertEquals("http://127.0.0.1:3000/v1", ModelSettingsService.validateBaseUrl("http://127.0.0.1:3000/v1"));
        assertEquals("https://gateway.example/v1/models", settings.parse(provider(""))
                .providers().get("example").endpoint("/models"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%2e%2e/models", "//outside.example/models", "/models\n", "/models?q=1",
            "/models#fragment", "/models%3Fkey", "/models%2F..", "/models%5Cnext", "/model%20list", "/%zz",
            "/%2foutside.example/models", "/%2F/models", "/catalog//models", "/catalog/%2fmodels",
            "/./models", "/catalog/.", "/%2e/models", "/models\\next",
            "/models%00", "/models%1f", "/models%7F", "/models%C2%85", "/models%C2%9F",
            "/models\u0085", "/%252e%252e/models", "/%252F%252Foutside.example/models",
            "/models%255Cnext", "/models%253Fkey", "/models%2523fragment", "/models%250A",
            "/models%25252F.."})
    void rejectsUnsafeOrMalformedModelPaths(String path) {
        assertCode("invalid_config", () -> ModelSettingsService.validateModelsPath(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/models", "/gateway/models/", "/custom%2Dmodels", "/models+list",
            "/models%2Blist", "/%E6%A8%A1%E5%9E%8B"})
    void modelPathsKeepAllowedCharactersAndEscaping(String path) {
        assertEquals(path, ModelSettingsService.validateModelsPath(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wire_api = []", "env_key = []", "requires_openai_auth = \"false\"",
            "timeout_seconds = true", "timeout_seconds = 2.0", "timeout_seconds = 1", "timeout_seconds = 121",
            "env_key = \"invalid-lowercase\"", "wire_api = \"unsupported\""})
    void providerFieldTypeErrorsAreUserErrors(String extra) {
        assertCode("invalid_config", () -> settings.parse(provider(extra)));
    }

    @Test
    void invalidTomlAndOversizeOrInvalidUnicodeHaveStructuredErrors() {
        assertCode("invalid_toml", () -> settings.parse("model = \"unterminated"));
        assertCode("invalid_config", () -> settings.parse("model = '\ud800'"));
        ApiException oversized = assertCode("invalid_config", () -> settings.parse("#" + "x".repeat(100_000)));
        assertEquals(413, oversized.getStatus());
    }

    @Test
    void invalidSaveDoesNotReplaceTheWorkingConfiguration() {
        settings.save(provider(""));
        ModelConfiguration before = settings.load();
        assertCode("invalid_toml", () -> settings.save("model = \"broken"));
        assertEquals(before, settings.load());
    }

    @Test
    void codexImportReturnsOnlyASafeSelectedProviderPreview() throws Exception {
        Path codex = temporary.resolve("codex");
        Files.createDirectories(codex);
        Files.writeString(codex.resolve("config.toml"), provider("env_key = \"EXAMPLE_TOKEN\"\n"
                + "api_key = \"synthetic-do-not-export\"\nmodels_path = \"/private/models\"\ntimeout_seconds = 90\n")
                + "\n[model_providers.unselected]\napi_key = \"other-secret\"\n"
                + "[projects.private]\ntrust_level = \"trusted\"\n");
        // This file must never be read during config import.
        Files.writeString(codex.resolve("auth.json"), "not-json-secret-fixture");
        String preview = settings.importCodexConfig();
        ModelConfiguration imported = settings.parse(preview);
        assertEquals(1, imported.providers().size());
        assertEquals("/models", imported.providers().get("example").modelsPath());
        assertEquals(15, imported.providers().get("example").timeoutSeconds());
        assertFalse(preview.contains("secret"));
        assertFalse(preview.contains("projects"));
        assertFalse(preview.contains("unselected"));
        assertEquals(ModelConfiguration.empty(), settings.load(), "Import is a preview, not a save");
    }

    @Test
    void codexHomeOverrideAndMissingOrMalformedFilesHaveStructuredErrors() throws Exception {
        assertCode("codex_config_not_found", settings::importCodexConfig);
        Path override = temporary.resolve("custom-codex");
        Files.createDirectories(override);
        Files.writeString(override.resolve("config.toml"), provider(""));
        DevPrepProperties properties = new DevPrepProperties();
        properties.setConfigFile(temporary.resolve("settings.toml").toString());
        ModelSettingsService custom = new ModelSettingsService(properties,
                Map.of("CODEX_HOME", override.toString())::get, temporary.resolve("unused"));
        assertEquals("example", custom.parse(custom.importCodexConfig()).modelProvider());
        Files.writeString(override.resolve("config.toml"), "invalid = \"");
        assertCode("codex_config_invalid", custom::importCodexConfig);
    }

    private static String provider(String extra) {
        return "model = \"example-model\"\nmodel_provider = \"example\"\n"
                + "[model_providers.example]\nbase_url = \"https://gateway.example/v1\"\n" + extra;
    }

    private static ApiException assertCode(String code, org.junit.jupiter.api.function.Executable operation) {
        ApiException exception = assertThrows(ApiException.class, operation);
        assertEquals(code, exception.getCode());
        return exception;
    }
}
