package dev.tendril.config

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiPolyVariantReference
import dev.tendril.SpringTestCase
import org.jetbrains.yaml.psi.YAMLKeyValue

class ConfigNavigationTest : SpringTestCase() {

    private fun feign(annotation: String) = myFixture.addClass(
        """
        package app;
        import org.springframework.cloud.openfeign.FeignClient;
        $annotation
        public interface InventoryClient {}
        """.trimIndent(),
    )

    /** What the reference under the caret resolves to. */
    private fun resolveAtCaret(source: String): List<String> {
        myFixture.configureByText("Use.java", source)
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion() as PsiPolyVariantReference
        return reference.multiResolve(false).map { (it.element as? YAMLKeyValue)?.let { kv -> ConfigNavigation.yamlPath(kv) } ?: it.element.toString() }
    }

    // The test IDE is IntelliJ IDEA Ultimate, whose own Spring plugin registers a reference on @Value literals with a
    // higher priority and so hides ours. In the IDE this plugin is meant for, that plugin is inactive, so the lookup
    // behind our reference is tested directly.
    fun `test a value placeholder is looked up in the yml`() {
        yaml("application.yml", "app:\n  greeting: hi")
        myFixture.configureByText(
            "Use.java",
            """
            package app;
            import org.springframework.beans.factory.annotation.Value;
            public class A { @Value("${'$'}{app.greeting}") String g; }
            """.trimIndent(),
        )
        val targets = ConfigNavigation.configElements(project, "app.greeting", myFixture.file)
        assertEquals(listOf("app.greeting"), targets.filterIsInstance<YAMLKeyValue>().map { ConfigNavigation.yamlPath(it) })
    }

    fun `test the shorthand value of configuration properties is the prefix`() {
        myFixture.addClass(
            """
            package app;
            import org.springframework.boot.context.properties.ConfigurationProperties;
            @ConfigurationProperties("shorthand")
            public class Short { private String apiKey; }
            """.trimIndent(),
        )
        assertTrue(ConfigMetadata.ownScan(project).properties.any { it.name == "shorthand.api-key" })
    }

    fun `test a feign url placeholder resolves to the yml key in every profile`() {
        yaml("application.yml", "clients:\n  inventory:\n    url: http://default")
        yaml("application-test.yml", "clients:\n  inventory:\n    url: http://test")
        val targets = resolveAtCaret(
            """
            package app;
            import org.springframework.cloud.openfeign.FeignClient;
            @FeignClient(name = "inventory", url = "${'$'}{clients.inven<caret>tory.url}")
            public interface C {}
            """.trimIndent(),
        )
        assertEquals(listOf("clients.inventory.url", "clients.inventory.url"), targets)
    }

    fun `test a feign service name resolves to the config that holds its address`() {
        yaml("application.yml", "pricing-service:\n  url: https://pricing.example.com\nunrelated:\n  key: 1")
        val targets = resolveAtCaret(
            """
            package app;
            import org.springframework.cloud.openfeign.FeignClient;
            @FeignClient("pricing-ser<caret>vice")
            public interface C {}
            """.trimIndent(),
        )
        assertEquals(listOf("pricing-service.url"), targets)
    }

    fun `test a feign service name without config resolves to nothing`() {
        yaml("application.yml", "unrelated:\n  key: 1")
        assertTrue(
            resolveAtCaret(
                """
                package app;
                import org.springframework.cloud.openfeign.FeignClient;
                @FeignClient("legacy-led<caret>ger")
                public interface C {}
                """.trimIndent(),
            ).isEmpty(),
        )
    }

    fun `test a yml key leads to the feign client that reads it`() {
        feign("""@FeignClient(name = "inventory", url = "${'$'}{clients.inventory.url}")""")
        val file = yaml("application.yml", "clients:\n  inventory:\n    url: http://x")
        val targets = ConfigNavigation.codeTargets(project, "clients.inventory.url", file)
        assertEquals(listOf("InventoryClient"), targets.filterIsInstance<PsiClass>().map { it.name })
    }

    fun `test the address key of a service name leads to its client`() {
        myFixture.addClass(
            """
            package app;
            import org.springframework.cloud.openfeign.FeignClient;
            @FeignClient("pricing-service")
            public interface PricingClient {}
            """.trimIndent(),
        )
        val file = yaml("application.yml", "pricing-service:\n  url: https://p")
        val targets = ConfigNavigation.codeTargets(project, "pricing-service.url", file)
        assertEquals(listOf("PricingClient"), targets.filterIsInstance<PsiClass>().map { it.name })
    }

    fun `test a configuration properties field is linked to its key in both directions`() {
        myFixture.addClass(
            """
            package app;
            import org.springframework.boot.context.properties.ConfigurationProperties;
            @ConfigurationProperties(prefix = "app")
            public class AppProperties { private String apiKey; private Security security = new Security();
                public static class Security { private String jwtSecret; } }
            """.trimIndent(),
        )
        val file = yaml("application.yml", "app:\n  api-key: k\n  security:\n    jwt-secret: s")
        val scan = ConfigMetadata.ownScan(project)
        assertTrue(scan.properties.map { it.name }.containsAll(listOf("app.api-key", "app.security.jwt-secret")))

        val fields = ConfigNavigation.codeTargets(project, "app.security.jwt-secret", file)
        assertEquals(listOf("jwtSecret"), fields.filterIsInstance<com.intellij.psi.PsiField>().map { it.name })

        val field = scan.byKey[MetadataIndex.normalize("app.api-key")]!!.single()
        val keys = ConfigNavigation.configElements(project, "app.api-key", field)
        assertEquals(listOf("app.api-key"), keys.filterIsInstance<YAMLKeyValue>().map { ConfigNavigation.yamlPath(it) })
    }

    fun `test keys read by the code are not reported as unknown`() {
        feign("""@FeignClient(name = "inventory", url = "${'$'}{clients.inventory.url}")""")
        val scan = ConfigMetadata.ownScan(project)
        assertTrue(scan.properties.any { it.name == "clients.inventory.url" })
    }
}
