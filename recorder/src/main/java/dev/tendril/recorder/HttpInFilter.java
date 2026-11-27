package dev.tendril.recorder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records every incoming HTTP request with its response, and answers those the IDE mocked ({@link Mocks}) without
 * calling the application. Actuator and event streams are left alone.
 */
final class HttpInFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return request.getRequestURI().contains("/actuator") || (accept != null && accept.contains("text/event-stream"));
    }

    /** Async requests complete in a second dispatch; wrapping that one too keeps the response intact. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, Config.MAX_BODY);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        long flow = Flow.begin();
        SqlRecording sql = SqlRecording.begin();
        long startedAt = System.currentTimeMillis();
        long started = System.nanoTime();
        Mocks.Rule mock = mockFor(request);
        try {
            if (mock != null) {
                serve(mock, wrappedResponse);
            } else {
                chain.doFilter(wrappedRequest, wrappedResponse);
            }
        } finally {
            try {
                if (!request.isAsyncStarted()) {
                    try {
                        record(request, wrappedRequest, wrappedResponse, flow, startedAt, (System.nanoTime() - started) / 1_000_000, sql, mock);
                    } catch (RuntimeException ignored) {
                        // recording must never affect the application
                    }
                    wrappedResponse.copyBodyToResponse();
                }
            } finally {
                SqlRecording.end();
                Flow.end();
            }
        }
    }

    private static Mocks.Rule mockFor(HttpServletRequest request) {
        try {
            return Mocks.http(request.getMethod(), request.getRequestURI(), request.getQueryString(), request::getHeader);
        } catch (RuntimeException e) {
            return null; // a broken rule must not break the request
        }
    }

    static void serve(Mocks.Rule mock, HttpServletResponse response) throws IOException {
        Mocks.delay(mock);
        response.setStatus(mock.status);
        for (String[] h : mock.responseHeaders) {
            if (h[0].equalsIgnoreCase("Content-Type")) {
                response.setContentType(h[1]);
            } else if (!h[0].equalsIgnoreCase("Content-Length") && !h[0].equalsIgnoreCase("Transfer-Encoding")) {
                response.addHeader(h[0], h[1]);
            }
        }
        if (mock.body.length > 0) {
            response.getOutputStream().write(mock.body);
        }
    }

    private void record(HttpServletRequest original, ContentCachingRequestWrapper request,
                        ContentCachingResponseWrapper response, long flow, long startedAt, long millis, SqlRecording sql,
                        Mocks.Rule mock) {
        StringBuffer url = original.getRequestURL();
        if (original.getQueryString() != null) {
            url.append('?').append(original.getQueryString());
        }
        List<String[]> requestHeaders = new ArrayList<>();
        for (String name : Collections.list(original.getHeaderNames())) {
            for (String value : Collections.list(original.getHeaders(name))) {
                requestHeaders.add(new String[]{name, value});
            }
        }
        List<String[]> responseHeaders = new ArrayList<>();
        for (String name : response.getHeaderNames()) {
            for (String value : response.getHeaders(name)) {
                responseHeaders.add(new String[]{name, value});
            }
        }

        if (response.getContentType() != null) {
            responseHeaders.add(new String[]{"Content-Type", response.getContentType()});
        }

        Json event = Json.object()
                .str("kind", "http_in")
                .str("project", Config.project())
                .num("time", startedAt)
                .num("durationMs", millis)
                .num("flow", flow)
                .str("method", original.getMethod())
                .str("url", url.toString())
                .headers("headers", requestHeaders)
                .body("body", request.getContentAsByteArray(), original.getContentType())
                .num("status", response.getStatus())
                .headers("responseHeaders", responseHeaders)
                .body("responseBody", response.getContentAsByteArray(), response.getContentType());

        Object handler = original.getAttribute("org.springframework.web.servlet.HandlerMapping.bestMatchingHandler");
        if (handler instanceof HandlerMethod method) {
            event.str("handler", method.getBeanType().getSimpleName() + "." + method.getMethod().getName());
        }
        Object pattern = original.getAttribute("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern");
        if (pattern != null) {
            event.str("pattern", pattern.toString());
        }
        sql.writeTo(event);
        if (mock != null) {
            event.str("mock", mock.label.isEmpty() ? mock.id : mock.label);
        }
        Sender.send(event.build());
    }
}
