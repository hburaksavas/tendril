package dev.tendril

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import dev.tendril.beans.BeanModels

/** Edits inside bodies must not invalidate the project-wide models; edits to declarations must. */
class CodeStructureTrackerTest : KotlinSpringTestCase() {

    private val tracker get() = CodeStructureTracker.getInstance(project)

    /** Replaces the first [old] in [file] by [new] through the document, as typing would, and commits. */
    private fun edit(file: PsiFile, old: String, new: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
            val at = document.text.indexOf(old)
            assertTrue("'$old' not in file", at >= 0)
            document.replaceString(at, at + old.length, new)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    private fun changes(action: () -> Unit): Boolean {
        val before = tracker.modificationCount
        action()
        return tracker.modificationCount != before
    }

    fun `test a Java method body edit is not a change`() {
        val file = myFixture.configureByText(
            "C.java",
            "class C { int f; @Deprecated int m() { int a = 1; return a; } }",
        )
        assertFalse(changes { edit(file, "int a = 1;", "int a = 2; int b = a;") })
    }

    fun `test a Java annotation or signature edit is a change`() {
        val file = myFixture.configureByText("C.java", "class C { @Deprecated int m() { return 1; } }")
        assertTrue(changes { edit(file, "@Deprecated", "@SuppressWarnings(\"x\")") })
        assertTrue(changes { edit(file, "int m()", "long m()") })
    }

    fun `test a Kotlin function body edit is not a change`() {
        val file = myFixture.configureByText("K.kt", "class K { fun m(): Int { val a = 1; return a } }")
        assertFalse(changes { edit(file, "val a = 1", "val a = 2 + 3") })
    }

    fun `test a Kotlin expression body or declaration edit is a change`() {
        val file = myFixture.configureByText("K.kt", "class K { fun m() = run { 1 }\n val p: Int = 0 }")
        // the type of an expression-bodied function comes from its body
        assertTrue(changes { edit(file, "run { 1 }", "run { \"s\" }") })
        assertTrue(changes { edit(file, "val p: Int", "val q: Int") })
    }

    fun `test the bean model is rebuilt only for declaration edits`() {
        val file = myFixture.addClass(
            """
            package app;
            @org.springframework.stereotype.Service
            public class A { public void run() { int x = 1; } }
            """.trimIndent(),
        ).containingFile
        val model = BeanModels.get(project)
        edit(file, "int x = 1;", "int x = 2;")
        assertSame(model, BeanModels.get(project))

        edit(file, "public class A", "public class B")
        assertEquals(listOf("b"), BeanModels.get(project).beans.map { it.name })
    }
}
