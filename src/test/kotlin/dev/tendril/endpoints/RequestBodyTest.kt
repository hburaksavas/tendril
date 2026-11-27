package dev.tendril.endpoints

import com.google.gson.JsonParser
import dev.tendril.KotlinSpringTestCase
import dev.tendril.calls.FindServiceCallsAction

/** The sample body of a generated request, for Feign methods (where the body needs no annotation) and Kotlin. */
class RequestBodyTest : KotlinSpringTestCase() {

    fun `test a Feign body needs no annotation, in Java and Kotlin`() {
        myFixture.addClass("package app; public record Item(String sku, java.util.List<Integer> sizes) { }")
        myFixture.addClass(
            """
            package app;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.*;
            @FeignClient(name = "items")
            public interface ItemsClient {
                @PostMapping("/items") void add(@RequestHeader("X-Tenant") String tenant, Item item);
                @GetMapping("/items/{id}") Item get(@PathVariable("id") String id);
                @PostMapping("/uris") void uri(java.net.URI base);
            }
            """.trimIndent(),
        )
        val client = myFixture.findClass("app.ItemsClient")
        fun body(name: String, implicit: Boolean = true) =
            EndpointRequests.bodyOf(client.findMethodsByName(name, false).single(), implicit = implicit, oneLine = true)
        assertEquals("""{"sku":"string","sizes":[0]}""", body("add"))
        assertNull(body("add", implicit = false)) // a controller needs @RequestBody
        assertNull(body("get"))
        assertNull(body("uri"))

        val file = myFixture.configureByText(
            "Prices.kt",
            """
            package app
            import org.springframework.cloud.openfeign.FeignClient
            import org.springframework.web.bind.annotation.*
            data class Price(val amount: Long, val currency: String)
            @FeignClient(name = "prices")
            interface PriceClient {
                @PostMapping("/prices") fun save(<caret>price: Price): Price
            }
            """.trimIndent(),
        )
        val save = FindServiceCallsAction.serviceMethodAt(file, myFixture.caretOffset)!!
        assertEquals(JsonParser.parseString("""{"amount":0,"currency":"string"}"""), JsonParser.parseString(EndpointRequests.bodyOf(save, implicit = true)!!))
    }

    fun `test a query map object becomes the query`() {
        myFixture.addClass("package org.springframework.cloud.openfeign; public @interface SpringQueryMap { }")
        myFixture.addClass("package feign; public @interface QueryMap { }")
        myFixture.addClass("package feign; public @interface Param { String value() default \"\"; }")
        myFixture.addClass("package app; public enum Status { NEW, PAID }")
        myFixture.addClass("package app; public class Paging { private int page; private int size; }")
        myFixture.addClass(
            """
            package app;
            public class OrderFilter extends Paging {
                public static final int MAX = 10;
                private Status status;
                @feign.Param("customer_id") private String customerId;
                private java.util.List<String> tags;
                private Paging nested;
                private transient String cache;
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package app;
            import org.springframework.cloud.openfeign.*;
            import org.springframework.web.bind.annotation.*;
            @FeignClient(name = "orders")
            public interface OrdersClient {
                @GetMapping("/orders") String search(@SpringQueryMap OrderFilter filter, @RequestParam("sort") String sort);
                @GetMapping("/raw") String raw(@feign.QueryMap java.util.Map<String, Object> query);
            }
            """.trimIndent(),
        )
        val client = myFixture.findClass("app.OrdersClient")
        fun query(name: String) = EndpointRequests.queryOf(client.findMethodsByName(name, false).single())
        assertEquals("?page=0&size=0&status=NEW&customer_id=string&tags=string&sort=value", query("search"))
        assertEquals("?{{query}}", query("raw"))
        assertNull(EndpointRequests.bodyOf(client.findMethodsByName("search", false).single(), implicit = true))

        val file = myFixture.configureByText(
            "Search.kt",
            """
            package app
            import org.springframework.cloud.openfeign.*
            import org.springframework.web.bind.annotation.*
            data class PriceFilter(val sku: String, val min: Long?)
            @FeignClient(name = "prices")
            interface PriceClient {
                @GetMapping("/prices") fun <caret>find(@SpringQueryMap filter: PriceFilter): String
            }
            """.trimIndent(),
        )
        val find = FindServiceCallsAction.serviceMethodAt(file, myFixture.caretOffset)!!
        assertEquals("?sku=string&min=0", EndpointRequests.queryOf(find))
    }
}
