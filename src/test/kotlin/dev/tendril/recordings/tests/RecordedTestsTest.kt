package dev.tendril.recordings.tests

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import dev.tendril.KotlinSpringTestCase
import dev.tendril.recordings.Recording
import dev.tendril.recordings.RecordingKind

/** A test made from a recorded request, with the Feign call of the same flow as a stub; Java and Kotlin. */
class RecordedTestsTest : KotlinSpringTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass("package org.springframework.http; public class ResponseEntity<T> { }")
        myFixture.addClass("package app; public record Order(String id, java.util.List<Line> lines) { public record Line(String sku) { } }")
        myFixture.addClass(
            """
            package app;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.*;
            @FeignClient(name = "orders")
            public interface OrdersClient {
                @GetMapping("/orders/{id}") Order get(@PathVariable("id") String id, @RequestParam("v") int version);
                @GetMapping("/orders") java.util.List<Order> all();
                @PostMapping("/audit") void audit(String text);
            }
            """.trimIndent(),
        )
        yaml("application.yml", "server:\n  servlet:\n    context-path: /shop")
    }

    private fun recording(
        kind: RecordingKind, method: String, url: String, status: Int?, flow: Long, time: Long = 0,
        body: String? = null, headers: List<Pair<String, String>> = emptyList(), responseBody: String? = null,
        responseHeaders: List<Pair<String, String>> = emptyList(), handler: String? = null, pattern: String? = null,
        client: String? = null,
    ) = Recording(
        kind = kind, time = time, durationMs = 5, flow = flow, method = method, url = url, headers = headers, body = body,
        bodyEncoding = body?.let { "text" }, status = status, responseHeaders = responseHeaders, responseBody = responseBody,
        responseBodyEncoding = responseBody?.let { "text" }, handler = handler, pattern = pattern, client = client, error = null,
        transport = null, destination = null, exchange = null, routingKey = null, contentType = null, replayable = false, detail = null,
    )

    private val incoming = recording(
        RecordingKind.INCOMING, "POST", "http://localhost:8080/shop/api/orders/42?expand=true", 201, flow = 7, time = 1000,
        body = """{"note":"a ${'$'}5 \"gift\""}""",
        headers = listOf("Host" to "localhost", "Content-Type" to "application/json", "X-Tenant" to "acme", "Authorization" to "***"),
        responseBody = """{"id":"42","status":"OK"}""", responseHeaders = listOf("Content-Type" to "application/json"),
        handler = "OrderController.update", pattern = "/api/orders/{id}",
    )

    private val outgoing = listOf(
        recording(RecordingKind.OUTGOING, "GET", "http://orders/orders/42?v=3", 200, flow = 7, time = 1001,
            responseBody = """{"id":"42","lines":[{"sku":"A"}]}""", client = "OrdersClient#get(String,int)"),
        recording(RecordingKind.OUTGOING, "GET", "http://orders/orders", 503, flow = 7, time = 1002, client = "OrdersClient#all()"),
        recording(RecordingKind.OUTGOING, "POST", "http://orders/audit", 200, flow = 7, time = 1003, client = "OrdersClient#audit(String)"),
        recording(RecordingKind.OUTGOING, "GET", "http://orders/orders/9", 200, flow = 8, time = 1004,
            responseBody = "{}", client = "OrdersClient#get(String,int)"),
    )

    private fun assertParses(text: String, extension: String) {
        val file = PsiFileFactory.getInstance(project).createFileFromText("T.$extension", FileTypeManager.getInstance().getFileTypeByExtension(extension), text)
        val errors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
        assertTrue(errors.joinToString { "${it.errorDescription} at ${it.textOffset}" } + "\n" + text, errors.isEmpty())
    }

    fun `test a Java test from a recorded request`() {
        myFixture.addClass(
            """
            package app;
            import org.springframework.web.bind.annotation.*;
            @RestController @RequestMapping("/api")
            public class OrderController {
                @PostMapping("/orders/{id}") public String update(@PathVariable String id, @RequestBody String body) { return ""; }
            }
            """.trimIndent(),
        )
        val plan = RecordedTests.plan(project, incoming, outgoing).getOrThrow()
        val t = plan.test
        assertFalse(t.kotlin)
        assertEquals("app", t.packageName)
        assertEquals("OrderControllerRecordedTest", t.className)
        assertEquals("updateReturns201", t.testName)
        assertEquals("org.springframework.boot.test.mock.mockito.MockBean", t.mockAnnotation)
        assertEquals("/shop", t.contextPath)
        assertEquals(listOf("X-Tenant" to "acme"), t.headers)
        assertEquals(listOf("OrdersClient"), t.clients.map { it.simpleName })

        val get = t.stubs.single { it.method == "get" }
        assertEquals(listOf("any()", "anyInt()"), get.matchers)
        assertEquals("Order", get.values.single().type)
        assertTrue(t.stubs.single { it.method == "all" }.notes.single().contains("returned 503"))
        assertTrue(t.stubs.single { it.method == "audit" }.notes.single().contains("returns nothing"))

        val text = RecordedTestText.render(t)
        assertParses(text, "java")
        assertTrue(text, text.contains("@MockBean\n    OrdersClient ordersClient;"))
        assertTrue(text, text.contains("given(ordersClient.get(any(), anyInt()))"))
        assertTrue(text, text.contains("new TypeReference<Order>() {}"))
        assertTrue(text, text.contains("mockMvc.perform(post(\"/shop/api/orders/42?expand=true\")"))
        assertTrue(text, text.contains(".contextPath(\"/shop\")"))
        assertTrue(text, text.contains(".header(\"X-Tenant\", \"acme\")"))
        assertTrue(text, text.contains("\"note\": \"a \$5 \\\\\"gift\\\\\"\"")) // escaped inside the text block
        assertTrue(text, text.contains(".andExpect(status().is(201))"))
        assertTrue(text, text.contains(".andExpect(content().json("))
        assertTrue(text, text.contains("// Authorization was masked when recorded: set it here"))
    }

    fun `test a Kotlin test from a recorded request`() {
        myFixture.configureByText(
            "OrderController.kt",
            """
            package app
            import org.springframework.web.bind.annotation.*
            @RestController
            @RequestMapping("/api")
            class OrderController {
                @PostMapping("/orders/{id}") fun update(@PathVariable id: String, @RequestBody body: String) = ""
            }
            """.trimIndent(),
        )
        val t = RecordedTests.plan(project, incoming, outgoing).getOrThrow().test
        assertTrue(t.kotlin)
        assertEquals("POST shop api orders 42 returns 201", t.testName)
        assertEquals(listOf("anyArg()", "ArgumentMatchers.anyInt()"), t.stubs.single { it.method == "get" }.matchers)
        val text = RecordedTestText.render(t)
        assertParses(text, "kt")
        assertTrue(text, text.contains("lateinit var ordersClient: OrdersClient"))
        assertTrue(text, text.contains("object : TypeReference<Order>() {}"))
        assertTrue(text, text.contains("\${'$'}5")) // a dollar in a raw string
        assertTrue(text, text.contains("status().`is`(201)"))
        assertTrue(text, text.contains("private fun <T> anyArg(): T"))
    }

    fun `test generic and nested return types`() {
        val imports = sortedSetOf<String>()
        val method = myFixture.findClass("app.OrdersClient").findMethodsByName("all", false).single()
        assertEquals("List<Order>", RecordedTests.typeText(method.returnType!!, false, imports))
        assertEquals(setOf("app.Order", "java.util.List"), imports) // same-package classes are dropped when imports are written
        val kotlinImports = sortedSetOf<String>()
        assertEquals("List<Order>", RecordedTests.typeText(method.returnType!!, true, kotlinImports))
        assertEquals(setOf("app.Order"), kotlinImports) // Kotlin's own List: no import
        val line = myFixture.findClass("app.Order").findInnerClassByName("Line", false)!!
        val type = com.intellij.psi.JavaPsiFacade.getElementFactory(project).createType(line)
        val nested = sortedSetOf<String>()
        assertEquals("Order.Line", RecordedTests.typeText(type, false, nested))
    }

    fun `test a request without a controller is explained`() {
        val result = RecordedTests.plan(project, incoming.let { recording(RecordingKind.INCOMING, "GET", "http://h/none", 200, 0, pattern = "/none") }, emptyList())
        assertEquals("No unique controller method was found for this request.", result.exceptionOrNull()?.message)
    }
}
