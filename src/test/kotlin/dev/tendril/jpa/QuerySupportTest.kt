package dev.tendril.jpa

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiParameter
import dev.tendril.SpringTestCase

class QuerySupportTest : SpringTestCase() {

    override fun setUp() {
        super.setUp()
        addJpaStubs(::addClass)
        addClass("package app; @jakarta.persistence.MappedSuperclass public abstract class Base { @jakarta.persistence.Id Long id; java.time.Instant createdAt; }")
        addClass("package app; @jakarta.persistence.Embeddable public class Address { String city; String zip; }")
        addClass(
            """
            package app;
            import jakarta.persistence.*;
            @Entity public class User extends Base {
                String email;
                @Transient String cached;
                static String CONSTANT;
                @Embedded Address address;
                @OneToMany(mappedBy = "user") java.util.List<Order> orders;
            }
            """,
        )
        addClass("package app; @jakarta.persistence.Entity(name = \"Orders\") public class Order extends Base { java.math.BigDecimal total; @jakarta.persistence.ManyToOne User user; }")
        addClass("package app; @jakarta.persistence.Entity public class Admin extends User { String level; }")
        myFixture.enableInspections(JpqlQueryInspection(), QueryParameterInspection())
    }

    private fun addClass(text: String) { myFixture.addClass(text.trimIndent()) }

    // in app/, where its package says it is: package-private fields are searched in their package only
    private fun repository(body: String, imports: String = "") = configure(
        "app/UserRepository.java",
        """
        package app;
        import org.springframework.data.jpa.repository.*;
        import org.springframework.data.repository.query.Param;
        $imports
        public interface UserRepository extends JpaRepository<User, Long> {
        $body
        }
        """.trimIndent(),
    )

    private fun configure(path: String, text: String) {
        val existing = myFixture.findFileInTempDir(path)
        val file = if (existing == null) myFixture.addFileToProject(path, text).virtualFile else existing.also {
            com.intellij.openapi.application.WriteAction.run<Throwable> { com.intellij.openapi.vfs.VfsUtil.saveText(it, text) }
        }
        myFixture.configureFromExistingVirtualFile(file)
    }

    private fun problems(body: String, imports: String = ""): List<String> {
        repository(body, imports)
        return myFixture.doHighlighting().mapNotNull { it.description }
    }

    fun `test a valid query has no findings`() {
        val p = problems(
            """
            @Query("select u from User u left join u.orders o where u.email = :email and o.total > 0 and u.address.city = :city and u.createdAt < current_timestamp")
            java.util.List<User> find(@Param("email") String email, String city);
            """,
        )
        assertEquals(emptyList<String>(), p)
    }

    fun `test an unknown entity is reported`() {
        assertTrue(problems("@Query(\"select u from Usr u\") java.util.List<User> all();").contains("Unknown entity 'Usr'"))
    }

    fun `test an explicit entity name and the class name are both accepted`() {
        assertEquals(emptyList<String>(), problems("@Query(\"select o from Orders o\") java.util.List<Order> a();\n@Query(\"select o from Order o\") java.util.List<Order> b();"))
    }

    fun `test unknown attributes are reported, also behind an embedded or a join`() {
        val p = problems(
            """
            @Query("select u from User u join u.orders o where u.emial = 'x' or u.address.cty = 'y' or o.totl > 1 or u.cached = 'z'")
            java.util.List<User> find();
            """,
        )
        assertTrue(p.toString(), "Unknown attribute 'emial' of 'User'" in p)
        assertTrue(p.toString(), "Unknown attribute 'cty' of 'Address'" in p)
        assertTrue(p.toString(), "Unknown attribute 'totl' of 'Order'" in p)
        assertTrue(p.toString(), "Unknown attribute 'cached' of 'User'" in p) // @Transient
    }

    fun `test inherited, subclass and special attributes are accepted`() {
        val p = problems("@Query(\"select u from User u where u.id = 1 and u.createdAt is null and u.level = 'x' and u.orders.size > 0 and u.class = Admin\") java.util.List<User> f();")
        assertEquals(emptyList<String>(), p)
    }

    fun `test entityName SpEL resolves to the repository domain type`() {
        val p = problems("@Query(\"select e from #{#entityName} e where e.nope = 1\") java.util.List<User> f();")
        assertTrue(p.toString(), "Unknown attribute 'nope' of 'User'" in p)
    }

    fun `test native queries are not checked against entities`() {
        assertEquals(emptyList<String>(), problems("@Query(value = \"select * from usr u where u.emial = 1\", nativeQuery = true) java.util.List<User> f();"))
    }

    fun `test a named parameter without a method parameter is reported`() {
        val p = problems("@Query(\"select u from User u where u.email = :mail\") User f(String email);")
        assertTrue(p.toString(), "No method parameter is bound to ':mail'" in p)
        assertTrue(p.toString(), "Parameter 'email' is not used in the query" in p)
    }

    fun `test Pageable and Sort are not bound and SpEL uses count`() {
        val p = problems(
            "@Query(\"select u from User u where u.email = :#{#email} and u.id = :id\") " +
                "java.util.List<User> f(String email, Long id, org.springframework.data.domain.Pageable page, org.springframework.data.domain.Sort sort);",
        )
        assertEquals(emptyList<String>(), p)
    }

    fun `test positional parameters`() {
        val p = problems("@Query(\"select u from User u where u.email = ?1 or u.id = ?3\") User f(String email, Long id);")
        assertTrue(p.toString(), p.any { it.startsWith("'?3' has no method parameter") })
        assertTrue(p.toString(), "Parameter 'id' is not used in the query" in p)
    }

    fun `test named and positional cannot be mixed`() {
        val p = problems("@Query(\"select u from User u where u.email = :email or u.id = ?2\") User f(String email, Long id);")
        assertTrue(p.toString(), p.any { it.startsWith("Named (:name) and positional") })
    }

    fun `test parameters are checked in native queries too`() {
        val p = problems("@Query(value = \"select * from users where email = :mail and data::text = 'x'\", nativeQuery = true) User f(@Param(\"email\") String email);")
        assertTrue(p.toString(), "No method parameter is bound to ':mail'" in p)
    }

    fun `test concatenated literals and text blocks`() {
        val p = problems(
            """
            @Query("select u from User u " +
                   "where u.emial = :email")
            User a(String email);
            @Query(""${'"'}
                select u
                from User u
                where u.adress is null
                ""${'"'})
            User b();
            """,
        )
        assertTrue(p.toString(), "Unknown attribute 'emial' of 'User'" in p)
        assertTrue(p.toString(), "Unknown attribute 'adress' of 'User'" in p)
        val highlighted = myFixture.doHighlighting().filter { it.description?.startsWith("Unknown attribute") == true }
            .map { myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(it.startOffset, it.endOffset)) }
        assertEquals(listOf("emial", "adress"), highlighted)
    }

    private fun resolveAt(marker: String, shift: Int = 1): com.intellij.psi.PsiElement? {
        val offset = myFixture.file.text.indexOf(marker) + shift
        return myFixture.file.findReferenceAt(offset)?.resolve()
    }

    fun `test navigation to the entity, attributes and parameters`() {
        repository("@Query(\"select u from User u join u.orders o where u.address.city = :town and o.total > ?2\") User f(@Param(\"town\") String city, java.math.BigDecimal min);")
        assertEquals("app.User", (resolveAt("User u") as PsiClass).qualifiedName)
        assertEquals("orders", (resolveAt("orders o") as PsiField).name)
        assertEquals("city", (resolveAt("city =") as PsiField).name)
        assertEquals("Address", (resolveAt("city =") as PsiField).containingClass?.name)
        assertEquals("total", (resolveAt("total >") as PsiField).name)
        assertEquals("city", (resolveAt(":town", 2) as PsiParameter).name)
        assertEquals("min", (resolveAt("?2", 1) as PsiParameter).name)
    }

    private fun complete(query: String, method: String = "User f(String email);"): List<String> {
        repository("@Query(\"$query\") $method")
        return myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    }

    fun `test completion of entities, attributes, aliases and parameters`() {
        assertTrue(complete("select u from <caret>").containsAll(listOf("User", "Orders", "Admin")))
        val attributes = complete("select u from User u where u.<caret>")
        assertTrue(attributes.toString(), attributes.containsAll(listOf("email", "address", "orders", "id", "createdAt", "level")))
        assertFalse(attributes.toString(), "cached" in attributes || "CONSTANT" in attributes)
        assertTrue(complete("select u from User u where u.address.<caret>").containsAll(listOf("city", "zip")))
        assertTrue(complete("select <caret> from User usr").contains("usr"))
        assertTrue(complete("select u from User u where u.email = :<caret>", "User f(String email, Long other);").containsAll(listOf("email", "other")))
    }

    fun `test renaming a field renames it in the query`() {
        repository("@Query(\"select u from User u where u.email = :email\") User f(String email);")
        val field = myFixture.findClass("app.User").findFieldByName("email", false)!!
        myFixture.renameElement(field, "mail")
        assertTrue(myFixture.file.text, "u.mail = :email" in myFixture.file.text)
    }

    companion object {
        /** jakarta.persistence and Spring Data declarations the tests need, as source. */
        fun addJpaStubs(add: (String) -> Unit) {
            for (a in listOf("Entity", "Embeddable", "MappedSuperclass", "Id", "EmbeddedId", "Embedded", "Transient", "OneToMany", "ManyToOne")) {
                add(
                    "package jakarta.persistence; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) " +
                        "@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.METHOD}) " +
                        "public @interface $a { String name() default \"\"; String mappedBy() default \"\"; }",
                )
            }
            add("package org.springframework.data.repository; public interface Repository<T, ID> {}")
            add("package org.springframework.data.jpa.repository; public interface JpaRepository<T, ID> extends org.springframework.data.repository.Repository<T, ID> {}")
            add("package org.springframework.data.jpa.repository; public @interface Query { String value() default \"\"; String countQuery() default \"\"; boolean nativeQuery() default false; }")
            add("package org.springframework.data.repository.query; @java.lang.annotation.Target(java.lang.annotation.ElementType.PARAMETER) public @interface Param { String value(); }")
            add("package org.springframework.data.domain; public interface Pageable {}")
            add("package org.springframework.data.domain; public class Sort {}")
        }
    }
}
