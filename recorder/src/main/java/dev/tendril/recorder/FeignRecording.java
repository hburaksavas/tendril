package dev.tendril.recorder;

import feign.Capability;
import feign.Client;
import feign.Request;
import feign.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Records the calls of every Feign client. Feign hands the final client (after load balancing) to
 * {@link Capability#enrich(Client)}, so wrapping it there does not disturb Spring Cloud's own client handling.
 */
public final class FeignRecording implements Capability {

    /** Larger responses are passed through untouched instead of being buffered in memory. */
    private static final long BUFFER_LIMIT = 5L * 1024 * 1024;

    @Override
    public Client enrich(Client client) {
        return new RecordingClient(client);
    }

    private static final class RecordingClient implements Client {
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
            try {
                Response response = delegate.execute(request, options);
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
                            (System.nanoTime() - started) / 1_000_000);
                } catch (RuntimeException ignored) {
                    // recording must never affect the application
                }
            }
        }

        private static long length(Response response) {
            Integer length = response.body().length();
            return length == null ? 0 : length;
        }

        private void record(Request request, Response response, byte[] body, String error, long flow, long startedAt,
                            long millis) {
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
