package dev.tendril.recorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** An incoming request that matches a mock is answered by the recorder, not by the controller. */
class MockedHttpTest {

    @Test
    void aMockedRequestNeverReachesTheController() throws Exception {
        var events = new LinkedBlockingQueue<JsonNode>();
        ObjectMapper json = new ObjectMapper();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/events", exchange -> {
            JsonNode event = json.readTree(exchange.getRequestBody().readAllBytes());
            if (event.path("kind").asText().equals("http_in")) events.add(event);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        System.setProperty("tendril.recorder.port", Integer.toString(receiver.getAddress().getPort()));
        try (var context = (ServletWebServerApplicationContext) SpringApplication.run(TestApplication.class,
                "--server.port=0", "--server.address=127.0.0.1", "--server.servlet.context-path=/ctx")) {
            Mocks.replace(Mocks.parse(Map.of(
                    "count", "1", "0.kind", "http", "0.label", "OrderController.get", "0.method", "GET",
                    "0.path", "/ctx/orders/{id}", "0.q.mock", "yes", "0.status", "418",
                    "0.rh.0", "Content-Type: application/json", "0.rh.1", "X-Mocked: 1", "0.body", "{\"id\":\"mocked\"}")), "v1");
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + context.getWebServer().getPort() + "/ctx/orders/7";

            HttpResponse<String> mocked = client.send(HttpRequest.newBuilder(URI.create(base + "?mock=yes")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(418, mocked.statusCode());
            assertEquals("{\"id\":\"mocked\"}", mocked.body());
            assertEquals("1", mocked.headers().firstValue("X-Mocked").orElse(null));
            assertTrue(mocked.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
            JsonNode event = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals("OrderController.get", event.path("mock").asText());
            assertEquals(418, event.path("status").asInt());

            HttpResponse<String> real = client.send(HttpRequest.newBuilder(URI.create(base)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, real.statusCode());
            assertEquals("real 7", real.body());
            JsonNode realEvent = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(realEvent);
            assertTrue(realEvent.path("mock").isMissingNode());
        } finally {
            Mocks.replace(List.of(), "");
            System.clearProperty("tendril.recorder.port");
            receiver.stop(0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(OrderController.class)
    static class TestApplication {}

    @RestController
    static class OrderController {
        @GetMapping("/orders/{id}")
        String get(@PathVariable("id") String id) {
            return "real " + id;
        }
    }
}
