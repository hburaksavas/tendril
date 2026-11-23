package dev.tendril.http

import org.junit.Assert.*
import org.junit.Test

class HttpRequestTextTest {

    private val two = "### First\nGET http://a/one\n\n### Second\nPOST http://a/two\nContent-Type: application/json\n\n{\"x\":1}\n"

    @Test
    fun `picks the request that contains the line`() {
        assertTrue(HttpRequestText.block(two, 1).contains("GET http://a/one"))
        assertFalse(HttpRequestText.block(two, 1).contains("POST"))
        assertTrue(HttpRequestText.block(two, 4).contains("POST http://a/two"))
        assertFalse(HttpRequestText.block(two, 4).contains("GET"))
        // the separator line itself belongs to the request below it
        assertTrue(HttpRequestText.block(two, 3).contains("POST"))
    }

    @Test
    fun `a caret above the first separator uses the first request`() {
        val text = "@host = x\n\n### A\nGET http://a\n### B\nGET http://b\n"
        assertTrue(HttpRequestText.block(text, 0).contains("GET http://a"))
    }

    @Test
    fun `a file without separators is one request`() {
        assertEquals("GET http://a", HttpRequestText.block("GET http://a", 0))
    }

    @Test
    fun `parses method url headers and body`() {
        val r = HttpRequestText.parse(HttpRequestText.block(two, 4), emptyMap()).getOrThrow()
        assertEquals("POST", r.method)
        assertEquals("http://a/two", r.url)
        assertEquals(listOf("Content-Type" to "application/json"), r.headers)
        assertEquals("{\"x\":1}", r.body)
    }

    @Test
    fun `a line with only a url is a GET`() {
        val r = HttpRequestText.parse("http://a/only", emptyMap()).getOrThrow()
        assertEquals("GET", r.method)
        assertEquals("http://a/only", r.url)
        assertNull(r.body)
    }

    @Test
    fun `comments and the title are not part of the request`() {
        val text = "### Title\n# a comment\n// another\nGET http://a\n# Authorization: Bearer {{token}}\nAccept: */*\n"
        val r = HttpRequestText.parse(text, emptyMap()).getOrThrow()
        assertEquals("GET", r.method)
        assertEquals(listOf("Accept" to "*/*"), r.headers)
    }

    @Test
    fun `substitutes variables in url headers and body`() {
        val text = "POST {{host}}/items/{{id}}\nX-Tenant: {{tenant}}\n\n{\"n\":\"{{name}}\"}"
        val vars = mapOf("host" to "http://h", "id" to "7", "tenant" to "t1", "name" to "Acme")
        val r = HttpRequestText.parse(text, vars).getOrThrow()
        assertEquals("http://h/items/7", r.url)
        assertEquals("t1", r.headers.single().second)
        assertEquals("{\"n\":\"Acme\"}", r.body)
    }

    @Test
    fun `reports every undefined variable by name`() {
        val error = HttpRequestText.parse("GET {{host}}/x/{{id}}", mapOf("host" to "h")).exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("id"))
        assertFalse(error.message!!.contains("host,"))
    }

    @Test
    fun `an empty value counts as undefined`() {
        assertTrue(HttpRequestText.parse("GET http://a/{{id}}", mapOf("id" to "")).isFailure)
    }

    @Test
    fun `dynamic variables are generated`() {
        val r = HttpRequestText.parse("GET http://a/{{\$uuid}}?t={{\$timestamp}}&n={{\$randomInt}}", emptyMap()).getOrThrow()
        assertTrue(Regex("http://a/[0-9a-f-]{36}\\?t=\\d+&n=\\d+").matches(r.url))
    }

    @Test
    fun `lists variables without the dynamic ones`() {
        assertEquals(listOf("host", "id"), HttpRequestText.variables("GET {{host}}/{{id}}/{{host}}/{{\$uuid}}"))
    }

    @Test
    fun `a commented line does not require its variables`() {
        val text = "GET {{host}}/x\n# Authorization: Bearer {{token}}\n// X: {{other}}"
        assertEquals(listOf("host"), HttpRequestText.variables(text))
    }

    @Test
    fun `reads file variables`() {
        val vars = HttpRequestText.fileVariables("@host = http://h\n@name=Acme corp\nGET {{host}}\n")
        assertEquals("http://h", vars["host"])
        assertEquals("Acme corp", vars["name"])
    }

    @Test
    fun `file variable lines are skipped when finding the request line`() {
        val r = HttpRequestText.parse("@host = http://h\nGET {{host}}/x", mapOf("host" to "http://h")).getOrThrow()
        assertEquals("http://h/x", r.url)
    }

    @Test
    fun `an empty request is an error with a hint`() {
        val error = HttpRequestText.parse("# nothing here\n", emptyMap()).exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("GET"))
    }
}
