package dev.tendril.jpa

import junit.framework.TestCase

class JpqlParserTest : TestCase() {

    private fun paths(s: JpqlStructure) = s.paths.map { p -> p.segments.joinToString(".") { it.text } }
    private fun aliases(s: JpqlStructure) = s.aliases.map { a ->
        a.name.text + "=" + when (val src = a.source) {
            is AliasSource.Entity -> src.entity.name
            is AliasSource.Join -> src.path.segments.joinToString(".") { it.text }
        }
    }

    fun `test range variables, joins and paths`() {
        val s = JpqlParser.parse(
            "select distinct u from User u left join fetch u.orders o join u.address as a " +
                "where o.total > 10 and a.city = :city order by u.email",
        )
        assertEquals(listOf("User"), s.entities.map { it.name })
        assertEquals(listOf("u=User", "o=u.orders", "a=u.address"), aliases(s))
        assertEquals(listOf("u.orders", "u.address", "o.total", "a.city", "u.email"), paths(s))
        assertEquals(listOf(":city"), s.namedParams.map { it.text })
    }

    fun `test several range declarations and the old IN syntax`() {
        val s = JpqlParser.parse("select u, r from User u, Role r, in(u.orders) o where u.role = r")
        assertEquals(listOf("User", "Role"), s.entities.map { it.name })
        assertEquals(listOf("u=User", "r=Role", "o=u.orders"), aliases(s))
    }

    fun `test update and delete`() {
        assertEquals(listOf("u=User"), aliases(JpqlParser.parse("update User u set u.active = false where u.id = ?1")))
        assertEquals(listOf("User"), JpqlParser.parse("delete from User where id = :id").entities.map { it.name })
        assertEquals(emptyList<String>(), aliases(JpqlParser.parse("delete from User where id = :id")))
    }

    fun `test aliases are case-insensitive and AS is optional`() {
        val s = JpqlParser.parse("SELECT U FROM User AS U WHERE u.email = :e")
        assertNotNull(s.alias("u"))
    }

    fun `test a subquery has its own from clause`() {
        val s = JpqlParser.parse("select u from User u where u.id in (select o.user.id from Order o where o.total > 5)")
        assertEquals(listOf("User", "Order"), s.entities.map { it.name })
        assertEquals(listOf("u.id", "o.user.id", "o.total"), paths(s))
    }

    fun `test an entity join`() {
        val s = JpqlParser.parse("select u from User u join Audit a on a.userId = u.id")
        assertEquals(listOf("User", "Audit"), s.entities.map { it.name })
        assertTrue(s.entities[1].inJoin)
    }

    fun `test strings, constructor expressions and qualified names are not paths`() {
        val s = JpqlParser.parse(
            "select new com.acme.UserDto(u.id, u.email) from com.acme.User u where u.email <> 'a.b:c' " +
                "and u.status = com.acme.Status.ACTIVE",
        )
        assertEquals(listOf("com.acme.User"), s.entities.map { it.name })
        assertEquals(listOf("u.id", "u.email", "u.email", "u.status", "com.acme.Status.ACTIVE"), paths(s))
        assertTrue(s.namedParams.isEmpty())
    }

    fun `test parameters, casts and SpEL`() {
        val s = JpqlParser.parse(
            "select * from users u where u.data::text = :data and u.id = ?2 and u.tenant = :#{#tenant.id} " +
                "and u.x = ?#{[0]} from #{#entityName} e",
        )
        assertEquals(listOf(":data"), s.namedParams.map { it.text })
        assertEquals(listOf(2), s.positionalParams.map { s.positionalIndex(it) })
        assertEquals(setOf("tenant"), s.spelNames)
        assertEquals(setOf(0), s.spelIndexes)
        assertTrue(s.entities.any { it.isSpel })
    }
}
