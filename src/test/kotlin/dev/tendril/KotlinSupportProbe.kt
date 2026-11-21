package dev.tendril

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiParameter
import dev.tendril.beans.BeanModels
import dev.tendril.beans.AmbiguousBeanInspection
import dev.tendril.beans.CircularDependencyInspection
import dev.tendril.beans.FieldInjectionInspection
import dev.tendril.beans.NoBeanFoundInspection
import dev.tendril.config.ConfigMetadata
import dev.tendril.config.ConfigNavigation
import dev.tendril.config.MissingRequiredPropertyInspection
import dev.tendril.endpoints.EndpointRequests
import dev.tendril.endpoints.EndpointScanner
import dev.tendril.feign.FeignResolver
import dev.tendril.feign.FeignScanner
import java.io.File
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt

/**
 * Measures what the plugin does with Kotlin sources. It builds the same project as the Java playground in Kotlin and
 * records, per feature, whether it works. The table is printed and written to build/kotlin-support.txt.
 * It only fails when the set-up itself breaks; a feature that does not work is a finding, not a failure.
 */
class KotlinSupportProbe : KotlinSpringTestCase() {

    private val results = mutableListOf<String>()

    private fun probe(feature: String, block: () -> Pair<String, String>) {
        val line = try {
            val (verdict, detail) = block()
            "$verdict | $feature | $detail"
        } catch (e: Throwable) {
            "ERROR | $feature | ${e.javaClass.simpleName}: ${e.message?.take(120)} at ${e.stackTrace.filter { it.className.startsWith("dev.tendril") || it.className.contains("Beans") }.take(6)}"
        }
        results += line
        println("KOTLIN-RESULT $line")
    }

    private fun kotlinProject() {
        kt(
            "BrandController",
            """
            import org.springframework.web.bind.annotation.*
            @RestController
            @RequestMapping("/api/brands")
            class BrandController {
                @GetMapping fun list(): String = ""
                @GetMapping("/{id}") fun get(@PathVariable id: Long): String = ""
                @PostMapping(path = ["/x"]) fun create(@RequestBody body: String): String = ""
                @RequestMapping(value = ["/old"], method = [RequestMethod.GET]) fun old(): String = ""
                @GetMapping("/search") fun search(@RequestParam("q") q: String, @RequestParam(name = "page") page: Int): String = ""
            }
            """,
        )
        kt(
            "Beans",
            """
            import org.springframework.stereotype.*
            import org.springframework.context.annotation.*
            import org.springframework.beans.factory.annotation.Autowired
            interface Gateway
            @Service class Stripe : Gateway
            @Service class Paypal : Gateway
            @Component class Consumer(private val gateway: Gateway)
            @Service class Dep
            @Component class WithField { @Autowired lateinit var dep: Dep }
            class Clock
            @Configuration class Config { @Bean fun clock(): Clock = Clock() }
            @Component class UsesClock(private val clock: Clock)
            class Sink
            @Component class NeedsSink(private val sink: Sink)
            @Component class CycleA(private val b: CycleB)
            @Component class CycleB(private val a: CycleA)
            """,
        )
        kt(
            "Props",
            """
            import org.springframework.boot.context.properties.ConfigurationProperties
            import org.springframework.beans.factory.annotation.Value
            import org.springframework.stereotype.Service
            import org.springframework.validation.annotation.Validated
            import jakarta.validation.constraints.NotBlank
            @ConfigurationProperties(prefix = "app")
            data class AppProps(val apiKey: String, val security: Security) { data class Security(val jwtSecret: String) }
            @ConfigurationProperties("shorthand")
            class ShortProps { var apiKey: String = "" }
            @Validated @ConfigurationProperties(prefix = "broken.demo")
            class BrokenProps { @NotBlank lateinit var requiredToken: String }
            @Service class Reader(@Value("§{app.greeting}") val greeting: String, @Value("§{app.ttl:60}") val ttl: Int)
            """,
        )
        kt(
            "Clients",
            """
            import org.springframework.cloud.openfeign.FeignClient
            import org.springframework.web.bind.annotation.*
            @FeignClient(name = "inventory", url = "§{clients.inventory.url}", path = "/v1")
            interface InventoryClient {
                @GetMapping("/stock/{sku}") fun stock(@PathVariable("sku") sku: String): String
                @PostMapping("/res") fun reserve(@RequestBody body: String): String
            }
            @FeignClient("pricing-service")
            interface PricingClient { @GetMapping("/p") fun price(): String }
            """,
        )
        yaml(
            "application.yml",
            """
            clients:
              inventory:
                url: http://localhost:9001
            pricing-service:
              url: https://pricing.example.com
            app:
              greeting: hi
              api-key: k
              security:
                jwt-secret: s
            shorthand:
              api-key: k
            """,
        )
    }

    // In Kotlin source a placeholder is written \${...}; § stands for that backslash-escaped dollar in the samples above.
    private fun kt(name: String, body: String) =
        myFixture.addFileToProject("app/$name.kt", "package app\n" + body.trimIndent().replace("§", "\\" + "$"))

    private fun kinds(elements: Collection<PsiElement>) = elements.map { it.javaClass.simpleName }.distinct()

    @OptIn(KaAllowAnalysisOnEdt::class)
    fun `test measure kotlin support`() {
        kotlinProject()

        probe("endpoints: scanner finds Kotlin controllers") {
            val found = EndpointScanner.scan(project).map { "${it.httpMethod} ${it.path}" }.sorted()
            val expected = listOf("GET /api/brands", "GET /api/brands/old", "GET /api/brands/search", "GET /api/brands/{id}", "POST /api/brands/x")
            (if (found == expected) "OK" else if (found.isEmpty()) "NO" else "PARTIAL") to "found=$found"
        }
        probe("endpoints: request text (query params, path variables)") {
            val e = EndpointScanner.scan(project).firstOrNull { it.path.endsWith("/search") }
            val http = e?.let { EndpointRequests.http(project, it) }.orEmpty()
            (if ("?q=value&page=value" in http) "OK" else "NO") to http.lines().getOrNull(1).orEmpty()
        }
        probe("feign: scanner finds Kotlin clients and their calls") {
            val defs = FeignScanner.scan(project)
            (if (defs.size == 2 && defs.sumOf { it.calls.size } == 3) "OK" else if (defs.isEmpty()) "NO" else "PARTIAL") to
                defs.joinToString { "${it.className}(${it.name},${it.url},${it.path}) calls=${it.calls.map { c -> c.httpMethod + c.subPath }}" }
        }
        probe("feign: address resolution") {
            val infos = FeignScanner.scan(project).map { FeignResolver.resolve(project, it, null) }
            val urls = infos.associate { it.def.className to it.resolution.url }
            (if (urls["InventoryClient"] == "http://localhost:9001" && urls["PricingClient"] == "https://pricing.example.com") "OK" else "NO") to urls.toString()
        }
        probe("config: @ConfigurationProperties data class (constructor properties)") {
            val names = ConfigMetadata.ownScan(project).properties.map { it.name }
            val ok = listOf("app.api-key", "app.security.jwt-secret").all { it in names }
            (if (ok) "OK" else if (names.any { it.startsWith("app.") }) "PARTIAL" else "NO") to names.filter { it.startsWith("app.") }.toString()
        }
        probe("config: @ConfigurationProperties(\"prefix\") shorthand with var property") {
            val names = ConfigMetadata.ownScan(project).properties.map { it.name }
            (if ("shorthand.api-key" in names) "OK" else "NO") to names.filter { it.startsWith("shorthand") }.toString()
        }
        probe("config: @Value on constructor parameters (with \${} escapes)") {
            val keys = ConfigMetadata.ownScan(project).sources.map { it.key }
            (if ("app.greeting" in keys && "app.ttl" in keys) "OK" else "NO") to keys.filter { it.startsWith("app.g") || it.startsWith("app.t") }.toString()
        }
        probe("config: feign url placeholder counts as a consumed key") {
            val keys = ConfigMetadata.ownScan(project).sources.map { it.key }
            (if ("clients.inventory.url" in keys) "OK" else "NO") to keys.filter { it.startsWith("clients") }.toString()
        }
        probe("navigation yml -> Kotlin: targets exist for @Value / properties / feign") {
            val file = myFixture.findFileInTempDir("application.yml")?.let { com.intellij.psi.PsiManager.getInstance(project).findFile(it) } ?: error("no yml")
            val byKey = listOf("app.greeting", "app.security.jwt-secret", "clients.inventory.url", "pricing-service.url").associateWith {
                ConfigNavigation.codeTargets(project, it, file)
            }
            val detail = byKey.entries.joinToString { (k, v) -> "$k->${kinds(v)}/${v.map { t -> t.navigationElement.javaClass.simpleName }.distinct()}" }
            val ok = byKey.values.all { it.isNotEmpty() }
            val landsInKotlin = byKey.values.flatten().all { it.navigationElement.containingFile?.name?.endsWith(".kt") == true }
            (if (ok && landsInKotlin) "OK" else if (ok) "PARTIAL" else "NO") to detail
        }
        probe("beans: model sees Kotlin beans, @Bean functions and injection points") {
            val model = BeanModels.get(project)
            val names = model.beans.map { it.type.name }
            val expected = listOf("Stripe", "Paypal", "Consumer", "Dep", "WithField", "Clock", "UsesClock", "CycleA", "CycleB")
            val missing = expected.filter { it !in names }
            val gateway = com.intellij.psi.JavaPsiFacade.getInstance(project).findClass("app.Gateway", com.intellij.psi.search.GlobalSearchScope.projectScope(project))!!
            (if (missing.isEmpty() && model.candidates(gateway).size == 2) "OK" else if (names.isNotEmpty()) "PARTIAL" else "NO") to
                "beans=$names missing=$missing gatewayCandidates=${model.candidates(gateway).size} points=${model.points.size}"
        }
        probe("beans: constructor and lateinit-field injection are injection points") {
            val points = BeanModels.get(project).points
            val kinds = points.map { it.kind.name }.groupingBy { it }.eachCount()
            (if (points.size >= 6) "OK" else if (points.isNotEmpty()) "PARTIAL" else "NO") to kinds.toString()
        }

        probe("beans: @Autowired lateinit var is a field injection point") {
            val cls = com.intellij.psi.JavaPsiFacade.getInstance(project).findClass("app.WithField", com.intellij.psi.search.GlobalSearchScope.projectScope(project))!!
            val fieldPoints = BeanModels.get(project).points.filter { it.element is PsiField }
            val fields = cls.fields.map { f -> "${f.name}:${f.type.presentableText} ann=${f.annotations.map { it.qualifiedName }} mods=${f.modifierList?.text}" }
            val methods = cls.methods.map { m -> "${m.name} ann=${m.annotations.map { it.qualifiedName }}" }
            (if (fieldPoints.isNotEmpty()) "OK" else "NO") to "fieldPoints=${fieldPoints.size} fields=$fields methods=$methods"
        }

        // Each inspection is enabled on its own and only its own message counts, so the checks do not feed each other.
        fun inspect(file: String, inspection: com.intellij.codeInspection.InspectionProfileEntry, message: String): List<String> {
            myFixture.enableInspections(inspection)
            myFixture.configureFromTempProjectFile("app/$file")
            return myFixture.doHighlighting().mapNotNull { it.description }.filter { it.startsWith(message) }
        }
        fun inspection(feature: String, file: String, tool: com.intellij.codeInspection.InspectionProfileEntry, message: String, expected: Int) =
            probe("inspection in a Kotlin file: $feature") {
                inspect(file, tool, message).let { (if (it.size == expected) "OK" else if (it.isNotEmpty()) "PARTIAL" else "NO") to "expected $expected: $it" }
            }
        inspection("ambiguous bean", "Beans.kt", AmbiguousBeanInspection(), "Multiple beans of type 'Gateway'", 1)
        inspection("no bean found", "Beans.kt", NoBeanFoundInspection(), "No Spring bean of type 'Sink'", 1)
        inspection("circular dependency", "Beans.kt", CircularDependencyInspection(), "Circular bean dependency", 2)
        inspection("field injection", "Beans.kt", FieldInjectionInspection(), "Field injection", 1)
        inspection("required property missing", "Props.kt", MissingRequiredPropertyInspection(), "Required property 'broken.demo.required-token'", 1)
        probe("inspection in a Kotlin file: constructor-bound configuration properties are not reported as missing beans") {
            inspect("Props.kt", NoBeanFoundInspection(), "No Spring bean").let { (if (it.isEmpty()) "OK" else "NO") to it.toString() }
        }

        fun gutters(file: String, tooltipPart: String): List<String> {
            myFixture.configureFromTempProjectFile("app/$file")
            // the daemon runs in the background in the IDE; the test fixture calls it on the EDT, where Kotlin forbids analysis
            return allowAnalysisOnEdt { myFixture.findAllGutters() }.mapNotNull { it.tooltipText }.filter { it.contains(tooltipPart) }
        }
        // The bean markers resolve annotations, which Kotlin only allows off the EDT. The daemon does that in the IDE;
        // here the providers are called the same way, from a background read action.
        fun backgroundMarkers(file: String, provider: com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider, part: String): List<String> {
            myFixture.configureFromTempProjectFile("app/$file")
            val psiFile = myFixture.file
            return com.intellij.openapi.application.ReadAction.nonBlocking<List<String>> {
                val leaves = com.intellij.psi.util.PsiTreeUtil.collectElements(psiFile) { it.firstChild == null }.toList()
                val found = mutableListOf<com.intellij.codeInsight.daemon.LineMarkerInfo<*>>()
                provider.collectSlowLineMarkers(leaves, found)
                found.mapNotNull { it.lineMarkerTooltip }.filter { it.contains(part) }
            }.executeSynchronously()
        }
        probe("gutter in a Kotlin file: constructor parameter -> bean") {
            backgroundMarkers("Beans.kt", dev.tendril.beans.BeanLineMarkerProvider(), "Navigate to Spring bean").let { (if (it.isNotEmpty()) "OK" else "NO") to it.toString() }
        }
        probe("gutter in a Kotlin file: bean -> where it is injected") {
            backgroundMarkers("Beans.kt", dev.tendril.beans.BeanUsagesLineMarkerProvider(), "Injected into").let { (if (it.isNotEmpty()) "OK" else "NO") to it.toString() }
        }
        probe("gutter in a Kotlin file: code -> configuration key") {
            gutters("Props.kt", "Navigate to configuration property").let { (if (it.isNotEmpty()) "OK" else "NO") to it.toString() }
        }
        probe("gutter in a Kotlin file: Feign call arrow") {
            gutters("Clients.kt", "Send this Feign call").let { (if (it.size == 3) "OK" else if (it.isNotEmpty()) "PARTIAL" else "NO") to "expected 3: $it" }
        }
        probe("reference in a Kotlin file: Ctrl+Click on \${key} to the yml") {
            myFixture.configureFromTempProjectFile("app/Props.kt")
            val offset = myFixture.editor.document.text.indexOf("app.greeting") + 3
            // Not findReferenceAt: in the Ultimate test IDE the Spring plugin's own reference hides ours there.
            val host = myFixture.file.findElementAt(offset)?.let { com.intellij.psi.util.PsiTreeUtil.getParentOfType(it, com.intellij.psi.PsiLanguageInjectionHost::class.java) }
            val ours = host?.references.orEmpty().filterIsInstance<com.intellij.psi.PsiPolyVariantReference>().filter { it.javaClass.simpleName == "ConfigKeyReference" }
            val resolved = ours.sumOf { it.multiResolve(false).size }
            (if (resolved > 0) "OK" else "NO") to "references=${host?.references.orEmpty().map { it.javaClass.simpleName }} resolved=$resolved"
        }
        probe("reference in a Kotlin file: Ctrl+Click on a Feign service name to its address") {
            myFixture.configureFromTempProjectFile("app/Clients.kt")
            val offset = myFixture.editor.document.text.indexOf("pricing-service") + 3
            val host = myFixture.file.findElementAt(offset)?.let { com.intellij.psi.util.PsiTreeUtil.getParentOfType(it, com.intellij.psi.PsiLanguageInjectionHost::class.java) }
            val resolved = host?.references.orEmpty().filterIsInstance<com.intellij.psi.PsiPolyVariantReference>().sumOf { it.multiResolve(false).size }
            (if (resolved > 0) "OK" else "NO") to "resolved=$resolved"
        }
        probe("completion in a Kotlin file: config keys inside a placeholder") {
            // in Kotlin source a placeholder is written \${...}; § stands for that escaped dollar here
            val source = listOf(
                "package app",
                "import org.springframework.beans.factory.annotation.Value",
                "class C(@Value(\"§{app.gre<caret>\") val g: String)",
            ).joinToString("\n").replace("§", "\\" + "$")
            myFixture.configureByText("Completion.kt", source)
            val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
            // a single candidate is inserted without showing a list
            val inserted = myFixture.editor.document.text.contains("app.greeting}")
            (if ("app.greeting" in items || inserted) "OK" else "NO") to "items=${items.take(8)} inserted=$inserted"
        }

        File("build/kotlin-support.txt").apply { parentFile.mkdirs() }.writeText(results.joinToString("\n"))

        // Every feature is expected to work from Kotlin: anything that does not is a regression.
        val regressions = results.filter { !it.startsWith("OK") }
        assertTrue("Kotlin support regressed:\n" + regressions.joinToString("\n"), regressions.isEmpty())
    }
}
