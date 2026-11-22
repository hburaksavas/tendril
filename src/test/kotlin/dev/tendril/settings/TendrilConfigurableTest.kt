package dev.tendril.settings

import com.intellij.openapi.util.Disposer
import dev.tendril.SpringTestCase
import dev.tendril.config.ConfigNavigation
import dev.tendril.config.TendrilSettings
import dev.tendril.feign.FeignResolver
import dev.tendril.feign.FeignScanner

class TendrilConfigurableTest : SpringTestCase() {

    private lateinit var configurable: TendrilConfigurable

    override fun setUp() {
        super.setUp()
        configurable = TendrilConfigurable(project)
        configurable.createComponent()
        // the shared test project keeps settings between tests: start clean
        TendrilSettings.getInstance(project).state.apply {
            ignoredKeys.clear()
            serviceUrls.clear()
            nameKeyTemplates.clear()
        }
        configurable.reset()
    }

    override fun tearDown() {
        try {
            configurable.disposeUIResources()
            TendrilSettings.getInstance(project).state.apply {
                ignoredKeys.clear()
                serviceUrls.clear()
                nameKeyTemplates.clear()
            }
        } finally {
            super.tearDown()
        }
    }

    private val state get() = TendrilSettings.getInstance(project).state

    fun `test the page builds and nothing is modified at first`() {
        assertEquals("Tendril", configurable.displayName)
        assertFalse(configurable.isModified)
    }

    fun `test applying ignored keys stores them and makes the inspection skip them`() {
        configurable.ignoredKeys.add("jwt")
        assertTrue(configurable.isModified)
        configurable.apply()

        assertEquals(listOf("jwt"), state.ignoredKeys)
        assertTrue(TendrilSettings.getInstance(project).isIgnored("jwt.secret"))
        assertFalse(TendrilSettings.getInstance(project).isIgnored("other.key"))
        assertFalse(configurable.isModified)
    }

    fun `test reset throws away what was not applied`() {
        configurable.ignoredKeys.add("temporary")
        configurable.templates.add("urls.{name}.base")
        configurable.addresses.addRow(AddressRow("svc", "http://svc"))
        configurable.reset()

        assertTrue(configurable.ignoredKeys.isEmpty)
        assertTrue(configurable.templates.isEmpty)
        assertEquals(0, configurable.addresses.rowCount)
        assertFalse(configurable.isModified)
    }

    fun `test reset loads what is stored`() {
        state.ignoredKeys.add("stored")
        state.serviceUrls["credit-service"] = "https://credit"
        configurable.reset()

        assertEquals(listOf("stored"), configurable.ignoredKeys.items)
        assertEquals("credit-service", configurable.addresses.items.single().service)
        assertFalse(configurable.isModified)
    }

    fun `test address rows are trimmed and incomplete rows are dropped`() {
        configurable.addresses.addRow(AddressRow("  credit-service ", " https://credit.example.com "))
        configurable.addresses.addRow(AddressRow("", "http://nameless"))
        configurable.addresses.addRow(AddressRow("no-url", ""))
        // a user edits through setValue, which trims
        configurable.addresses.columnInfos[0].let { column ->
            @Suppress("UNCHECKED_CAST")
            (column as com.intellij.util.ui.ColumnInfo<AddressRow, String>).setValue(configurable.addresses.items[0], "  credit-service ")
        }
        configurable.apply()

        assertEquals(mapOf("credit-service" to "https://credit.example.com"), state.serviceUrls)
    }

    fun `test an address from the page is used by the resolver`() {
        myFixture.addClass(
            """
            package app;
            import org.springframework.cloud.openfeign.FeignClient;
            @FeignClient(name = "legacy-ledger")
            public interface LedgerClient {}
            """.trimIndent(),
        )
        val def = FeignScanner.scan(project).single()
        assertNull(FeignResolver.resolve(project, def, null).resolution.url)

        configurable.addresses.addRow(AddressRow("legacy-ledger", "https://ledger.example.com/"))
        configurable.apply()

        assertEquals("https://ledger.example.com", FeignResolver.resolve(project, def, null).resolution.url)
    }

    fun `test an extra address key is tried and also links the yml to the client`() {
        val client = myFixture.addClass(
            """
            package app;
            import org.springframework.cloud.openfeign.FeignClient;
            @FeignClient(name = "credit-service")
            public interface CreditClient {}
            """.trimIndent(),
        )
        val yml = yaml("application.yml", "urls:\n  credit-service:\n    base: https://credit.example.com")
        val def = FeignScanner.scan(project).single()

        // before: the key is not one of the built-in ones, the scan (now cached) does not know it
        assertNull(FeignResolver.resolve(project, def, null).resolution.url.takeIf { it?.startsWith("guessed") == true })
        assertTrue(ConfigNavigation.codeTargets(project, "urls.credit-service.base", yml).isEmpty())

        configurable.templates.add("urls.{name}.base")
        configurable.apply()

        val resolution = FeignResolver.resolve(project, def, null).resolution
        assertEquals("https://credit.example.com", resolution.url)
        assertEquals("config key urls.credit-service.base", resolution.source)
        // the cached scan was refreshed: the yml key now leads to the client
        assertEquals(listOf(client.name), ConfigNavigation.codeTargets(project, "urls.credit-service.base", yml).map { (it as com.intellij.psi.PsiClass).name })
    }

    fun `test a template without a name placeholder is ignored by the resolver`() {
        state.nameKeyTemplates.add("urls.fixed.base")
        state.nameKeyTemplates.add("urls.{name}.base")
        assertEquals("urls.{name}.base", FeignResolver.nameKeys(project).first())
        assertEquals(FeignResolver.NAME_KEYS.size + 1, FeignResolver.nameKeys(project).size)
    }

    fun `test disposing the page twice is harmless`() {
        configurable.disposeUIResources()
        configurable.disposeUIResources()
        Disposer.isDisposed(testRootDisposable)
    }
}
