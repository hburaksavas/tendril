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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SqlHttpRecordingTest {
    @Test
    void repeatingAnHttpRequestProducesIndependentSqlSummaries() throws Exception {
        var events = new LinkedBlockingQueue<byte[]>();
        ObjectMapper json = new ObjectMapper();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/events", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (json.readTree(body).path("kind").asText().equals("http_in")) events.add(body);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        System.setProperty("tendril.recorder.port", Integer.toString(receiver.getAddress().getPort()));
        try (var context = (ServletWebServerApplicationContext) SpringApplication.run(TestApplication.class,
                "--server.port=0", "--server.address=127.0.0.1", "--tendril.recorder.sql=true")) {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + context.getWebServer().getPort() + "/sql-test")).build();
            for (int attempt = 0; attempt < 2; attempt++) {
                assertEquals(200, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
                byte[] event = events.poll(5, TimeUnit.SECONDS);
                assertNotNull(event, "Recorder should publish the request");
                JsonNode sql = json.readTree(event).path("sql");
                assertEquals(1, sql.size());
                assertEquals("select cast(? as integer)", sql.get(0).path("template").asText());
                assertEquals(2, sql.get(0).path("count").asInt());
                assertTrue(sql.get(0).path("durationMicros").asLong() > 0);
            }
        } finally {
            System.clearProperty("tendril.recorder.port");
            receiver.stop(0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(SqlController.class)
    static class TestApplication {}

    @RestController
    static class SqlController {
        private final JdbcTemplate jdbc;

        SqlController(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping("/sql-test")
        int query() {
            return jdbc.queryForObject("select cast(? as integer)", Integer.class, 10)
                    + jdbc.queryForObject("select cast(? as integer)", Integer.class, 20);
        }
    }
}
