package dev.tendril.logs

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import dev.tendril.SpringTestCase

class LoggerChoicesTest : SpringTestCase() {

    private val choices get() = LoggerChoices(project, GlobalSearchScope.allScope(project))

    private fun docText(name: String) = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(name))!!.text

    fun `test a shortened name resolves to the class and its full name`() {
        myFixture.addClass("package com.example.order; public class OrderService {}")
        myFixture.addClass("package com.example.billing; public class OrderService {}")
        val resolved = choices.resolve("c.e.order.OrderService")
        assertEquals(listOf("com.example.order.OrderService"), resolved.classes.map { it.qualifiedName })
        assertEquals("com.example.order.OrderService", resolved.fullName)
    }

    fun `test ambiguous shortened name lists every class but has no full name`() {
        myFixture.addClass("package com.example.order; public class OrderService {}")
        myFixture.addClass("package com.export.order; public class OrderService {}")
        val resolved = choices.resolve("c.e.order.OrderService")
        assertEquals(2, resolved.classes.size)
        assertNull(resolved.fullName)
        assertEquals(
            listOf("Go to com.example.order.OrderService", "Go to com.export.order.OrderService"),
            choices.of(resolved, null, null).map { it.text },
        )
    }

    fun `test nested class gets its binary name`() {
        myFixture.addClass("package app; public class OrderService { public static class Audit {} }")
        assertEquals("app.OrderService\$Audit", choices.resolve("app.OrderService\$Audit").fullName)
    }

    fun `test a name that is no class keeps its level choices`() {
        myFixture.addFileToProject("application.properties", "")
        val resolved = choices.resolve("org.hibernate.SQL")
        assertEquals(emptyList<Any>(), resolved.classes)
        assertEquals(listOf("Add logging.level.org.hibernate.SQL to application.properties"), choices.of(resolved, null, null).map { it.text })
    }

    fun `test running application level comes first`() {
        myFixture.addClass("package app; public class OrderService {}")
        myFixture.addFileToProject("application.properties", "")
        val list = choices.of(choices.resolve("app.OrderService"), "http://localhost:8080/actuator", "INFO")
        assertEquals(
            listOf(
                "Go to OrderService",
                "Level of OrderService in the running application (INFO)",
                "Add logging.level.app.OrderService to application.properties",
            ),
            list.map { it.text },
        )
        assertEquals(listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF", "Reset (inherit from parent)"), list[1].children.map { it.text })
    }

    fun `test level is added under logging level in yml`() {
        myFixture.addClass("package app; public class OrderService {}")
        yaml("application.yml", "app:\n  name: shop")
        val add = choices.of(choices.resolve("app.OrderService"), null, null).single { it.text.startsWith("Add") }
        assertEquals("Add logging.level.app.OrderService to application.yml", add.text)
        add.children.single { it.text == "DEBUG" }.perform(project)
        val text = docText("application.yml")
        assertTrue(text, text.contains("logging:") && text.contains("level:") && text.contains("app.OrderService: DEBUG"))
    }

    fun `test existing level is changed`() {
        myFixture.addClass("package app; public class OrderService {}")
        myFixture.addFileToProject("application.properties", "logging.level.app.OrderService=INFO\n")
        val change = choices.of(choices.resolve("app.OrderService"), null, null).single { it.text.startsWith("Change") }
        assertEquals("Change logging.level.app.OrderService in application.properties", change.text)
        change.children.single { it.text == "TRACE" }.perform(project)
        assertTrue(docText("application.properties").contains("logging.level.app.OrderService=TRACE"))
    }
}
