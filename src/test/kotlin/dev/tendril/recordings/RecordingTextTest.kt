package dev.tendril.recordings

import com.google.gson.JsonParser
import dev.tendril.http.HttpResult
import org.junit.Assert.*
import org.junit.Test

class RecordingTextTest {

    private fun recording(json: String) = Recording.fromJson(JsonParser.parseString(json).asJsonObject)!!

    private val incoming = recording(
        """{"kind":"http_in","time":1000,"durationMs":12,"flow":3,"method":"POST","url":"http://localhost:8081/playground/api/brands?x=1",
        "headers":[["host","localhost:8081"],["authorization","***"],["content-type","application/json"],["content-length","20"],["accept-encoding","gzip"]],
        "body":"{\"name\":\"Acme\"}","bodyEncoding":"text","status":200,
        "responseHeaders":[["Content-Type","application/json"]],"responseBody":"{\"id\":1}","handler":"BrandController.create","pattern":"/api/brands"}""",
    )

    @Test
    fun `reads an incoming http event`() {
        assertEquals(RecordingKind.INCOMING, incoming.kind)
        assertEquals("POST", incoming.method)
        assertEquals(200, incoming.status)
        assertEquals("BrandController.create", incoming.handler)
        assertEquals(5, incoming.headers.size)
        assertEquals(3L, incoming.flow)
    }

    @Test
    fun `reads a message event`() {
        val m = recording(
            """{"kind":"message","transport":"kafka","time":5,"destination":"orders","routingKey":"k1","partition":2,"offset":77,
            "headers":[],"body":"hello","bodyEncoding":"text","replayable":false,"valueType":"com.acme.Order"}""",
        )
        assertEquals(RecordingKind.MESSAGE, m.kind)
        assertEquals("orders", m.destination)
        assertFalse(m.replayable)
        assertTrue(m.detail!!.contains("partition 2"))
        assertTrue(m.detail!!.contains("com.acme.Order"))
    }

    @Test
    fun `ignores events of an unknown kind`() {
        assertNull(Recording.fromJson(JsonParser.parseString("""{"kind":"hello","control":5}""").asJsonObject))
    }

    @Test
    fun `a replay drops connection headers and masked values`() {
        val request = RecordingText.asRequest(incoming)
        assertEquals("POST", request.method)
        assertEquals(listOf("content-type" to "application/json"), request.headers)
        assertEquals("{\"name\":\"Acme\"}", request.body)
    }

    @Test
    fun `the request editor text turns masked headers into a placeholder comment`() {
        val text = RecordingText.httpFile(incoming)
        assertTrue(text.contains("POST http://localhost:8081/playground/api/brands?x=1"))
        assertTrue(text.contains("# authorization: {{authorization}}"))
        assertTrue(text.contains("content-type: application/json"))
        assertFalse(text.contains("content-length"))
        assertFalse(text.contains("\nhost:"))
    }

    @Test
    fun `compares a replay with the recording`() {
        val same = RecordingText.comparison(incoming, HttpResult(200, emptyList(), "{\"id\":1}", 5, 8))
        assertTrue(same.startsWith("Same as recorded"))

        val body = RecordingText.comparison(incoming, HttpResult(200, emptyList(), "{\"id\":2}", 5, 8))
        assertTrue(body.startsWith("Same status, different body"))
        assertTrue(body.contains("recorded body"))

        val status = RecordingText.comparison(incoming, HttpResult(500, emptyList(), "", 5, 0))
        assertTrue(status.startsWith("Different: recorded 200, now 500"))
    }

    @Test
    fun `shortens urls for the list`() {
        assertEquals("/playground/api/brands?x=1", RecordingText.shortUrl(incoming, RecordingKind.INCOMING))
        assertEquals("localhost/playground/api/brands?x=1", RecordingText.shortUrl(incoming, RecordingKind.OUTGOING))
    }
}
