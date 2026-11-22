package dev.tendril.hints

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiAnnotation
import dev.tendril.SpringTestCase
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.toUElementOfType

class SpringHintsTest : SpringTestCase() {

    private fun annotation(code: String, name: String): UAnnotation {
        myFixture.configureByText("Sample.java", code.trimIndent())
        val ann = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiAnnotation::class.java)
            .first { it.qualifiedName?.endsWith(name) == true }
        return ann.toUElementOfType<UAnnotation>()!!
    }

    // ---- pure parts ----

    fun `test a resolved value is shown after an arrow`() {
        assertEquals("→ 8080", ValueHints.resolvedValue("\${server.port}") { "8080" to emptyList() })
    }

    fun `test nothing is shown for plain text, expressions, unresolved keys and unchanged text`() {
        val same = { s: String -> s to emptyList<String>() }
        assertNull(ValueHints.resolvedValue("plain", same))
        assertNull(ValueHints.resolvedValue("#{1 + 1}", same))
        assertNull(ValueHints.resolvedValue("\${a.b}") { it to listOf("a.b") })
        assertNull(ValueHints.resolvedValue("\${a.b}", same))
    }

    fun `test secrets are not shown`() {
        assertEquals("→ ••••••", ValueHints.resolvedValue("\${jwt.secret}") { "hunter2" to emptyList() })
        assertEquals("→ ••••••", ValueHints.resolvedValue("\${db.password:x}") { "hunter2" to emptyList() })
    }

    fun `test long values are cut`() {
        val shown = ValueHints.resolvedValue("\${x.y}") { "a".repeat(100) to emptyList() }!!
        assertTrue(shown, shown.length <= 62 && shown.endsWith("…"))
    }

    fun `test transaction summary shows the default and what differs from it`() {
        assertEquals("REQUIRED · read-write", TransactionalHints.summary(null, null, false, null, emptyList()))
        assertEquals("REQUIRES_NEW · read-only", TransactionalHints.summary("REQUIRES_NEW", "DEFAULT", true, null, emptyList()))
        assertEquals(
            "REQUIRED · read-write · SERIALIZABLE · 30s timeout · rollback on IOException, SQLException",
            TransactionalHints.summary(null, "SERIALIZABLE", false, 30, listOf("IOException", "SQLException")),
        )
    }

    fun `test fixed rates are written the way a person would`() {
        assertEquals("5 s", ScheduledHints.formatMillis(5000))
        assertEquals("2 min", ScheduledHints.formatMillis(120_000))
        assertEquals("1 h", ScheduledHints.formatMillis(3_600_000))
        assertEquals("250 ms", ScheduledHints.formatMillis(250))
        assertEquals(30_000L, ScheduledHints.parseDuration("PT30S"))
        assertEquals(1500L, ScheduledHints.parseDuration("1500"))
        assertNull(ScheduledHints.parseDuration("soon"))
    }

    // ---- from source ----

    fun `test @Value shows what the config file says`() {
        yaml("application.yml", "app:\n  greeting: hello")
        val ann = annotation(
            """
            package app;
            import org.springframework.beans.factory.annotation.Value;
            class A { @Value("${'$'}{app.greeting}") String greeting; }
            """,
            "Value",
        )
        assertEquals("→ hello", ValueHints().hint(ann))
    }

    fun `test @Value without a placeholder gets no hint`() {
        val ann = annotation(
            """
            package app;
            import org.springframework.beans.factory.annotation.Value;
            class A { @Value("literal") String greeting; }
            """,
            "Value",
        )
        assertNull(ValueHints().hint(ann))
    }

    fun `test a Feign client shows its address and path`() {
        yaml("application.yml", "clients:\n  inventory:\n    url: http://localhost:9001")
        myFixture.addClass(
            """
            package app;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.*;
            @FeignClient(name = "inventory", url = "${'$'}{clients.inventory.url}", path = "/v1")
            public interface InventoryClient { @GetMapping("/stock") String stock(); }
            """.trimIndent(),
        )
        myFixture.configureByFile("app/InventoryClient.java".also { })
    }

    fun `test @Scheduled with a cron shows the next run, with fixed rate how often`() {
        myFixture.addClass(
            """
            package org.springframework.scheduling.annotation;
            public @interface Scheduled { String cron() default ""; long fixedRate() default -1; long fixedDelay() default -1;
                String fixedRateString() default ""; String fixedDelayString() default ""; String zone() default ""; }
            """.trimIndent(),
        )
        val cron = annotation(
            """
            package app;
            import org.springframework.scheduling.annotation.Scheduled;
            class A { @Scheduled(cron = "0 30 14 * * *") void a() {} }
            """,
            "Scheduled",
        )
        assertTrue(ScheduledHints().hint(cron)!!.startsWith("next: "))

        val rate = annotation(
            """
            package app;
            import org.springframework.scheduling.annotation.Scheduled;
            class B { @Scheduled(fixedRate = 5000) void a() {} }
            """,
            "Scheduled",
        )
        assertEquals("every 5 s", ScheduledHints().hint(rate))

        val delay = annotation(
            """
            package app;
            import org.springframework.scheduling.annotation.Scheduled;
            class C { @Scheduled(fixedDelayString = "PT1M") void a() {} }
            """,
            "Scheduled",
        )
        assertEquals("every 1 min after each run", ScheduledHints().hint(delay))
    }

    fun `test @Transactional shows its attributes`() {
        myFixture.addClass("package org.springframework.transaction.annotation; public enum Propagation { REQUIRED, REQUIRES_NEW }")
        myFixture.addClass(
            """
            package org.springframework.transaction.annotation;
            public @interface Transactional { Propagation propagation() default Propagation.REQUIRED; boolean readOnly() default false;
                int timeout() default -1; Class<? extends Throwable>[] rollbackFor() default {}; }
            """.trimIndent(),
        )
        val ann = annotation(
            """
            package app;
            import org.springframework.transaction.annotation.*;
            class A { @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, rollbackFor = Exception.class) void a() {} }
            """,
            "Transactional",
        )
        assertEquals("REQUIRES_NEW · read-only · rollback on Exception", TransactionalHints().hint(ann))
    }
}
