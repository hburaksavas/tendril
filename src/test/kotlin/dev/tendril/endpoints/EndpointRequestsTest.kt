package dev.tendril.endpoints

import dev.tendril.SpringTestCase

class EndpointRequestsTest : SpringTestCase() {

    private fun controller() {
        myFixture.addClass(
            """
            package app;
            import java.util.List;
            public class OrderRequest {
                public String customerId;
                public int quantity;
                public boolean paid;
                public Kind kind;
                public Address address;
                public List<String> tags;
                public static class Address { public String city; }
                public enum Kind { CARD, WIRE }
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package app;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/api/orders")
            public class OrderController {
                @PostMapping public String create(@RequestBody OrderRequest request, @RequestHeader("X-Tenant") String tenant) { return ""; }
                @GetMapping("/{orderId}/items/{itemId}")
                public String item(@PathVariable String orderId, @PathVariable("itemId") long itemId,
                                   @RequestParam("expand") String expand, @RequestParam(name = "page") int page) { return ""; }
                @RequestMapping("/ping") public String ping() { return ""; }
            }
            """.trimIndent(),
        )
    }

    private fun endpoint(method: String, path: String) =
        EndpointScanner.scan(project).single { it.httpMethod == method && it.path == path }

    fun `test the address defaults to localhost 8080`() {
        controller()
        assertEquals("http://localhost:8080/api/orders", EndpointRequests.url(project, endpoint("POST", "/api/orders")))
    }

    fun `test the address follows the port and context path of the config`() {
        controller()
        yaml("application.yml", "server:\n  port: 8081\n  servlet:\n    context-path: /playground/")
        assertEquals("http://localhost:8081/playground/api/orders", EndpointRequests.url(project, endpoint("POST", "/api/orders")))
    }

    fun `test a request with a body gets a json skeleton and its header`() {
        controller()
        val http = EndpointRequests.http(project, endpoint("POST", "/api/orders"))
        assertTrue(http, http.contains("POST http://localhost:8080/api/orders"))
        assertTrue(http, http.contains("Content-Type: application/json"))
        assertTrue(http, http.contains("\"customerId\": \"string\""))
        assertTrue(http, http.contains("\"quantity\": 0"))
        assertTrue(http, http.contains("\"paid\": false"))
        assertTrue(http, http.contains("\"kind\": \"CARD\""))
        assertTrue(http, http.contains("\"city\": \"string\""))
        assertTrue(http, http.contains("\"tags\": []"))
    }

    fun `test path variables become http client variables and request params become the query`() {
        controller()
        val http = EndpointRequests.http(project, endpoint("GET", "/api/orders/{orderId}/items/{itemId}"))
        assertTrue(http, http.contains("GET http://localhost:8080/api/orders/{{orderId}}/items/{{itemId}}?expand=value&page=value"))
    }

    fun `test request params named with value or name both count`() {
        controller()
        val http = EndpointRequests.http(project, endpoint("GET", "/api/orders/{orderId}/items/{itemId}"))
        assertTrue(http, http.contains("?expand=value&page=value"))
    }

    fun `test the curl command has the verb the header and the body`() {
        controller()
        val curl = EndpointRequests.curl(project, endpoint("POST", "/api/orders"))
        assertTrue(curl, curl.startsWith("curl -g -X POST"))
        assertTrue(curl, curl.contains("-H 'Content-Type: application/json'"))
        assertTrue(curl, curl.contains("-d '{"))
    }

    fun `test an endpoint without a method is sent as GET`() {
        controller()
        val http = EndpointRequests.http(project, endpoint("ANY", "/api/orders/ping"))
        assertTrue(http, http.contains("GET http://localhost:8080/api/orders/ping"))
    }
}
