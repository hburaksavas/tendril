package dev.tendril.calls.clientgen

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import dev.tendril.KotlinSpringTestCase

/** A Feign method made from a controller handler: what is read from the backend, the text, and the write. */
class CreateFeignClientTest : KotlinSpringTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass("package org.springframework.http; public class ResponseEntity<T> { }")
        myFixture.addClass("package com.fasterxml.jackson.annotation; public @interface JsonProperty { String value() default \"\"; }")
        myFixture.addClass("package com.fasterxml.jackson.annotation; public @interface JsonIgnore { }")
        myFixture.addClass("package jakarta.servlet.http; public interface HttpServletRequest { }")
        myFixture.addClass("package org.springframework.web.multipart; public interface MultipartFile { }")
        myFixture.addClass("package shop.api; public enum Status { NEW, PAID }")
        myFixture.addClass(
            """
            package shop.api;
            import com.fasterxml.jackson.annotation.*;
            public class OrderDto {
                private String id;
                @JsonProperty("created_at") private java.time.LocalDate created;
                private Status status;
                private java.util.List<LineDto> lines;
                private OrderDto parent;
                @JsonIgnore private String secret;
            }
            """.trimIndent(),
        )
        myFixture.addClass("package shop.api; public record LineDto(String sku, int quantity) { }")
        myFixture.addClass("package shop.api; public class OrderFilter { private Status status; private int page; }")
        myFixture.addClass(
            """
            package shop.api;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.*;
            @RestController @RequestMapping("/orders")
            public class OrderController {
                @GetMapping("/{id}")
                public ResponseEntity<OrderDto> get(@PathVariable("id") String id, @RequestParam(value = "expand", required = false) boolean expand,
                                                    @RequestHeader("X-Tenant") String tenant, jakarta.servlet.http.HttpServletRequest request) { return null; }
                @PostMapping public void create(@RequestBody OrderDto order, @RequestParam("file") org.springframework.web.multipart.MultipartFile file) { }
                @GetMapping public java.util.List<OrderDto> search(OrderFilter filter, String q) { return null; }
            }
            """.trimIndent(),
        )
        yaml("application.yml", "spring:\n  application:\n    name: orders-service\nserver:\n  port: 8081\n  servlet:\n    context-path: /orders-api")
    }

    private fun handler(name: String) = myFixture.findClass("shop.api.OrderController").findMethodsByName(name, false).single()

    private fun assertParses(text: String, extension: String) {
        val file = PsiFileFactory.getInstance(project).createFileFromText("T.$extension", FileTypeManager.getInstance().getFileTypeByExtension(extension), text)
        val errors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
        assertTrue(errors.joinToString { "${it.errorDescription} at ${it.textOffset}" } + "\n" + text, errors.isEmpty())
    }

    fun `test the endpoint and the classes it uses are read from the backend`() {
        val x = ClientExtractor(project)
        val get = x.endpoint(handler("get"))!!
        assertEquals("GET", get.httpMethod)
        assertEquals("/orders/{id}", get.path)
        assertEquals("/orders-api", get.contextPath)
        assertEquals("orders-service", get.service)
        assertEquals("8081", get.port)
        assertEquals(
            listOf("PATH id id true", "QUERY expand expand false", "HEADER tenant X-Tenant true"),
            get.params.map { "${it.role} ${it.name} ${it.httpName} ${it.required}" },
        )
        assertEquals(TypeRef.dto("OrderDto"), get.returns) // ResponseEntity is not on the wire
        assertEquals(listOf("OrderDto", "Status", "LineDto"), x.dtoSpecs.map { it.name })
        val order = x.dtoSpecs.first()
        assertEquals(listOf("id", "created", "status", "lines", "parent"), order.fields.map { it.name })
        assertEquals("created_at", order.fields[1].jsonName)
        assertEquals(listOf("NEW", "PAID"), x.dtoSpecs[1].constants)

        val create = ClientExtractor(project).endpoint(handler("create"))!!
        assertEquals(listOf(ParamSpec.Role.BODY), create.params.map { it.role })
        assertNull(create.returns)
        assertTrue(create.notes.single(), create.notes.single().contains("multipart"))

        val search = ClientExtractor(project).endpoint(handler("search"))!!
        assertEquals(listOf(ParamSpec.Role.QUERY_MAP, ParamSpec.Role.QUERY), search.params.map { it.role })
        assertFalse(search.params[1].required)
    }

    fun `test the Java and Kotlin text`() {
        val x = ClientExtractor(project)
        val get = x.endpoint(handler("get"))!!
        val java = ClientText.Context(false, "bff.client", "bff.client.dto")
        val method = ClientText.method(get, "getOrder", "/orders/{id}", java)
        assertEquals(
            "    @GetMapping(\"/orders/{id}\")\n" +
                "    OrderDto getOrder(@PathVariable(\"id\") String id, @RequestParam(value = \"expand\", required = false) Boolean expand, " +
                "@RequestHeader(\"X-Tenant\") String tenant);\n",
            method,
        )
        val client = ClientText.client("OrdersServiceClient", "orders-service", "\${orders-service.url:http://localhost:8081}", "/orders-api", method, java)
        assertParses(client, "java")
        assertTrue(client, client.contains("import bff.client.dto.OrderDto;"))
        assertTrue(client, client.contains("@FeignClient(name = \"orders-service\", url = \"\${orders-service.url:http://localhost:8081}\", path = \"/orders-api\")"))

        val record = ClientText.dto(x.dtoSpecs[0], true, ClientText.Context(false, "bff.client.dto", "bff.client.dto"))
        assertParses(record, "java")
        assertTrue(record, record.contains("public record OrderDto("))
        assertTrue(record, record.contains("@JsonProperty(\"created_at\") LocalDate created"))
        assertTrue(record, record.contains("List<LineDto> lines"))
        val pojo = ClientText.dto(x.dtoSpecs[0], false, ClientText.Context(false, "bff.client.dto", "bff.client.dto"))
        assertParses(pojo, "java")
        assertTrue(pojo, pojo.contains("public OrderDto parent;"))
        assertParses(ClientText.dto(x.dtoSpecs[1], true, ClientText.Context(false, "bff.client.dto", "bff.client.dto")), "java")

        val kotlin = ClientText.Context(true, "bff.client", "bff.client.dto")
        val kMethod = ClientText.method(get, "getOrder", "/orders/{id}", kotlin)
        assertTrue(kMethod, kMethod.contains("fun getOrder(@PathVariable(\"id\") id: String, @RequestParam(value = \"expand\", required = false) expand: Boolean?, "))
        assertTrue(kMethod, kMethod.endsWith("): OrderDto\n"))
        val kClient = ClientText.client("OrdersServiceClient", "orders-service", "\${orders-service.url:http://localhost:8081}", "/orders-api", kMethod, kotlin)
        assertParses(kClient, "kt")
        assertTrue(kClient, kClient.contains("url = \"\\\${orders-service.url:http://localhost:8081}\"")) // a literal $ in Kotlin
        val kDto = ClientText.dto(x.dtoSpecs[0], true, ClientText.Context(true, "bff.client.dto", "bff.client.dto"))
        assertParses(kDto, "kt")
        assertTrue(kDto, kDto.contains("data class OrderDto("))
        assertTrue(kDto, kDto.contains("val lines: List<LineDto>? = null,"))
        assertParses(ClientText.dto(x.dtoSpecs[1], true, ClientText.Context(true, "bff.client.dto", "bff.client.dto")), "kt")
    }

    fun `test the method goes into the client of the service, below its path`() {
        myFixture.addClass(
            """
            package bff;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.*;
            @FeignClient(name = "orders-service", path = "/orders-api")
            public interface OrdersClient {
                @PostMapping("/orders") void create(@RequestBody shop.api.OrderDto order);
            }
            """.trimIndent(),
        )
        myFixture.addClass("package bff; @org.springframework.cloud.openfeign.FeignClient(name = \"payments\") public interface PaymentsClient { }")
        val x = ClientExtractor(project)
        val get = x.endpoint(handler("get"))!!
        val info = ClientTargets.analyze(project, get)
        val orders = info.clients.first()
        assertEquals("OrdersClient", orders.name)
        assertTrue(orders.callsService)
        assertNull(orders.existingMethod)
        assertFalse(info.clients.single { it.name == "PaymentsClient" }.callsService)
        assertEquals("bff", info.defaultPackage)
        assertFalse(info.kotlin)
        // creating the existing POST is noticed
        val create = ClientExtractor(project).endpoint(handler("create"))!!
        assertEquals("create", ClientTargets.analyze(project, create).clients.first().existingMethod)

        val plan = ClientTargets.plan(info, get, x.dtoSpecs, ClientTargets.Choice(orders, "", "bff", "bff.dto", "getOrder", false))
        assertTrue(plan.methodText, plan.methodText.contains("@GetMapping(\"/orders/{id}\")")) // below the client's /orders-api
        assertEquals(listOf("OrderDto", "Status", "LineDto"), plan.reused) // one repository: the classes are there
        assertEquals(emptyList<Any>(), plan.files)
        assertEquals("Adds getOrder to OrdersClient; uses the existing OrderDto, Status, LineDto", plan.summary)

        ClientTargets.write(plan).getOrThrow()
        val text = myFixture.findClass("bff.OrdersClient").containingFile.text
        assertTrue(text, text.contains("OrderDto getOrder(@PathVariable(\"id\") String id"))
        assertTrue(text, text.contains("import shop.api.OrderDto;"))
        assertTrue(text, text.contains("import org.springframework.web.bind.annotation.*;")) // the wildcard covers the rest
        assertFalse(text, text.contains("import org.springframework.web.bind.annotation.GetMapping;"))
        assertEquals(2, myFixture.findClass("bff.OrdersClient").methods.size)
    }

    fun `test the method goes into a Kotlin client`() {
        myFixture.configureByText(
            "OrdersClient.kt",
            """
            package bff

            import org.springframework.cloud.openfeign.FeignClient

            @FeignClient(name = "orders-service", url = "\${'$'}{orders.url}")
            interface OrdersClient {
                fun ping(): String
            }
            """.trimIndent(),
        )
        val x = ClientExtractor(project)
        val get = x.endpoint(handler("get"))!!
        val info = ClientTargets.analyze(project, get)
        val orders = info.clients.single { it.name == "OrdersClient" }
        assertTrue(orders.kotlin)
        val plan = ClientTargets.plan(info, get, x.dtoSpecs, ClientTargets.Choice(orders, "", "bff", "bff.dto", "getOrder", false))
        assertTrue(plan.methodText, plan.methodText.contains("@GetMapping(\"/orders-api/orders/{id}\")")) // no client path: the full path
        ClientTargets.write(plan).getOrThrow()
        val text = myFixture.file.text
        assertParses(text, "kt")
        assertTrue(text, text.contains("fun getOrder("))
        assertTrue(text, text.contains("@PathVariable(\"id\") id: String,"))
        assertTrue(text, text.contains("): OrderDto"))
        assertTrue(text, text.contains("import org.springframework.web.bind.annotation.GetMapping\n"))
        assertTrue(text, text.contains("import shop.api.OrderDto\n"))
        assertTrue(text, text.trimEnd().endsWith("}"))
    }

    fun `test a new client is written with its address`() {
        myFixture.addClass("package org.springframework.boot.autoconfigure; public @interface SpringBootApplication { }")
        myFixture.addClass("package bff; @org.springframework.boot.autoconfigure.SpringBootApplication public class BffApplication { }")
        val x = ClientExtractor(project)
        val get = x.endpoint(handler("get"))!!
        val info = ClientTargets.analyze(project, get)
        assertEquals("bff.client", info.defaultPackage)
        val plan = ClientTargets.plan(info, get, x.dtoSpecs, ClientTargets.Choice(null, "OrdersServiceClient", "bff.client", "bff.client.dto", "getOrder", true))
        assertEquals(listOf("OrdersServiceClient.java"), plan.files.map { it.second })
        ClientTargets.write(plan).getOrThrow()
        val created = myFixture.findClass("bff.client.OrdersServiceClient")
        assertEquals("getOrder", created.methods.single().name)
        assertTrue(created.containingFile.text.contains("url = \"\${orders-service.url:http://localhost:8081}\", path = \"/orders-api\""))
    }
}
