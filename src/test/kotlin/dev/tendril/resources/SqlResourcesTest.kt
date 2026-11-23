package dev.tendril.resources

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import dev.tendril.SpringTestCase

class SqlResourcesTest : SpringTestCase() {

    private fun sql(path: String) = myFixture.addFileToProject(path, "select 1;")

    private fun annotatedClass(annotation: String) = myFixture.addClass(
        """
        package app;
        import org.springframework.beans.factory.annotation.Value;
        public class Loader {
            $annotation
            private org.springframework.core.io.Resource script;
        }
        """.trimIndent(),
    )

    private fun referenceAt(file: PsiFile, marker: String) =
        file.findReferenceAt(file.text.indexOf(marker) + 2)

    fun `test relative paths`() {
        assertEquals("db/seed.sql", SqlResources.relativePath("classpath:/db/seed.sql"))
        assertEquals("db/seed.sql", SqlResources.relativePath("classpath*:db/seed.sql"))
        assertEquals("a.sql", SqlResources.relativePath("/a.sql"))
        assertNull(SqlResources.relativePath("classpath:db/*.sql"))
        assertNull(SqlResources.relativePath("\${scripts}/a.sql"))
        assertNull(SqlResources.relativePath("classpath:db/seed.json"))
    }

    fun `test a classpath location in a Value annotation resolves to the sql file`() {
        val script = sql("src/main/resources/db/seed.sql")
        val cls = annotatedClass("""@Value("classpath:db/seed.sql")""")
        val reference = referenceAt(cls.containingFile, "classpath:db/seed.sql")!!
        assertEquals(script, reference.resolve())
    }

    fun `test a plain path with a leading slash resolves too`() {
        val script = sql("src/test/resources/schema.sql")
        val cls = annotatedClass("""@Value("/schema.sql")""")
        assertEquals(script, referenceAt(cls.containingFile, "/schema.sql")!!.resolve())
    }

    fun `test a script that does not exist does not resolve`() {
        val cls = annotatedClass("""@Value("classpath:db/missing.sql")""")
        assertNull(referenceAt(cls.containingFile, "classpath:db/missing.sql")!!.resolve())
    }

    fun `test the sql file finds the annotation that uses it`() {
        val script = sql("src/main/resources/db/seed.sql")
        annotatedClass("""@Value("classpath:db/seed.sql")""")
        val usages = ReferencesSearch.search(script, GlobalSearchScope.projectScope(project)).findAll()
        assertEquals(1, usages.size)
    }

    fun `test the sql file gets a gutter icon only when something uses it`() {
        val used = sql("src/main/resources/db/seed.sql")
        val unused = sql("src/main/resources/db/other.sql")
        annotatedClass("""@Value("classpath:db/seed.sql")""")

        fun markers(file: PsiFile): List<LineMarkerInfo<*>> {
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
            myFixture.doHighlighting()
            return com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
                .getLineMarkers(myFixture.editor.document, project)
                .filter { it.lineMarkerTooltip?.startsWith("Used by") == true }
        }
        assertEquals(1, markers(used).size)
        assertEquals(0, markers(unused).size)
    }
}
