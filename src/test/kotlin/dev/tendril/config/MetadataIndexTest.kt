package dev.tendril.config

import org.junit.Assert.*
import org.junit.Test

class MetadataIndexTest {

    private fun index(vararg props: Pair<String, String?>) = MetadataIndex(
        props.associate { (name, type) -> name to SpringProperty(name, type, null, null) },
        emptyMap(),
    )

    @Test
    fun `relaxed binding ignores case dashes underscores and list indexes`() {
        assertEquals("server.port", MetadataIndex.normalize("server.port"))
        assertEquals("serverport", MetadataIndex.normalize("server-port"))
        assertEquals("spring.datasource.url", MetadataIndex.normalize("Spring.DataSource.URL"))
        assertEquals("ab.c", MetadataIndex.normalize("a_b[0].c"))
        assertEquals(MetadataIndex.normalize("my.list[0].name"), MetadataIndex.normalize("my.list.name"))
        assertEquals(MetadataIndex.normalize("max-pool_size"), MetadataIndex.normalize("MAXPOOLSIZE"))
    }

    @Test
    fun `knows properties in any spelling`() {
        val i = index("server.port" to "java.lang.Integer", "spring.main.lazy-initialization" to "java.lang.Boolean")
        assertTrue(i.isKnown("server.port"))
        assertTrue(i.isKnown("SERVER.PORT"))
        assertTrue(i.isKnown("spring.main.lazyInitialization"))
        assertTrue(i.isKnown("spring.main.lazy_initialization"))
        assertFalse(i.isKnown("server.prot"))
    }

    @Test
    fun `a parent of a known property is known`() {
        val i = index("spring.datasource.url" to "java.lang.String")
        assertTrue(i.isKnown("spring"))
        assertTrue(i.isKnown("spring.datasource"))
    }

    @Test
    fun `keys below a map or object property are known`() {
        val i = index("logging.level" to "java.util.Map<java.lang.String,org.springframework.boot.logging.LogLevel>")
        assertTrue(i.isKnown("logging.level.dev.playground"))
    }

    @Test
    fun `keys below a simple property are unknown`() {
        val i = index("server.port" to "java.lang.Integer")
        assertFalse(i.isKnown("server.port.extra"))
    }

    @Test
    fun `without library metadata nothing is reported`() {
        assertTrue(MetadataIndex(emptyMap(), emptyMap()).isKnown("anything.at.all"))
    }

    @Test
    fun `finds a property by key in another spelling`() {
        val i = index("server.error.include-message" to "java.lang.String")
        assertNotNull(i.propertyByKey("server.error.includeMessage"))
        assertNull(i.propertyByKey("server.error.other"))
    }
}
