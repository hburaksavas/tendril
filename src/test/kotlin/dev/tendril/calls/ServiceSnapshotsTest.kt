package dev.tendril.calls

import dev.tendril.KotlinSpringTestCase
import java.nio.file.Files

/** The snapshot of a BFF: which Feign calls each endpoint makes, through services and interfaces. */
class ServiceSnapshotsTest : KotlinSpringTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass(
            """
            package bff;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.*;
            @FeignClient(name = "orders-service", url = "${'$'}{clients.orders}", path = "/v1")
            public interface OrdersClient {
                @GetMapping("/orders/{id}") String get(@PathVariable("id") String id);
                @PostMapping("/orders") String create(String body);
            }
            """.trimIndent(),
        )
        myFixture.addClass("package bff; public interface OrderService { String find(String id); }")
        myFixture.addClass(
            """
            package bff;
            @org.springframework.stereotype.Service
            public class OrderServiceImpl implements OrderService {
                private final OrdersClient client;
                OrderServiceImpl(OrdersClient client) { this.client = client; }
                public String find(String id) { return helper(id); }
                private String helper(String id) { return client.get(id); }
            }
            """.trimIndent(),
        )
        yaml("application.yml", "spring:\n  application:\n    name: bff\nserver:\n  port: 8090\nclients:\n  orders: http://localhost:8081")
    }

    fun `test an endpoint reaches the Feign call through an interface and private methods`() {
        myFixture.addClass(
            """
            package bff;
            import org.springframework.web.bind.annotation.*;
            @RestController @RequestMapping("/api")
            public class BffController {
                private final OrderService orders;
                BffController(OrderService orders) { this.orders = orders; }
                @GetMapping("/orders/{id}") public String order(@PathVariable("id") String id) { return orders.find(id); }
                @GetMapping("/health") public String health() { return "ok"; }
            }
            """.trimIndent(),
        )
        val s = ServiceSnapshots.build(project)
        assertEquals(AppInfo("bff", "8090", ""), s.apps.single())
        val get = s.feign.single { it.handler == "OrdersClient.get" }
        assertEquals("/v1/orders/{id}", get.path)
        assertEquals("http://localhost:8081", get.url)
        assertEquals("orders-service", get.service)

        val order = s.endpoints.single { it.handler == "BffController.order" }
        assertEquals(listOf(s.feign.indexOf(get)), order.feignCalls)
        assertEquals(emptyList<Int>(), s.endpoints.single { it.handler == "BffController.health" }.feignCalls)
    }

    fun `test a Kotlin controller`() {
        myFixture.configureByText(
            "BffController.kt",
            """
            package bff
            import org.springframework.web.bind.annotation.*
            @RestController
            class KotlinController(private val client: OrdersClient) {
                @PostMapping("/api/orders") fun create(body: String) = client.create(body)
            }
            """.trimIndent(),
        )
        val s = ServiceSnapshots.build(project)
        val create = s.endpoints.single { it.handler == "KotlinController.create" }
        assertEquals(listOf("OrdersClient.create"), create.feignCalls.map { s.feign[it].handler })
        assertEquals(4, create.location?.line)
    }

    fun `test the context menu finds the method around the caret`() {
        val file = myFixture.configureByText(
            "Web.kt",
            """
            package bff
            import org.springframework.web.bind.annotation.*
            @RestController
            class Web(private val client: OrdersClient) {
                @GetMapping("/a") fun a(): String { val x = 1; return client.get("" + <caret>x) }
                fun notMapped() = 1
            }
            """.trimIndent(),
        )
        val offset = myFixture.caretOffset
        assertEquals("a", FindServiceCallsAction.serviceMethodAt(file, offset)?.name)
        assertNull(FindServiceCallsAction.serviceMethodAt(file, file.text.indexOf("notMapped")))
        assertNull(FindServiceCallsAction.serviceMethodAt(file, 0))
        val feign = myFixture.findClass("bff.OrdersClient").containingFile
        assertEquals("create", FindServiceCallsAction.serviceMethodAt(feign, feign.text.indexOf("create"))?.name)
    }

    fun `test a snapshot survives the store`() {
        val dir = Files.createTempDirectory("service-map")
        val previous = ServiceMapStore.directory
        try {
            ServiceMapStore.directory = dir
            val s = ServiceSnapshots.build(project)
            ServiceMapStore.write(s)
            val read = ServiceMapStore.read(s.projectPath)!!
            assertEquals(s.feign, read.feign)
            assertEquals(s.apps, read.apps)
            assertNull(ServiceMapStore.read("/somewhere/else"))
        } finally {
            ServiceMapStore.directory = previous
            dir.toFile().deleteRecursively()
        }
    }
}
