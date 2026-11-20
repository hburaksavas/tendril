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

/** Records every incoming HTTP request with its response. Actuator and event streams are left alone. */
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
        long startedAt = System.currentTimeMillis();
        long started = System.nanoTime();
        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            if (!request.isAsyncStarted()) {
                try {
                    record(request, wrappedRequest, wrappedResponse, flow, startedAt, (System.nanoTime() - started) / 1_000_000);
                } catch (RuntimeException ignored) {
                    // recording must never affect the application
                }
                wrappedResponse.copyBodyToResponse();
            }
            Flow.end();
        }
    }

    private void record(HttpServletRequest original, ContentCachingRequestWrapper request,
                        ContentCachingResponseWrapper response, long flow, long startedAt, long millis) {
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
        Sender.send(event.build());
    }
}
