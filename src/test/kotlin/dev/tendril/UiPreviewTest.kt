package dev.tendril

import com.google.gson.JsonParser
import dev.tendril.endpoints.EndpointScanner
import dev.tendril.endpoints.EndpointsPanel
import dev.tendril.feign.FeignPanel
import dev.tendril.feign.FeignResolver
import dev.tendril.feign.FeignScanner
import dev.tendril.http.HttpRequestPanel
import dev.tendril.http.RequestHost
import dev.tendril.recordings.Recording
import dev.tendril.recordings.RecordingKind
import dev.tendril.recordings.RecordingPane

/** Not a test of behaviour: renders the main panels with sample data to PNG files in build/ui-preview. */
class UiPreviewTest : SpringTestCase() {

    private val host = object : RequestHost {
        override fun sendRequest(text: String) {}
    }

    private fun sampleProject() {
        myFixture.addClass(
            """
            package app;
            import org.springframework.web.bind.annotation.*;
            @RestController @RequestMapping("/api/brands")
            public class BrandController {
                @GetMapping public String list() { return ""; }
                @GetMapping("/{id}") public String get(@PathVariable Long id) { return ""; }
                @GetMapping("/search") public String search(@RequestParam("q") String q) { return ""; }
                @PostMapping public String create(@RequestBody String body) { return ""; }
                @PutMapping("/{id}") public String update(@PathVariable Long id, @RequestBody String body) { return ""; }
                @DeleteMapping("/{id}") public void delete(@PathVariable Long id) {}
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package app;
            import org.springframework.web.bind.annotation.*;
            @RestController @RequestMapping("/api/orders")
            public class OrderController {
                @PostMapping public String create(@RequestBody String body) { return ""; }
                @GetMapping("/{orderId}/items/{itemId}") public String item(@PathVariable String orderId, @PathVariable String itemId) { return ""; }
                @RequestMapping("/ping") public String ping() { return ""; }
                @PatchMapping("/{orderId}") public String patch(@PathVariable String orderId) { return ""; }
            }
            """.trimIndent(),
        )
        for ((name, url, extra) in listOf(
            Triple("InventoryClient", "name = \"inventory-service\", url = \"\${clients.inventory.url}\", path = \"/v1\"", ""),
            Triple("PricingClient", "name = \"pricing-service\"", ""),
            Triple("LegacyLedgerClient", "name = \"legacy-ledger\"", ""),
        )) {
            myFixture.addClass(
                """
                package app;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.*;
                @FeignClient($url)
                public interface $name {
                    @GetMapping("/stock/{sku}") String stock(@PathVariable("sku") String sku);
                    @PostMapping("/reservations") String reserve(@RequestBody String body);
                }
                """.trimIndent(),
            )
        }
        yaml("application.yml", "clients:\n  inventory:\n    url: http://localhost:8081/playground/mock\npricing-service:\n  url: https://pricing.test.example.com")
        yaml("application-test.yml", "x: 1")
    }


    private fun rec(vararg fields: Pair<String, Any>): Recording {
        val o = com.google.gson.JsonObject()
        for ((k, v) in fields) when (v) {
            is String -> o.addProperty(k, v)
            is Number -> o.addProperty(k, v)
            is Boolean -> o.addProperty(k, v)
            is List<*> -> o.add(k, com.google.gson.JsonArray().also { arr ->
                v.forEach { p ->
                    val (a, b) = p as Pair<*, *>
                    arr.add(com.google.gson.JsonArray().also { it.add(a as String); it.add(b as String) })
                }
            })
        }
        return Recording.fromJson(o)!!
    }

    fun `test render the panels`() {
        sampleProject()

        val incoming = EndpointsPanel(project, host) {}
        incoming.show(EndpointScanner.scan(project))
        UiSnapshot.save(incoming, "endpoints-incoming", 820, 360)

        val outgoing = FeignPanel(project, host) {}
        outgoing.show(
            listOf("(default)", "test"),
            FeignScanner.scan(project).map { FeignResolver.resolve(project, it, null) },
        )
        UiSnapshot.save(outgoing, "endpoints-outgoing", 820, 360)

        UiSnapshot.save(EndpointsPanel(project, host) {}, "endpoints-empty", 820, 200)

        val request = HttpRequestPanel(project) {}
        request.setRequest("### BrandController.create\nPOST {{baseUrl}}/api/brands\nContent-Type: application/json\n# Authorization: Bearer {{token}}\n\n{\n  \"name\": \"Acme\",\n  \"active\": true\n}\n")
        UiSnapshot.save(request, "request-section", 820, 380)

        val incomingPane = RecordingPane(project, RecordingKind.INCOMING)
        incomingPane.show(
            listOf(
                rec(
                    "kind" to "http_in", "time" to 1795214823047L, "durationMs" to 51, "method" to "GET",
                    "url" to "http://localhost:8081/playground/api/demo/stock/sku-9", "status" to 200,
                    "headers" to listOf("accept" to "*/*", "authorization" to "***"),
                    "responseHeaders" to listOf("Content-Type" to "application/json"),
                    "responseBody" to "{\"sku\":\"sku-9\",\"quantity\":42}", "handler" to "FeignDemoController.stock",
                ),
                rec(
                    "kind" to "http_in", "time" to 1795214823137L, "durationMs" to 76, "method" to "POST",
                    "url" to "http://localhost:8081/playground/api/brands", "status" to 201,
                    "headers" to listOf("content-type" to "application/json"),
                    "body" to "{\"name\":\"Acme\",\"password\":\"***\"}", "responseBody" to "{\"id\":1}",
                    "handler" to "BrandController.create",
                ),
                rec(
                    "kind" to "http_in", "time" to 1795214823500L, "durationMs" to 9, "method" to "GET",
                    "url" to "http://localhost:8081/playground/api/brands/99", "status" to 404,
                    "responseBody" to "{\"error\":\"not found\"}", "handler" to "BrandController.get",
                ),
                rec(
                    "kind" to "http_in", "time" to 1795214824000L, "durationMs" to 310, "method" to "DELETE",
                    "url" to "http://localhost:8081/playground/api/brands/1", "status" to 500,
                ),
            ),
        )
        UiSnapshot.save(incomingPane, "recordings-incoming", 900, 380)

        val messagePane = RecordingPane(project, RecordingKind.MESSAGE)
        messagePane.show(
            listOf(
                rec(
                    "kind" to "message", "transport" to "rabbit", "time" to 1795214823047L, "durationMs" to 12,
                    "destination" to "orders.created", "exchange" to "orders", "routingKey" to "created",
                    "headers" to listOf("x-trace" to "a1"), "body" to "{\"orderId\":7}", "bodyEncoding" to "text",
                    "replayable" to true,
                ),
                rec(
                    "kind" to "message", "transport" to "kafka", "time" to 1795214823100L, "destination" to "payments",
                    "routingKey" to "k1", "partition" to 2, "offset" to 77, "body" to "hello", "replayable" to false,
                    "valueType" to "com.acme.Payment",
                ),
            ),
        )
        UiSnapshot.save(messagePane, "recordings-messages", 900, 300)

        val beans = dev.tendril.beans.BeansPanel(project)
        beans.show(
            listOf(
                dev.tendril.beans.BeanRow("brandController", "BrandController", "@RestController", "app", 0, null),
                dev.tendril.beans.BeanRow("orderController", "OrderController", "@RestController", "app", 0, null),
                dev.tendril.beans.BeanRow("orderService", "OrderService", "@Service", "app", 2, null),
                dev.tendril.beans.BeanRow("auditService", "AuditService", "@Service", "shared", 4, null),
                dev.tendril.beans.BeanRow("brandRepository", "BrandRepository", "Spring Data repository", "app", 3, null),
                dev.tendril.beans.BeanRow("clock", "Clock", "@Bean", "app", 1, null),
                dev.tendril.beans.BeanRow("appProperties", "AppProperties", "@ConfigurationProperties", "app", 5, null),
            ),
        )
        UiSnapshot.save(beans, "beans", 820, 300)
    }
}
