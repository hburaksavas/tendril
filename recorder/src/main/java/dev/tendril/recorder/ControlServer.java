package dev.tendril.recorder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lets the IDE act inside the application: it republishes a recorded message through the application's own
 * RabbitTemplate / KafkaTemplate, sends new messages written in the IDE, and sets the mocked responses
 * ({@link Mocks}). It listens on the loopback interface only and every call needs the shared token.
 */
final class ControlServer {

    private final HttpServer server;
    private final ApplicationContext context;
    private final ScheduledExecutorService hello = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tendril-recorder-hello");
        t.setDaemon(true);
        return t;
    });

    ControlServer(ApplicationContext context) throws IOException {
        this.context = context;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/replay", this::replay);
        this.server.createContext("/mocks", this::mocks);
        this.server.createContext("/send", this::send);
        this.server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tendril-recorder-control");
            t.setDaemon(true);
            return t;
        }));
        this.server.start();
        int port = server.getAddress().getPort();
        // the IDE may start after the application: keep announcing where to reach us
        hello.scheduleWithFixedDelay(() -> Sender.send(Json.object().str("kind", "hello")
                .str("project", Config.project()).num("control", port).str("mocks", Mocks.version()).build()),
                0, 15, TimeUnit.SECONDS);
    }

    void stop() {
        hello.shutdownNow();
        server.stop(0);
    }

    private static boolean authorized(HttpExchange exchange) throws IOException {
        String sent = exchange.getRequestHeaders().getFirst("X-Tendril-Token");
        if (sent == null || !MessageDigest.isEqual(sent.getBytes(StandardCharsets.UTF_8),
                Config.token().getBytes(StandardCharsets.UTF_8))) {
            respond(exchange, 403, "forbidden");
            return false;
        }
        return true;
    }

    /** Replaces every mocked response at once; an empty set turns mocking off. */
    private void mocks(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                return;
            }
            Map<String, String> form = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Mocks.replace(Mocks.parse(form), form.get("version"));
            respond(exchange, 200, "ok");
        } catch (Throwable t) {
            respond(exchange, 400, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Sends a new message written in the IDE to a topic or queue; answers with where it went. */
    private void send(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                return;
            }
            Map<String, String> form = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String transport = form.getOrDefault("transport", "");
            String result = switch (transport) {
                case "rabbit" -> RabbitSend.send(context, form);
                case "kafka" -> KafkaSend.send(context, form);
                default -> throw new IllegalArgumentException("unknown transport '" + transport + "'");
            };
            respond(exchange, 200, result);
        } catch (Throwable t) {
            Throwable cause = t instanceof java.util.concurrent.ExecutionException && t.getCause() != null ? t.getCause() : t;
            respond(exchange, 500, cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private void replay(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                return;
            }
            Map<String, String> form = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String transport = form.getOrDefault("transport", "");
            switch (transport) {
                case "rabbit" -> RabbitReplay.send(context, form);
                case "kafka" -> KafkaReplay.send(context, form);
                default -> throw new IllegalArgumentException("unknown transport '" + transport + "'");
            }
            respond(exchange, 200, "ok");
        } catch (Throwable t) {
            respond(exchange, 500, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** application/x-www-form-urlencoded into a map. */
    static Map<String, String> parse(String form) {
        Map<String, String> out = new HashMap<>();
        if (form.isEmpty()) {
            return out;
        }
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    static byte[] body(Map<String, String> form) {
        String body = form.getOrDefault("body", "");
        return "base64".equals(form.get("bodyEncoding")) ? Base64.getDecoder().decode(body)
                : body.getBytes(StandardCharsets.UTF_8);
    }
}
