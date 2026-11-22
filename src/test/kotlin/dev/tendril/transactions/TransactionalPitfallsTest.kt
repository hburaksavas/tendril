package dev.tendril.transactions

import dev.tendril.SpringTestCase

class TransactionalPitfallsTest : SpringTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass("package org.springframework.transaction.annotation; public enum Propagation { REQUIRED, REQUIRES_NEW }")
        myFixture.addClass(
            """
            package org.springframework.transaction.annotation;
            public @interface Transactional { Propagation propagation() default Propagation.REQUIRED; boolean readOnly() default false;
                Class<? extends Throwable>[] rollbackFor() default {}; }
            """.trimIndent(),
        )
        myFixture.enableInspections(TransactionalPitfallsInspection())
    }

    private fun report(body: String, modifiers: String = ""): List<String> {
        myFixture.configureByText(
            "Orders.java",
            """
            package app;
            import org.springframework.transaction.annotation.*;
            import org.springframework.stereotype.Service;
            @Service
            $modifiers class Orders {
            $body
            }
            """.trimIndent(),
        )
        return myFixture.doHighlighting().mapNotNull { it.description }
    }

    fun `test a private transactional method is reported`() {
        val m = report("@Transactional private void save() {}")
        assertTrue(m.toString(), m.any { it.contains("private method") })
    }

    fun `test a static and a final method are reported`() {
        val m = report("@Transactional static void a() {}\n@Transactional final void b() {}")
        assertTrue(m.toString(), m.any { it.contains("static method") })
        assertTrue(m.toString(), m.any { it.contains("final method") })
    }

    fun `test a final class is reported`() {
        val m = report("@Transactional public void save() {}", modifiers = "final")
        assertTrue(m.toString(), m.any { it.contains("Final class") })
    }

    fun `test a plain public transactional method is fine`() {
        val m = report("@Transactional public void save() {}")
        assertTrue(m.toString(), m.isEmpty())
    }

    fun `test a call to a transactional method of the same class is reported`() {
        val m = report("public void run() { save(); }\n@Transactional public void save() {}")
        assertTrue(m.toString(), m.any { it.contains("bypasses the Spring proxy") && it.contains("no transaction is started") })
    }

    fun `test the same call through this is reported too`() {
        val m = report("public void run() { this.save(); }\n@Transactional public void save() {}")
        assertTrue(m.toString(), m.any { it.contains("bypasses the Spring proxy") })
    }

    fun `test a transactional caller calling a plain transactional method is fine`() {
        val m = report("@Transactional public void run() { save(); }\n@Transactional public void save() {}")
        assertTrue(m.toString(), m.none { it.contains("bypasses") })
    }

    fun `test a transactional caller is told when the callee wants a new transaction`() {
        val m = report(
            "@Transactional public void run() { audit(); }\n" +
                "@Transactional(propagation = Propagation.REQUIRES_NEW) public void audit() {}",
        )
        assertTrue(m.toString(), m.any { it.contains("REQUIRES_NEW is not applied") })
    }

    fun `test a call on another object is fine`() {
        myFixture.addClass("package app; public class Other { @org.springframework.transaction.annotation.Transactional public void save() {} }")
        val m = report("Other other;\npublic void run() { other.save(); }")
        assertTrue(m.toString(), m.none { it.contains("bypasses") })
    }

    fun `test a checked exception in throws is reported and the fix adds rollbackFor`() {
        val m = report("@Transactional public void save() throws java.io.IOException {}")
        assertTrue(m.toString(), m.any { it.contains("'IOException' does not roll back") })
        val fix = myFixture.getAllQuickFixes().first { it.text.startsWith("Add rollbackFor") }
        myFixture.launchAction(fix)
        assertTrue(myFixture.file.text, myFixture.file.text.contains("rollbackFor = "))
    }

    fun `test a thrown checked exception is reported unless it is caught`() {
        val thrown = report("@Transactional public void save() { if (true) throw new java.io.IOException(); }")
        assertTrue(thrown.toString(), thrown.any { it.contains("'IOException' does not roll back") })
        val caught = report(
            "@Transactional public void save() { try { throw new java.io.IOException(); } catch (java.io.IOException e) {} }",
        )
        assertTrue(caught.toString(), caught.none { it.contains("does not roll back") })
    }

    fun `test runtime exceptions and rollbackFor are fine`() {
        val rt = report("@Transactional public void a() throws IllegalStateException {}")
        assertTrue(rt.toString(), rt.isEmpty())
        val m = report("@Transactional(rollbackFor = Exception.class) public void b() throws java.io.IOException {}")
        assertTrue(m.toString(), m.none { it.contains("does not roll back") })
    }
}
