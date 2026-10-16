package com.devprep.ai.config;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Set;

import jakarta.servlet.ReadListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(0)
public class WebConfig extends OncePerRequestFilter {
    private static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]", "::1");
    private static final long MAX_JSON_BYTES = 25_000_000;
    private final long maxJsonBytes;

    public WebConfig(DevPrepProperties properties) {
        long materialLimit = Math.max(1_000, properties.getMaxBodyBytes());
        maxJsonBytes = materialLimit > (MAX_JSON_BYTES - 8192) / 6
                ? MAX_JSON_BYTES
                : materialLimit * 6 + 8192;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        String host = request.getServerName();
        String origin = request.getHeader("Origin");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (!LOCAL_HOSTS.contains(host) || (origin != null && !isLocalOrigin(origin))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":{\"code\":\"invalid_origin\",\"message\":\"本地复习服务只接受本机浏览器请求。\"}}");
            return;
        }
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            if (!isJson(request.getContentType())) {
                response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"error\":{\"code\":\"invalid_content_type\",\"message\":\"请求内容需要使用 application/json。\"}}");
                return;
            }
            if (request.getContentLengthLong() > maxJsonBytes) {
                sendTooLarge(response);
                return;
            }
            byte[] body = request.getInputStream().readNBytes(Math.toIntExact(maxJsonBytes + 1));
            if (body.length > maxJsonBytes) {
                sendTooLarge(response);
                return;
            }
            request = new BufferedJsonRequest(request, body);
        }
        chain.doFilter(request, response);
    }

    private static boolean isJson(String value) {
        if (value == null) return false;
        try {
            MediaType type = MediaType.parseMediaType(value);
            return "application".equalsIgnoreCase(type.getType())
                    && ("json".equalsIgnoreCase(type.getSubtype()) || type.getSubtype().endsWith("+json"));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static void sendTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":{\"code\":\"request_too_large\",\"message\":\"请求内容过大。\"}}");
    }

    private static final class BufferedJsonRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BufferedJsonRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous requests only"); }
                @Override public int read() throws IOException { return input.read(); }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException { return input.read(buffer, offset, length); }
            };
        }
    }

    private boolean isLocalOrigin(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && LOCAL_HOSTS.contains(uri.getHost())
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getPort() != 0 && uri.getPort() <= 65535
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
