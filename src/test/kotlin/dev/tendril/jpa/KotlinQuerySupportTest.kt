package dev.tendril.jpa

import com.intellij.psi.PsiField
import com.intellij.psi.PsiParameter
import dev.tendril.KotlinSpringTestCase
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt

@OptIn(KaAllowAnalysisOnEdt::class)
class KotlinQuerySupportTest : KotlinSpringTestCase() {

    override fun setUp() {
        super.setUp()
        QuerySupportTest.addJpaStubs { myFixture.addClass(it) }
        myFixture.addFileToProject(
            "app/Customer.kt",
            """
            package app
            import jakarta.persistence.*
            @Entity
            class Customer(@Id val id: Long, val email: String, @Transient val cached: String? = null) {
                @OneToMany var orders: MutableList<Purchase> = mutableListOf()
            }
            @Entity
            class Purchase(@Id val id: Long, val total: Int)
            """.trimIndent(),
        )
        myFixture.enableInspections(JpqlQueryInspection(), QueryParameterInspection())
    }

    private fun repository(body: String) = myFixture.configureByText(
        "CustomerRepository.kt",
        """
        package app
        import org.springframework.data.jpa.repository.*
        import org.springframework.data.repository.query.Param
        interface CustomerRepository : JpaRepository<Customer, Long> {
        $body
        }
        """.trimIndent(),
    )

    private fun problems(body: String): List<String> = allowAnalysisOnEdt {
        repository(body)
        myFixture.doHighlighting().mapNotNull { it.description }.filter { !it.startsWith("Unused") }
    }

    fun `test unknown attributes and parameters are reported in Kotlin`() {
        val p = problems(
            """
            @Query("select c from Customer c join c.orders o where c.emial = :mail and o.totl > 0")
            fun find(email: String): List<Customer>
            """,
        )
        assertTrue(p.toString(), "Unknown attribute 'emial' of 'Customer'" in p)
        assertTrue(p.toString(), "Unknown attribute 'totl' of 'Purchase'" in p)
        assertTrue(p.toString(), "No method parameter is bound to ':mail'" in p)
        assertTrue(p.toString(), "Parameter 'email' is not used in the query" in p)
    }

    fun `test a valid Kotlin query has no findings`() {
        val p = problems(
            """
            @Query(""${'"'}
                select c from Customer c
                where c.email = :email and c.id = :id
            ""${'"'})
            fun find(email: String, @Param("id") key: Long): Customer?
            """,
        )
        assertEquals(emptyList<String>(), p.filter { "@Query" !in it && ("Unknown" in it || "parameter" in it.lowercase()) })
    }

    fun `test navigation from a Kotlin query`() = allowAnalysisOnEdt {
        repository("@Query(\"select c from Customer c where c.email = :email\") fun find(email: String): Customer?")
        val text = myFixture.file.text
        val attribute = myFixture.file.findReferenceAt(text.indexOf("email =") + 1)?.resolve()
        assertEquals("email", (attribute as? PsiField)?.name)
        val parameter = myFixture.file.findReferenceAt(text.indexOf(":email") + 2)?.resolve()
        assertEquals("email", (parameter as? PsiParameter)?.name)
    }

    fun `test completion of attributes in a Kotlin query`() = allowAnalysisOnEdt {
        repository("@Query(\"select c from Customer c where c.<caret>\") fun all(): List<Customer>")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(items.toString(), items.containsAll(listOf("id", "email", "orders")))
        assertFalse(items.toString(), "cached" in items)
    }
}
