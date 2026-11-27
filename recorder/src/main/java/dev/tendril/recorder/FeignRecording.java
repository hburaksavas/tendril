package dev.tendril.recorder;

import feign.Capability;
import feign.Client;
import feign.Request;
import feign.Response;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records the calls of every Feign client, and answers those the IDE mocked ({@link Mocks}) without making them.
 * Feign hands the final client (after load balancing) to
 * {@link Capability#enrich(Client)}, so wrapping it there does not disturb Spring Cloud's own client handling.
 */
public final class FeignRecording implements Capability {

    /** Larger responses are passed through untouched instead of being buffered in memory. */
    private static final long BUFFER_LIMIT = 5L * 1024 * 1024;

    @Override
    public Client enrich(Client client) {
        return new RecordingClient(client);
    }

    static final class RecordingClient implements Client {
        private final Client delegate;

        RecordingClient(Client delegate) {
            this.delegate = delegate;
        }

        @Override
        public Response execute(Request request, Request.Options options) throws IOException {
            long startedAt = System.currentTimeMillis();
            long started = System.nanoTime();
            long flow = Flow.current();
            Response result = null;
            byte[] body = null;
            boolean buffered = false;
            String error = null;
            Mocks.Rule mock = null;
            try {
                mock = mockFor(request);
                Response response = mock != null ? answer(mock, request) : delegate.execute(request, options);
                result = response;
                if (response.body() != null && length(response) <= BUFFER_LIMIT) {
                    try (Response original = response) {
                        body = original.body().asInputStream().readAllBytes();
                    }
                    result = response.toBuilder().body(body).build();
                    buffered = true;
                }
                return result;
            } catch (IOException | RuntimeException e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                throw e;
            } finally {
                try {
                    record(request, result, buffered ? body : null, error, flow, startedAt,
                            (System.nanoTime() - started) / 1_000_000, mock);
                } catch (RuntimeException ignored) {
                    // recording must never affect the application
                }
            }
        }

        private static Mocks.Rule mockFor(Request request) {
            try {
                return Mocks.feign(configKey(request), request.httpMethod().name(), request.url(), request.headers(), request.body());
            } catch (RuntimeException e) {
                return null; // a broken rule must not break the call
            }
        }

        private static String configKey(Request request) {
            try {
                return request.requestTemplate() != null && request.requestTemplate().methodMetadata() != null
                        ? request.requestTemplate().methodMetadata().configKey() : null;
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** The mocked response, after its delay; a fault is thrown as the real client would. */
        static Response answer(Mocks.Rule mock, Request request) throws IOException {
            Mocks.delay(mock);
            if ("timeout".equals(mock.fault)) {
                throw new SocketTimeoutException("Read timed out (mocked by Tendril)");
            }
            if ("refused".equals(mock.fault)) {
                throw new ConnectException("Connection refused (mocked by Tendril)");
            }
            Map<String, Collection<String>> headers = new LinkedHashMap<>();
            for (String[] h : mock.responseHeaders) {
                headers.computeIfAbsent(h[0], k -> new ArrayList<>()).add(h[1]);
            }
            return Response.builder()
                    .status(mock.status)
                    .reason(Mocks.reason(mock.status))
                    .request(request)
                    .headers(headers)
                    .body(mock.body)
                    .build();
        }

        private static long length(Response response) {
            Integer length = response.body().length();
            return length == null ? 0 : length;
        }

        private void record(Request request, Response response, byte[] body, String error, long flow, long startedAt,
                            long millis, Mocks.Rule mock) {
            Json event = Json.object()
                    .str("kind", "http_out")
                    .str("project", Config.project())
                    .num("time", startedAt)
                    .num("durationMs", millis)
                    .num("flow", flow)
                    .str("method", request.httpMethod().name())
                    .str("url", request.url())
                    .headers("headers", pairs(request.headers()))
                    .body("body", request.body(), first(request.headers(), "Content-Type"));
            try {
                if (request.requestTemplate() != null && request.requestTemplate().methodMetadata() != null) {
                    event.str("client", request.requestTemplate().methodMetadata().configKey());
                }
            } catch (RuntimeException ignored) {
                // the config key is only a label
            }
            if (response != null) {
                event.num("status", response.status())
                        .headers("responseHeaders", pairs(response.headers()))
                        .body("responseBody", body, first(response.headers(), "Content-Type"));
            }
            if (error != null) {
                event.str("error", error);
            }
            if (mock != null) {
                event.str("mock", mock.label.isEmpty() ? mock.id : mock.label);
            }
            Sender.send(event.build());
        }

        private static List<String[]> pairs(Map<String, Collection<String>> headers) {
            List<String[]> out = new ArrayList<>();
            if (headers != null) {
                headers.forEach((name, values) -> values.forEach(value -> out.add(new String[]{name, value})));
            }
            return out;
        }

        private static String first(Map<String, Collection<String>> headers, String name) {
            if (headers == null) {
                return null;
            }
            for (Map.Entry<String, Collection<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().iterator().next();
                }
            }
            return null;
        }
    }
}
