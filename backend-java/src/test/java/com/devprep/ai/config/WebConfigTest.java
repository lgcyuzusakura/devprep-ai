package com.devprep.ai.config;

import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WebConfigTest {
    @Test
    void blocksNonLocalHostsAndExternalOriginsWithNoStoreHeaders() throws Exception {
        WebConfig filter = new WebConfig(new DevPrepProperties());
        MockHttpServletRequest remote = request("GET", "/api/settings");
        remote.setServerName("192.0.2.10");
        MockHttpServletResponse remoteResponse = new MockHttpServletResponse();
        AtomicReference<Boolean> chained = new AtomicReference<>(false);

        filter.doFilter(remote, remoteResponse, (req, res) -> chained.set(true));

        assertEquals(403, remoteResponse.getStatus());
        assertTrue(remoteResponse.getContentAsString().contains("invalid_origin"));
        assertEquals("no-store", remoteResponse.getHeader("Cache-Control"));
        assertEquals("nosniff", remoteResponse.getHeader("X-Content-Type-Options"));
        assertFalse(chained.get());

        MockHttpServletRequest forgedOrigin = request("GET", "/api/settings");
        forgedOrigin.addHeader("Origin", "https://attacker.example");
        MockHttpServletResponse forgedResponse = new MockHttpServletResponse();
        filter.doFilter(forgedOrigin, forgedResponse, (req, res) -> fail("external Origin must not reach the API"));
        assertEquals(403, forgedResponse.getStatus());
    }

    @Test
    void rejectsPostWithoutJsonContentType() throws Exception {
        WebConfig filter = new WebConfig(new DevPrepProperties());
        MockHttpServletRequest request = request("POST", "/api/qa");
        request.setContentType("text/plain");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> fail("non-JSON POST must be rejected"));

        assertEquals(415, response.getStatus());
        assertTrue(response.getContentAsString().contains("invalid_content_type"));
    }

    @Test
    void boundsUnknownLengthBodiesAndAllowsStructuredJsonMediaTypes() throws Exception {
        DevPrepProperties properties = new DevPrepProperties();
        properties.setMaxBodyBytes(1000);
        WebConfig filter = new WebConfig(properties);
        MockHttpServletRequest oversized = new MockHttpServletRequest("POST", "/api/materials/import") {
            @Override public long getContentLengthLong() { return -1; }
        };
        oversized.setServerName("127.0.0.1");
        oversized.setContentType("application/json");
        oversized.setContent(new byte[14_193]);
        MockHttpServletResponse oversizedResponse = new MockHttpServletResponse();

        filter.doFilter(oversized, oversizedResponse, (req, res) -> fail("oversized body must be rejected"));

        assertEquals(413, oversizedResponse.getStatus());
        assertTrue(oversizedResponse.getContentAsString().contains("request_too_large"));

        MockHttpServletRequest valid = request("POST", "/api/materials/import");
        valid.setContentType("application/vnd.devprep+json; charset=UTF-8");
        valid.setContent("{\"filename\":\"notes.md\"}".getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> body = new AtomicReference<>();
        filter.doFilter(valid, new MockHttpServletResponse(), (wrapped, response) -> {
            ServletInputStream input = wrapped.getInputStream();
            try { body.set(new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (IOException ex) { throw new AssertionError(ex); }
        });
        assertEquals("{\"filename\":\"notes.md\"}", body.get());
    }

    @Test
    void capsJsonLimitWithoutOverflowWhenConfiguredBodyLimitIsHuge() throws Exception {
        DevPrepProperties properties = new DevPrepProperties();
        properties.setMaxBodyBytes(Long.MAX_VALUE);
        WebConfig filter = new WebConfig(properties);
        MockHttpServletRequest valid = request("POST", "/api/qa");
        valid.setContentType("application/json");
        valid.setContent("{\"question\":\"Java memory model\"}".getBytes(StandardCharsets.UTF_8));
        AtomicReference<Boolean> chained = new AtomicReference<>(false);

        filter.doFilter(valid, new MockHttpServletResponse(), (req, res) -> chained.set(true));

        assertTrue(chained.get());
    }

    @Test
    void doesNotApplyApiOriginOrBodyRulesToStaticRequests() throws Exception {
        WebConfig filter = new WebConfig(new DevPrepProperties());
        MockHttpServletRequest request = request("POST", "/assets/app.js");
        request.setServerName("192.0.2.10");
        request.setContentType("text/plain");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> chained = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> chained.set(true));

        assertTrue(chained.get());
        assertEquals(200, response.getStatus());
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setServerName("127.0.0.1");
        return request;
    }
}
