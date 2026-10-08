package com.devprep.ai.model;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.config.DevPrepProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HttpModelGatewayTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<StubResponse> response = new AtomicReference<>();
    private final List<ObservedRequest> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService executor;
    private ModelSettingsService settings;
    private HttpModelGateway gateway;
    private MutableClock clock;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "provider-fixture");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::respond);
        server.start();
        DevPrepProperties properties = new DevPrepProperties();
        properties.setConfigFile(temporary.resolve("settings.toml").toString());
        properties.setModelCacheSeconds(300);
        settings = new ModelSettingsService(properties,
                Map.of("SYNTHETIC_TEST_TOKEN", "synthetic-selected-token")::get,
                temporary.resolve("codex"));
        clock = new MutableClock();
        gateway = new HttpModelGateway(settings, properties, mapper, clock);
        json("{\"data\":[{\"id\":\"first\"}]}");
        configure("responses", "");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"},{\"id\":\"a-model\"}]}",
            "[\"z-model\",\"a-model\",\"a-model\"]",
            "[{\"id\":\"z-model\"},{\"id\":\"a-model\"}]",
            "{\"models\":[\"z-model\",\"a-model\"]}"
    })
    void discoversSupportedCatalogShapesAndSortsUniqueIdentifiers(String payload) {
        json(payload);
        assertEquals(List.of("a-model", "z-model"), gateway.discover("example", true));
        assertEquals("GET", requests.get(0).method());
        assertEquals("/v1/models", requests.get(0).path());
        assertNull(requests.get(0).authorization());
    }

    @Test
    void discoversAtMostFiveHundredValidModelIdentifiers() throws Exception {
        List<String> identifiers = new ArrayList<>();
        for (int i = 0; i < 510; i++) identifiers.add(String.format("model-%03d", i));
        identifiers.add(" ");
        identifiers.add("x".repeat(161));
        json(mapper.writeValueAsString(identifiers));
        List<String> models = gateway.discover("example", true);
        assertEquals(500, models.size());
        assertEquals("model-000", models.get(0));
        assertEquals("model-499", models.get(499));
    }

    @Test
    void cacheExpiresRefreshesAndIsInvalidatedAfterSavingSettings() {
        assertEquals(List.of("first"), gateway.discover("example", false));
        json("[\"second\"]");
        assertEquals(List.of("first"), gateway.discover("example", false));
        assertEquals(1, requests.size());
        assertEquals(List.of("second"), gateway.discover("example", true));
        json("[\"third\"]");
        clock.advance(Duration.ofSeconds(301));
        assertEquals(List.of("third"), gateway.discover("example", false));
        json("[\"fourth\"]");
        configure("responses", "");
        assertEquals(List.of("fourth"), gateway.discover("example", false));
        assertEquals(4, requests.size());
    }

    @Test
    void configuredCredentialsAreReadOnlyFromTheNamedEnvironmentVariable() {
        configure("responses", "SYNTHETIC_TEST_TOKEN");
        gateway.discover("example", true);
        assertEquals("Bearer synthetic-selected-token", requests.get(0).authorization());
        configure("responses", "MISSING_TOKEN");
        assertCode("missing_api_key", () -> gateway.discover("example", true));
        assertEquals(1, requests.size(), "A missing credential must not start a network request");
    }

    @Test
    void providerRedirectNeverForwardsTheCredentialToItsTarget() {
        configure("responses", "SYNTHETIC_TEST_TOKEN");
        response.set(new StubResponse(302, new byte[0], baseUrl() + "/credential-leak", 0));
        assertCode("provider_redirect", () -> gateway.discover("example", true));
        assertEquals(1, requests.size());
        assertEquals("/v1/models", requests.get(0).path());
    }

    @ParameterizedTest
    @MethodSource("catalogFailures")
    void invalidCatalogsAndHttpFailuresAreStructured(int status, String payload, String code, int apiStatus) {
        response.set(new StubResponse(status, payload.getBytes(StandardCharsets.UTF_8), null, 0));
        ApiException failure = assertCode(code, () -> gateway.discover("example", true));
        assertEquals(apiStatus, failure.getStatus());
        assertFalse(failure.getMessage().contains("private-diagnostic"));
    }

    static Stream<Arguments> catalogFailures() {
        return Stream.of(
                Arguments.of(200, "[]", "empty_catalog", 502),
                Arguments.of(200, "{\"data\":null}", "invalid_catalog", 502),
                Arguments.of(200, "not-json", "invalid_catalog", 502),
                Arguments.of(401, "private-diagnostic", "provider_auth", 401),
                Arguments.of(403, "private-diagnostic", "provider_auth", 401),
                Arguments.of(404, "private-diagnostic", "models_endpoint_not_found", 502),
                Arguments.of(429, "private-diagnostic", "provider_rate_limit", 429),
                Arguments.of(500, "private-diagnostic", "provider_unavailable", 502));
    }

    @Test
    void modelCatalogAndGenerationBodiesAreBoundedWhileBeingRead() {
        response.set(new StubResponse(200, new byte[2_000_001], null, 0));
        assertCode("catalog_too_large", () -> gateway.discover("example", true));
        assertCode("response_too_large", () -> generate());
    }

    @ParameterizedTest
    @ValueSource(strings = {"responses", "chat_completions"})
    void sendsTheExpectedWireProtocolEndpointBodyAndAuthorization(String wire) {
        configure(wire, "SYNTHETIC_TEST_TOKEN");
        json(wire.equals("responses") ? "{\"output_text\":\" Answer [1] \"}"
                : "{\"choices\":[{\"message\":{\"content\":\" Answer [1] \"}}]}");
        assertEquals("Answer [1]", generate());
        ObservedRequest request = requests.get(0);
        assertEquals("POST", request.method());
        assertEquals("/v1" + (wire.equals("responses") ? "/responses" : "/chat/completions"), request.path());
        assertEquals("Bearer synthetic-selected-token", request.authorization());
        assertEquals("test-model", request.body().path("model").asText());
        if (wire.equals("responses")) {
            assertFalse(request.body().path("store").asBoolean(true));
            assertTrue(request.body().path("input").asText().contains("[1] Notes / Concept"));
            assertTrue(request.body().path("instructions").asText().contains("不得虚构来源"));
        } else {
            assertFalse(request.body().path("stream").asBoolean(true));
            assertEquals("system", request.body().path("messages").get(0).path("role").asText());
            assertEquals("user", request.body().path("messages").get(1).path("role").asText());
        }
    }

    @Test
    void handlesNestedResponsesAndChatTextParts() {
        json("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Nested [1]\"}]}]}");
        assertEquals("Nested [1]", generate());
        configure("chat_completions", "");
        json("{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Part [1]\"}]}}]}");
        assertEquals("Part [1]", generate());
    }

    @Test
    void flatResponsesTextCanAccompanyCompletedStructuredOutputs() {
        json("{\"status\":\"completed\",\"output_text\":\"Flat [1]\",\"output\":["
                + "{\"type\":\"reasoning\",\"status\":\"completed\",\"summary\":[]},"
                + "{\"type\":\"message\",\"status\":\"completed\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"Nested [1]\"}]}]}");
        assertEquals("Flat [1]", generate());
    }

    @ParameterizedTest
    @MethodSource("generationFailures")
    void malformedRefusedFailedOrIncompleteGenerationHasStructuredErrors(String wire, String payload,
                                                                      String code, int apiStatus) {
        configure(wire, "");
        json(payload);
        ApiException failure = assertCode(code, this::generate);
        assertEquals(apiStatus, failure.getStatus());
        assertFalse(failure.getMessage().contains("private-diagnostic"));
    }

    static Stream<Arguments> generationFailures() {
        return Stream.of(
                Arguments.of("responses", "null", "invalid_response", 502),
                Arguments.of("responses", "[]", "invalid_response", 502),
                Arguments.of("responses", "{\"output\":null}", "invalid_response", 502),
                Arguments.of("responses", "{\"output\":[{\"content\":null}]}", "invalid_response", 502),
                Arguments.of("responses", "{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":{}}]}]}", "invalid_response", 502),
                Arguments.of("chat_completions", "{\"choices\":null}", "invalid_response", 502),
                Arguments.of("chat_completions", "{\"choices\":[null]}", "invalid_response", 502),
                Arguments.of("chat_completions", "{\"choices\":[{\"message\":null}]}", "invalid_response", 502),
                Arguments.of("chat_completions", "{\"choices\":[{\"message\":{\"content\":[{\"text\":{}}]}}]}", "invalid_response", 502),
                Arguments.of("responses", "{\"status\":\"failed\",\"error\":{\"message\":\"private-diagnostic\"}}", "provider_generation_failed", 502),
                Arguments.of("responses", "{\"status\":\"cancelled\"}", "provider_generation_failed", 502),
                Arguments.of("responses", "{\"status\":\"incomplete\",\"output_text\":\"Partial\"}", "incomplete_response", 502),
                Arguments.of("responses", "{\"status\":\"in_progress\",\"output_text\":\"Partial\"}", "incomplete_response", 502),
                Arguments.of("responses", "{\"status\":\"queued\",\"output_text\":\"Partial\"}", "incomplete_response", 502),
                Arguments.of("responses", "{\"output\":[{\"content\":[{\"type\":\"refusal\"}]}]}", "provider_refusal", 422),
                Arguments.of("responses", "{\"output_text\":\"Flat [1]\",\"output\":[{\"content\":[{\"type\":\"refusal\",\"refusal\":\"private-diagnostic\"}]}]}", "provider_refusal", 422),
                Arguments.of("responses", "{\"output_text\":\"Flat [1]\",\"output\":[{\"type\":\"refusal\"}]}", "provider_refusal", 422),
                Arguments.of("responses", "{\"output_text\":\"Flat [1]\",\"output\":[{\"status\":\"incomplete\",\"content\":[{\"type\":\"output_text\",\"text\":\"Partial\"}]}]}", "incomplete_response", 502),
                Arguments.of("responses", "{\"output_text\":\"Flat [1]\",\"output\":[{\"status\":\"in_progress\"}]}", "incomplete_response", 502),
                Arguments.of("responses", "{\"output_text\":\"Flat [1]\",\"output\":[{\"status\":\"failed\"}]}", "provider_generation_failed", 502),
                Arguments.of("responses", "{\"output_text\":\"Flat [1]\",\"output\":null}", "invalid_response", 502),
                Arguments.of("responses", "{\"output_text\":\"Flat [1]\",\"output\":[{\"content\":null}]}", "invalid_response", 502),
                Arguments.of("responses", "{\"output_text\":\"Flat [1]\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":{}}]}]}", "invalid_response", 502),
                Arguments.of("chat_completions", "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"Partial\"}}]}", "incomplete_response", 502),
                Arguments.of("chat_completions", "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":\"Filtered\"}}]}", "provider_refusal", 422),
                Arguments.of("chat_completions", "{\"choices\":[{\"message\":{\"refusal\":\"refused\"}}]}", "provider_refusal", 422),
                Arguments.of("chat_completions", "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Part [1]\"},{\"type\":\"refusal\",\"refusal\":\"private-diagnostic\"}]}}]}", "provider_refusal", 422),
                Arguments.of("chat_completions", "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"refusal\",\"text\":\"private-diagnostic\"}]}}]}", "provider_refusal", 422),
                Arguments.of("responses", "{}", "empty_response", 502),
                Arguments.of("responses", "not-json", "invalid_response", 502),
                Arguments.of("responses", "{\"output_text\":\"Invalid\\ud800\"}", "invalid_response", 502));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500})
    void generationHttpErrorsAreSanitized(int status) {
        response.set(new StubResponse(status, "private-diagnostic".getBytes(StandardCharsets.UTF_8), null, 0));
        String code = status == 401 || status == 403 ? "provider_auth"
                : status == 429 ? "provider_rate_limit" : "provider_unavailable";
        assertCode(code, this::generate);
    }

    @Test
    void generationWithoutSourcesDoesNotRequestTheProvider() {
        assertCode("no_sources", () -> gateway.generate(settings.load(), "Explain", List.of()));
        assertTrue(requests.isEmpty());
    }

    @Test
    void boundsTheReferenceContextBeforeSendingItToTheGateway() {
        List<Map<String, Object>> passages = new ArrayList<>();
        for (int i = 0; i < 8; i++) passages.add(Map.of("title", "Note " + i, "content", "x".repeat(6000)));
        json("{\"output_text\":\"Answer [1]\"}");
        gateway.generate(settings.load(), "Explain", passages);
        String input = requests.get(0).body().path("input").asText();
        assertTrue(input.contains("[6] Note 5"));
        assertFalse(input.contains("[7]"));
        assertFalse(input.contains("x".repeat(5001)));
    }

    @Test
    void deadlineCoversAGatewayThatStallsAfterResponseHeaders() {
        configure("responses", "", 2);
        response.set(new StubResponse(200, "{\"output_text\":\"Late [1]\"}".getBytes(StandardCharsets.UTF_8), null, 5000));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertCode("provider_unavailable", this::generate));
    }

    @Test
    void malformedUtf8UpstreamIsRejected() {
        response.set(new StubResponse(200, new byte[]{(byte) 0xC3, (byte) 0x28}, null, 0));
        assertCode("invalid_catalog", () -> gateway.discover("example", true));
        assertCode("invalid_response", this::generate);
    }

    private String generate() {
        return gateway.generate(settings.load(), "Explain a concept", List.of(Map.of(
                "title", "Notes", "heading", "Concept", "content", "A concept has a precise definition.")));
    }

    private void configure(String wire, String envKey) { configure(wire, envKey, 15); }

    private void configure(String wire, String envKey, int timeout) {
        settings.save("model = \"test-model\"\nmodel_provider = \"example\"\n"
                + "[model_providers.example]\nbase_url = \"" + baseUrl() + "/v1\"\n"
                + "wire_api = \"" + wire + "\"\nenv_key = \"" + envKey + "\"\n"
                + "timeout_seconds = " + timeout + "\n");
    }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void json(String value) {
        response.set(new StubResponse(200, value.getBytes(StandardCharsets.UTF_8), null, 0));
    }

    private void respond(HttpExchange exchange) throws IOException {
        try {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.add(new ObservedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body.length == 0 ? null : mapper.readTree(body)));
            StubResponse stub = response.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (stub.location() != null) exchange.getResponseHeaders().set("Location", stub.location());
            exchange.sendResponseHeaders(stub.status(), stub.body().length == 0 ? -1 : stub.body().length);
            if (stub.delayMillis() > 0) {
                try { Thread.sleep(stub.delayMillis()); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
            }
            if (stub.body().length > 0) exchange.getResponseBody().write(stub.body());
        } finally {
            exchange.close();
        }
    }

    private static ApiException assertCode(String code, org.junit.jupiter.api.function.Executable operation) {
        ApiException failure = assertThrows(ApiException.class, operation);
        assertEquals(code, failure.getCode());
        return failure;
    }

    private record StubResponse(int status, byte[] body, String location, long delayMillis) { }
    private record ObservedRequest(String method, String path, String authorization, JsonNode body) { }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
