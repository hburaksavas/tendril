package dev.tendril.recorder;

import feign.Client;
import feign.Contract;
import feign.MethodMetadata;
import feign.Param;
import feign.Request;
import feign.RequestLine;
import feign.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MocksTest {

    interface OrdersClient {
        @RequestLine("GET /v1/orders/{id}")
        String get(@Param("id") String id);
    }

    @AfterEach
    void clear() {
        Mocks.replace(List.of(), "");
    }

    private static Map<String, String> form(String... pairs) {
        var out = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Test
    void parsesTheFormOfTheIde() {
        List<Mocks.Rule> rules = Mocks.parse(form(
                "count", "1", "0.kind", "feign", "0.key", "OrdersClient#get(String)", "0.label", "OrdersClient.get",
                "0.method", "GET", "0.path", "/orders/42", "0.q.page", "2", "0.h.X-Tenant", "a",
                "0.status", "404", "0.delayMs", "50", "0.rh.1", "X-B: 2", "0.rh.0", "Content-Type: application/json",
                "0.body", "{\"a\":1}"));
        Mocks.Rule rule = rules.get(0);
        assertEquals("feign", rule.kind);
        assertEquals("OrdersClient#get(String)", rule.key);
        assertEquals(Map.of("page", "2"), rule.query);
        assertEquals(Map.of("X-Tenant", "a"), rule.headers);
        assertEquals(404, rule.status);
        assertEquals(50, rule.delayMs);
        assertEquals("Content-Type", rule.responseHeaders.get(0)[0]);
        assertEquals("2", rule.responseHeaders.get(1)[1]);
        assertEquals("{\"a\":1}", new String(rule.body, StandardCharsets.UTF_8));
        assertTrue(Mocks.parse(form()).isEmpty());
    }

    @Test
    void matchesPaths() {
        assertTrue(Mocks.pathMatches("/api/orders/{id}", "/api/orders/42", false));
        assertFalse(Mocks.pathMatches("/api/orders/{id}", "/api/orders/42/items", false));
        assertTrue(Mocks.pathMatches("/api/**", "/api/orders/42/items", false));
        assertTrue(Mocks.pathMatches("/orders/42", "/base/v1/orders/42", true));
        assertFalse(Mocks.pathMatches("/orders/42", "/base/v1/orders/43", true));
        assertTrue(Mocks.pathMatches("/files/a b", "/files/a%20b", false));
    }

    @Test
    void anHttpRuleNeedsItsMethodPathQueryAndHeaders() {
        Mocks.replace(Mocks.parse(form(
                "count", "2",
                "0.kind", "http", "0.method", "GET", "0.path", "/api/orders/{id}", "0.q.view", "full", "0.status", "201",
                "1.kind", "http", "1.method", "GET", "1.path", "/api/orders/{id}", "1.status", "200")), "v1");
        assertEquals("v1", Mocks.version());
        assertEquals(201, Mocks.http("GET", "/api/orders/1", "view=full&x=1", h -> null).status);
        assertEquals(200, Mocks.http("GET", "/api/orders/1", null, h -> null).status);
        assertNull(Mocks.http("GET", "/api/other", null, h -> null));
    }

    @Test
    void aFeignCallIsAnsweredByTheMockWithoutReachingTheService() throws IOException {
        MethodMetadata metadata = new Contract.Default().parseAndValidateMetadata(OrdersClient.class).get(0);
        assertEquals("OrdersClient#get(String)", metadata.configKey());
        Mocks.replace(Mocks.parse(form(
                "count", "1", "0.kind", "feign", "0.key", metadata.configKey(), "0.path", "/orders/42",
                "0.status", "503", "0.rh.0", "Content-Type: text/plain", "0.body", "down")), "v");

        AtomicInteger real = new AtomicInteger();
        Client delegate = (request, options) -> {
            real.incrementAndGet();
            return Response.builder().status(200).request(request).headers(Map.of()).body("real", StandardCharsets.UTF_8).build();
        };
        Client client = new FeignRecording().enrich(delegate);

        Response mocked = client.execute(request(metadata, "http://orders/v1/orders/42"), new Request.Options());
        assertEquals(503, mocked.status());
        assertEquals("down", new String(mocked.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(0, real.get());

        Response other = client.execute(request(metadata, "http://orders/v1/orders/7"), new Request.Options());
        assertEquals(200, other.status());
        assertEquals(1, real.get());
    }

    @Test
    void aFaultIsThrownLikeTheRealClientWould() {
        Mocks.Rule rule = new Mocks.Rule();
        rule.fault = "timeout";
        rule.delayMs = 1;
        assertThrows(SocketTimeoutException.class, () -> FeignRecording.RecordingClient.answer(rule, request(null, "http://x/a")));
    }

    private static Request request(MethodMetadata metadata, String url) {
        return Request.create(Request.HttpMethod.GET, url, Map.of(), null, StandardCharsets.UTF_8,
                metadata == null ? null : metadata.template());
    }
}
