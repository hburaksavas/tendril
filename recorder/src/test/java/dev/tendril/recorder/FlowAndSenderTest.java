package dev.tendril.recorder;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FlowAndSenderTest {

    @Test
    void flowIsBoundToTheThread() throws Exception {
        assertEquals(0, Flow.current());
        long id = Flow.begin();
        assertTrue(id > 0);
        assertEquals(id, Flow.current());

        long[] other = new long[1];
        Thread t = new Thread(() -> other[0] = Flow.current());
        t.start();
        t.join();
        assertEquals(0, other[0], "another thread must not see this flow");

        Flow.end();
        assertEquals(0, Flow.current());
    }

    @Test
    void sendsEventsToTheIdeWithTheToken() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/events", exchange -> {
            String token = exchange.getRequestHeaders().getFirst("X-Tendril-Token");
            received.add(token + "|" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty("tendril.recorder.port", String.valueOf(server.getAddress().getPort()));
            System.setProperty("tendril.recorder.token", "secret");
            Sender.send("{\"kind\":\"hello\"}");
            assertEquals("secret|{\"kind\":\"hello\"}", received.poll(5, TimeUnit.SECONDS));
        } finally {
            server.stop(0);
            System.clearProperty("tendril.recorder.port");
            System.clearProperty("tendril.recorder.token");
        }
    }

    @Test
    void doesNothingWhenNoPortIsConfigured() {
        System.clearProperty("tendril.recorder.port");
        assertFalse(Config.enabled());
        assertDoesNotThrow(() -> Sender.send("{}"));
    }
}
