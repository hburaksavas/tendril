package dev.tendril.calls

import junit.framework.TestCase

/** Matching across a React frontend, a BFF and a backend, on hand-made snapshots. */
class ServiceGraphTest : TestCase() {

    private fun loc(file: String, line: Int = 0) = Location(file, line)

    private val backend = ProjectSnapshot(
        ProjectSnapshot.VERSION, "/w/backend", "backend", 0,
        apps = listOf(AppInfo("orders-service", "8081", "/orders-api")),
        endpoints = listOf(
            EndpointNode(0, "GET", "/orders/{id}", "OrderController.get", loc("/w/backend/OrderController.java", 10), emptyList()),
            EndpointNode(0, "POST", "/orders", "OrderController.create", loc("/w/backend/OrderController.java", 20), emptyList()),
        ),
        feign = emptyList(),
    )

    private fun bff(url: String?, service: String = "orders-service") = ProjectSnapshot(
        ProjectSnapshot.VERSION, "/w/bff", "bff", 0,
        apps = listOf(AppInfo("bff", "8080", "")),
        endpoints = listOf(
            EndpointNode(0, "GET", "/api/orders/{orderId}", "BffController.order", loc("/w/bff/BffController.java", 5), listOf(0)),
        ),
        feign = listOf(
            FeignNode(0, "OrdersClient", service, url, "GET", "/orders-api/orders/{id}", "OrdersClient.get", loc("/w/bff/OrdersClient.java", 7)),
        ),
    )

    private val web = listOf(
        FetchCall("GET", "/api/orders/*", loc("/w/web/src/api.ts", 3)),
        FetchCall("POST", "/api/orders/*", loc("/w/web/src/api.ts", 9)),
        FetchCall("GET", "*/orders/*", loc("/w/web/src/other.ts", 1)),
    )

    private fun graph(bff: ProjectSnapshot) = ServiceGraph(
        listOf(
            ServiceSource("/w/backend", "backend", ServiceSource.State.CURRENT, backend, emptyList()),
            ServiceSource("/w/bff", "bff", ServiceSource.State.SAVED, bff, emptyList()),
            ServiceSource("/w/web", "web", ServiceSource.State.NONE, null, web),
        ),
    )

    private fun titles(nodes: List<ChainNode>, indent: String = ""): List<String> =
        nodes.flatMap { listOf(indent + it.text) + titles(it.children, "$indent  ") }

    fun `test the chain from a backend endpoint up to the frontend`() {
        val g = graph(bff("http://localhost:8081"))
        val callers = titles(g.callersOf(ServiceGraph.EndpointRef(0, 0)))
        assertEquals(
            listOf(
                "OrdersClient.get → GET /orders-api/orders/{id}",
                "  GET /api/orders/{orderId}",
                "    fetch GET /api/orders/*",
                "    fetch GET */orders/*",
                "fetch GET */orders/*", // base unknown: matched by the end of the path
            ),
            callers,
        )
    }

    fun `test the service is found by name when the client has no address`() {
        val g = graph(bff(null))
        val targets = g.targetsOf(ServiceGraph.FeignRef(1, 0))
        assertEquals(listOf(ServiceGraph.EndpointRef(0, 0)), targets.map { it.endpoint })
        assertFalse(targets.single().byPathOnly)
    }

    fun `test a client of an unknown service matches by path only`() {
        val g = graph(bff("http://payments.internal", service = "payments"))
        val target = g.targetsOf(ServiceGraph.FeignRef(1, 0)).single()
        assertTrue(target.byPathOnly)
    }

    fun `test another port is another service`() {
        val g = graph(bff("http://localhost:9999"))
        // no application listens on 9999: falls back to the path, marked as such
        assertTrue(g.targetsOf(ServiceGraph.FeignRef(1, 0)).single().byPathOnly)
    }

    fun `test downstream from the BFF endpoint`() {
        val g = graph(bff("http://localhost:8081"))
        assertEquals(
            listOf("OrdersClient.get → GET /orders-api/orders/{id}", "  GET /orders-api/orders/{id}"),
            titles(g.callsOf(ServiceGraph.EndpointRef(1, 0))),
        )
    }

    fun `test path matching`() {
        assertTrue(CallMatching.pathMatches("/a/*/c", "/a/{b}/c"))
        assertTrue(CallMatching.pathMatches("/a/{x}", "/a/{b}"))
        assertFalse(CallMatching.pathMatches("/a/b", "/a/b/c"))
        assertTrue(CallMatching.pathMatches("/files/x/y", "/files/**"))
        assertTrue(CallMatching.pathMatches("/b/c", "/ctx/a/b/c", suffix = true))
        assertFalse(CallMatching.pathMatches("/*", "/a", suffix = true))
        assertEquals("8443", CallMatching.parseUrl("https://h:8443/x").port)
        assertEquals("443", CallMatching.parseUrl("https://h/x").port)
        assertEquals(CallMatching.serviceKey("ORDERS_SERVICE"), CallMatching.serviceKey("orders-service"))
    }
}
