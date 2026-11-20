package dev.tendril.feign

import dev.tendril.SpringTestCase
import dev.tendril.config.TendrilSettings

class FeignResolverTest : SpringTestCase() {

    private fun client(annotation: String, name: String = "ApiClient") = myFixture.addClass(
        """
        package app;
        import org.springframework.cloud.openfeign.FeignClient;
        import org.springframework.web.bind.annotation.*;
        $annotation
        public interface $name {
            @GetMapping("/stock/{sku}") String stock(@PathVariable("sku") String sku);
        }
        """.trimIndent(),
    )

    private fun resolve(profile: String? = null): FeignClientInfo =
        FeignResolver.resolve(project, FeignScanner.scan(project).single(), profile)

    // ---- the url attribute ----

    fun `test the url attribute is resolved from the config`() {
        client("""@FeignClient(name = "inventory", url = "${'$'}{clients.inventory.url}", path = "/v1")""")
        yaml("application.yml", "clients:\n  inventory:\n    url: http://localhost:9001/")
        val info = resolve()
        assertEquals("http://localhost:9001", info.resolution.url)
        assertTrue(info.resolution.source, info.resolution.source.startsWith("url attribute"))
        assertEquals("/v1", info.path)
    }

    fun `test a profile overrides the default value`() {
        client("""@FeignClient(name = "inventory", url = "${'$'}{clients.inventory.url}")""")
        yaml("application.yml", "clients:\n  inventory:\n    url: http://default")
        yaml("application-test.yml", "clients:\n  inventory:\n    url: https://test.example.com")
        assertEquals("http://default", resolve(null).resolution.url)
        assertEquals("https://test.example.com", resolve("test").resolution.url)
        // a profile that does not define the key falls back to the default
        assertEquals("http://default", resolve("other").resolution.url)
    }

    fun `test a profile document inside application yml counts`() {
        client("""@FeignClient(name = "inventory", url = "${'$'}{clients.inventory.url}")""")
        yaml(
            "application.yml",
            """
            clients:
              inventory:
                url: http://default
            ---
            spring:
              config:
                activate:
                  on-profile: dev
            clients:
              inventory:
                url: http://dev-host
            """,
        )
        assertEquals("http://dev-host", resolve("dev").resolution.url)
        assertEquals("http://default", resolve(null).resolution.url)
    }

    fun `test a placeholder default is used when the key is missing`() {
        client("""@FeignClient(name = "notify", url = "${'$'}{clients.notify.url:https://notify.default.example.com}")""")
        assertEquals("https://notify.default.example.com", resolve().resolution.url)
    }

    fun `test an undefined placeholder without default is reported`() {
        client("""@FeignClient(name = "inventory", url = "${'$'}{clients.inventory.url}")""")
        val info = resolve()
        assertNull(info.resolution.url)
        assertEquals(listOf("clients.inventory.url"), info.resolution.unresolved)
    }

    fun `test an address without a scheme gets http`() {
        client("""@FeignClient(name = "inventory", url = "${'$'}{u}")""")
        yaml("application.properties", "u=inventory.internal:8443")
        assertEquals("http://inventory.internal:8443", resolve().resolution.url)
    }

    fun `test a placeholder nested in the value is resolved too`() {
        client("""@FeignClient(name = "inventory", url = "${'$'}{inventory.base}")""")
        yaml("application.yml", "host: example.com\ninventory:\n  base: https://${'$'}{host}/api")
        assertEquals("https://example.com/api", resolve().resolution.url)
    }

    // ---- name only ----

    fun `test the well known openfeign config key`() {
        client("""@FeignClient(name = "credit-service")""")
        yaml("application.yml", "spring:\n  cloud:\n    openfeign:\n      client:\n        config:\n          credit-service:\n            url: https://credit.example.com")
        val r = resolve().resolution
        assertEquals("https://credit.example.com", r.url)
        assertTrue(r.source, r.source.contains("spring.cloud.openfeign.client.config.credit-service.url"))
    }

    fun `test a name url key`() {
        client("""@FeignClient("pricing-service")""")
        yaml("application.yml", "pricing-service:\n  url: https://pricing.example.com")
        assertEquals("https://pricing.example.com", resolve().resolution.url)
    }

    fun `test simple discovery uri as a yaml list`() {
        client("""@FeignClient(name = "catalog-service")""")
        yaml(
            "application.yml",
            """
            spring:
              cloud:
                discovery:
                  client:
                    simple:
                      instances:
                        catalog-service:
                          - uri: https://catalog.example.com
            """,
        )
        assertEquals("https://catalog.example.com", resolve().resolution.url)
    }

    fun `test simple discovery uri in a properties file`() {
        client("""@FeignClient(name = "catalog-service")""")
        yaml("application.properties", "spring.cloud.discovery.client.simple.instances.catalog-service[0].uri=https://catalog.example.com")
        assertEquals("https://catalog.example.com", resolve().resolution.url)
    }

    fun `test simple discovery host port and secure`() {
        client("""@FeignClient(name = "billing-service")""")
        yaml(
            "application.yml",
            """
            spring:
              cloud:
                discovery:
                  client:
                    simple:
                      instances:
                        billing-service:
                          - host: billing.example.com
                            port: 8443
                            secure: true
            """,
        )
        assertEquals("https://billing.example.com:8443", resolve().resolution.url)
    }

    fun `test the first server of a ribbon list`() {
        client("""@FeignClient(name = "audit-service")""")
        yaml("application.yml", "audit-service:\n  ribbon:\n    listOfServers: audit1.example.com:8443,audit2.example.com:8443")
        assertEquals("http://audit1.example.com:8443", resolve().resolution.url)
    }

    fun `test a key that mentions the service is found by guessing`() {
        client("""@FeignClient("shipping-service")""")
        yaml("application.yml", "integrations:\n  shipping-service:\n    endpoint: https://shipping.example.com")
        val r = resolve().resolution
        assertEquals("https://shipping.example.com", r.url)
        assertTrue(r.source, r.source.startsWith("guessed"))
    }

    fun `test the guess ignores keys that do not hold an http url`() {
        client("""@FeignClient("shipping-service")""")
        yaml("application.yml", "shipping-service:\n  retries: 3\n  mode: fast")
        assertNull(resolve().resolution.url)
    }

    fun `test the service name can come from a placeholder`() {
        client("""@FeignClient(name = "${'$'}{feign.scoring.service.name}")""")
        yaml("application.yml", "feign:\n  scoring:\n    service:\n      name: scoring-service\n      url: https://scoring.example.com")
        val info = resolve()
        assertEquals("scoring-service", info.name)
        assertEquals("https://scoring.example.com", info.resolution.url)
    }

    fun `test a name with nothing configured is unresolved`() {
        client("""@FeignClient(name = "legacy-ledger")""")
        val r = resolve().resolution
        assertNull(r.url)
        assertTrue(r.source, r.source.contains("discovery"))
    }

    fun `test the address table in the settings wins`() {
        client("""@FeignClient(name = "pricing-service")""")
        yaml("application.yml", "pricing-service:\n  url: https://from-config.example.com")
        TendrilSettings.getInstance(project).state.serviceUrls["pricing-service"] = "https://from-table.example.com/"
        val r = resolve().resolution
        assertEquals("https://from-table.example.com", r.url)
        assertTrue(r.source, r.source.contains("address table"))
    }

    fun `test an empty url falls back to the name`() {
        client("""@FeignClient(name = "pricing-service", url = "${'$'}{pricing.url:}")""")
        yaml("application.yml", "pricing-service:\n  url: https://pricing.example.com")
        assertEquals("https://pricing.example.com", resolve().resolution.url)
    }

    // ---- scanning and requests ----

    fun `test the scanner reads attributes and inherited methods`() {
        myFixture.addClass(
            """
            package app;
            import org.springframework.web.bind.annotation.*;
            public interface Api { @PostMapping("/items") String create(@RequestBody String body); }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package app;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.*;
            @FeignClient(name = "items", url = "http://items", path = "/v2")
            public interface ItemsClient extends Api { @GetMapping("/items/{id}") String get(@PathVariable("id") String id); }
            """.trimIndent(),
        )
        val def = FeignScanner.scan(project).single()
        assertEquals("items", def.name)
        assertEquals("http://items", def.url)
        assertEquals("/v2", def.path)
        assertEquals(listOf("GET /items/{id}", "POST /items"), def.calls.map { "${it.httpMethod} ${it.subPath}" }.sorted())
    }

    fun `test the request text uses the resolved address and path variables`() {
        client("""@FeignClient(name = "inventory", url = "http://localhost:9001", path = "/v1")""")
        val info = resolve()
        val call = info.def.calls.single()
        assertEquals("http://localhost:9001/v1/stock/{sku}", FeignRequests.url(info, call))
        val http = FeignRequests.http(info, call)
        assertTrue(http, http.contains("GET http://localhost:9001/v1/stock/{{sku}}"))
        assertTrue(http, http.contains("# Authorization: Bearer {{token}}"))
        assertTrue(FeignRequests.curl(info, call).contains("-X GET"))
    }

    fun `test an unresolved address keeps the request editable`() {
        client("""@FeignClient(name = "legacy-ledger")""")
        val info = resolve()
        val http = FeignRequests.http(info, info.def.calls.single())
        assertTrue(http, http.contains("GET {{baseUrl}}/stock/{{sku}}"))
        assertTrue(http, http.contains("No address resolved"))
    }
}
