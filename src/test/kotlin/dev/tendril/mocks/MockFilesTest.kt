package dev.tendril.mocks

import com.google.gson.JsonParser
import junit.framework.TestCase
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class MockFilesTest : TestCase() {

    private val feign = """
        {
          "kind": "feign",
          "target": "OrdersClient#get(String)",
          "label": "OrdersClient.get",
          "when": { "path": "/orders/42", "query": { "view": "full" } },
          "response": { "status": 404, "body": { "error": "not found" }, "delayMs": 300 }
        }
    """.trimIndent()

    fun `test a file is read with defaults`() {
        val d = MockFiles.parse(feign)
        assertTrue(d.enabled)
        assertTrue(d.isFeign)
        assertNull(d.method)
        assertEquals(mapOf("view" to "full"), d.query)
        assertEquals("""{"error":"not found"}""", d.bodyText)
        assertEquals(mapOf("Content-Type" to "application/json"), d.effectiveHeaders)
        assertEquals("ANY /orders/42?view=full  →  404, 300 ms", d.summary)
    }

    fun `test a string body is sent as text`() {
        val d = MockFiles.parse("""{"kind":"http","when":{"method":"get","path":"/x"},"response":{"body":"plain","headers":{"content-type":"text/plain"}}}""")
        assertEquals("plain", d.bodyText)
        assertEquals("GET", d.method)
        assertEquals(mapOf("content-type" to "text/plain"), d.effectiveHeaders)
    }

    fun `test mistakes are reported`() {
        fun error(text: String) = runCatching { MockFiles.parse(text) }.exceptionOrNull()?.message
        assertEquals("\"kind\" is missing: \"feign\" or \"http\"", error("{}"))
        assertEquals("A Feign mock needs \"target\", the client method", error("""{"kind":"feign"}"""))
        assertEquals("An endpoint mock needs \"when.path\"", error("""{"kind":"http"}"""))
        assertEquals("\"fault\" works for Feign mocks only", error("""{"kind":"http","when":{"path":"/a"},"response":{"fault":"timeout"}}"""))
        assertEquals("\"fault\" is \"timeout\" or \"refused\"", error("""{"kind":"feign","target":"A#b()","response":{"fault":"boom"}}"""))
    }

    fun `test a written file reads back the same and can be switched off`() {
        val d = MockFiles.parse(feign)
        val text = MockFiles.toText(d)
        assertEquals(d, MockFiles.parse(text))
        assertTrue(text.contains("\"fault\": null")) // the options are visible in the file
        val off = MockFiles.withEnabled(text, false)
        assertEquals(d.copy(enabled = false), MockFiles.parse(off))
        val keys = JsonParser.parseString(MockFiles.withEnabled(feign, false)).asJsonObject.keySet().toList()
        assertEquals(listOf("kind", "target", "label", "enabled", "when", "response"), keys)
    }

    fun `test the most specific mock is tried first and the form carries everything`() {
        val any = MockFiles.parse("""{"kind":"feign","target":"OrdersClient#get(String)"}""")
        val specific = MockFiles.parse(feign)
        val off = any.copy(enabled = false)
        val dir = Files.createTempDirectory("mocks")
        try {
            val entries = listOf(
                MockEntry(dir.resolve("a.json"), any, null), MockEntry(dir.resolve("b.json"), specific, null),
                MockEntry(dir.resolve("c.json"), off, null), MockEntry(dir.resolve("d.json"), null, "broken"),
            )
            val active = MockFiles.active(entries)
            assertEquals(listOf("b", "a"), active.map { it.first })

            val (form, version) = MockFiles.form(active)
            val fields = form.split("&").associate { pair ->
                val (k, v) = pair.split("=", limit = 2)
                URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
            }
            assertEquals(version, fields["version"])
            assertEquals("2", fields["count"])
            assertEquals("OrdersClient#get(String)", fields["0.key"])
            assertEquals("full", fields["0.q.view"])
            assertEquals("404", fields["0.status"])
            assertEquals("Content-Type: application/json", fields["0.rh.0"])
            assertEquals("300", fields["0.delayMs"])
            assertEquals("a", fields["1.label"])
            assertEquals(version, MockFiles.form(active).second)
            assertFalse(version == MockFiles.form(emptyList()).second)

            Files.writeString(dir.resolve("x.json"), feign)
            Files.writeString(dir.resolve("y.json"), "{ not json")
            val read = MockFiles.read(dir)
            assertEquals(specific, read.single { it.name == "x" }.definition)
            assertNotNull(read.single { it.name == "y" }.error)
            assertEquals("x-2.json", MockFiles.freeName(dir, "x"))
            assertEquals("OrdersClient.get.json", MockFiles.freeName(dir, "OrdersClient.get"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
