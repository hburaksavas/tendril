package dev.tendril.mocks

import com.google.gson.JsonParser
import dev.tendril.KotlinSpringTestCase
import dev.tendril.calls.FindServiceCallsAction
import dev.tendril.recordings.Recording
import dev.tendril.recordings.RecordingKind

/** Mocks started from the code (sample body of the return type, Feign key, endpoint path) or from a recording. */
class MockFactoryTest : KotlinSpringTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass("package org.springframework.http; public class ResponseEntity<T> { }")
        myFixture.addClass("package com.fasterxml.jackson.annotation; public @interface JsonProperty { String value() default \"\"; }")
        myFixture.addClass("package com.fasterxml.jackson.annotation; public @interface JsonIgnore { }")
        myFixture.addClass("package app; public enum Status { NEW, PAID }")
        myFixture.addClass("package app; public record Line(String sku, int quantity) { }")
        myFixture.addClass("package app; public class Page<T> { private java.util.List<T> content; private long total; }")
        myFixture.addClass(
            """
            package app;
            import com.fasterxml.jackson.annotation.*;
            public class Order {
                public static final String TYPE = "order";
                private java.util.UUID id;
                @JsonProperty("created_at") private java.time.LocalDate created;
                private Status status;
                private java.math.BigDecimal total;
                private java.util.List<Line> lines;
                private java.util.Map<String, Boolean> flags;
                private Order parent;
                @JsonIgnore private String internal;
                private transient String cache;
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package app;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.*;
            @FeignClient(name = "orders")
            public interface OrdersClient {
                @GetMapping("/orders/{id}") java.util.Optional<Order> get(@PathVariable("id") String id);
                @GetMapping("/orders") Page<Order> page(@RequestParam("ids") java.util.List<Long> ids, int size, String... tags);
                @PostMapping("/orders") void create(Order order);
            }
            """.trimIndent(),
        )
        yaml("application.yml", "server:\n  servlet:\n    context-path: /shop")
    }

    private fun method(cls: String, name: String) = myFixture.findClass(cls).findMethodsByName(name, false).single()

    fun `test the sample body of a DTO`() {
        val d = MockFactory.forMethod(project, method("app.OrdersClient", "get"))!!
        assertEquals(MockDefinition.FEIGN, d.kind)
        assertEquals("OrdersClient#get(String)", d.target)
        assertEquals("OrdersClient.get", d.label)
        assertEquals(
            JsonParser.parseString(
                """
                {"id":"3fa85f64-5717-4562-b3fc-2c963f66afa6","created_at":"2026-01-01","status":"NEW","total":0.0,
                 "lines":[{"sku":"string","quantity":0}],"flags":{"key":false},"parent":{}}
                """,
            ),
            d.body,
        )
        assertEquals(mapOf("Content-Type" to "application/json"), d.responseHeaders)
    }

    fun `test generics, varargs and void`() {
        val page = MockFactory.forMethod(project, method("app.OrdersClient", "page"))!!
        assertEquals("OrdersClient#page(List,int,String[])", page.target)
        val content = page.body!!.asJsonObject["content"].asJsonArray
        assertEquals(1, content.size())
        assertEquals("NEW", content[0].asJsonObject["status"].asString)
        val create = MockFactory.forMethod(project, method("app.OrdersClient", "create"))!!
        assertNull(create.body)
        assertEquals(emptyMap<String, String>(), create.responseHeaders)
    }

    fun `test an endpoint mock answers its path under the context path`() {
        myFixture.addClass(
            """
            package app;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.*;
            @RestController @RequestMapping("/api")
            public class OrderController {
                @GetMapping("/orders/{id}") public ResponseEntity<java.util.List<Line>> lines(@PathVariable String id) { return null; }
            }
            """.trimIndent(),
        )
        val d = MockFactory.forMethod(project, method("app.OrderController", "lines"))!!
        assertEquals(MockDefinition.HTTP, d.kind)
        assertEquals("GET", d.method)
        assertEquals("/shop/api/orders/{id}", d.path)
        assertEquals(JsonParser.parseString("""[{"sku":"string","quantity":0}]"""), d.body)
        // the file it writes is a valid mock
        assertEquals(d, MockFiles.parse(MockFiles.toText(d)))
    }

    fun `test a Kotlin client and controller`() {
        val file = myFixture.configureByText(
            "Clients.kt",
            """
            package app
            import org.springframework.cloud.openfeign.FeignClient
            import org.springframework.web.bind.annotation.*
            data class Price(val amount: Long, val currency: String?, val tags: List<String>)
            @FeignClient(name = "prices")
            interface PriceClient {
                @GetMapping("/prices/{sku}") fun price(@PathVariable sku: String, quantity: Int): Price
            }
            @RestController
            class PriceController {
                @PostMapping("/prices") fun save(@RequestBody price: Price): Price = <caret>price
            }
            """.trimIndent(),
        )
        val client = FindServiceCallsAction.serviceMethodAt(file, file.text.indexOf("fun price") + 5)!!
        val d = MockFactory.forMethod(project, client)!!
        assertEquals("PriceClient#price(String,int)", d.target)
        assertEquals(JsonParser.parseString("""{"amount":0,"currency":"string","tags":["string"]}"""), d.body)
        val controller = MockFactory.forMethod(project, FindServiceCallsAction.serviceMethodAt(file, myFixture.caretOffset)!!)!!
        assertEquals("POST", controller.method)
        assertEquals("/shop/prices", controller.path)
    }

    fun `test a mock from a recorded call`() {
        val r = recording(
            RecordingKind.OUTGOING, client = "OrdersClient#get(String)", url = "http://orders/v1/orders/42?x=1", status = 404,
            headers = listOf("Content-Type" to "application/json", "Content-Length" to "20", "Set-Cookie" to "***"),
            body = """{"error":"gone"}""",
        )
        val d = MockFactory.fromRecording(r)!!
        assertEquals("OrdersClient#get(String)", d.target)
        assertEquals("OrdersClient.get", d.label)
        assertEquals("/v1/orders/42", d.path)
        assertEquals(404, d.status)
        assertEquals(mapOf("Content-Type" to "application/json"), d.responseHeaders)
        assertEquals("""{"error":"gone"}""", d.bodyText)

        val text = MockFactory.fromRecording(
            recording(RecordingKind.INCOMING, handler = "OrderController.get", url = "http://h/shop/a", body = "hi",
                headers = listOf("Content-Type" to "text/plain")),
        )!!
        assertEquals("hi", text.bodyText)
        assertEquals(MockDefinition.HTTP, text.kind)
        assertEquals("/shop/a", text.path)
        assertNull(MockFactory.fromRecording(recording(RecordingKind.OUTGOING, client = null, url = "http://h/a")))
        assertNull(MockFactory.fromRecording(recording(RecordingKind.INCOMING, url = "http://h/a", status = null)))
    }

    private fun recording(
        kind: RecordingKind, client: String? = null, handler: String? = null, url: String, status: Int? = 200,
        headers: List<Pair<String, String>> = emptyList(), body: String? = null,
    ) = Recording(
        kind, 0, 1, 0, "GET", url, emptyList(), null, null, status, headers, body, "text", handler, null, client, null,
        null, null, null, null, null, false, null,
    )
}
