package dev.tendril.recordings

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RecordingStoreTest : BasePlatformTestCase() {

    private fun http(id: Int, kind: String = "http_in") = Recording.fromJson(
        JsonParser.parseString("""{"kind":"$kind","time":$id,"method":"GET","url":"http://localhost/$id","status":200}""").asJsonObject,
    )!!

    private fun message(id: Int) = Recording.fromJson(
        JsonParser.parseString("""{"kind":"message","transport":"rabbit","time":$id,"destination":"q$id"}""").asJsonObject,
    )!!

    fun `test keeps the latest ten of each kind with the newest first`() {
        val store = RecordingStore(project)
        for (i in 1..13) store.add(http(i))
        val kept = store.snapshot(RecordingKind.INCOMING)
        assertEquals(RecordingStore.MAX_PER_KIND, kept.size)
        assertEquals(13L, kept.first().time)
        assertEquals(4L, kept.last().time)
    }

    fun `test the kinds are limited independently`() {
        val store = RecordingStore(project)
        for (i in 1..12) {
            store.add(http(i))
            store.add(http(i, "http_out"))
            store.add(message(i))
        }
        assertEquals(10, store.snapshot(RecordingKind.INCOMING).size)
        assertEquals(10, store.snapshot(RecordingKind.OUTGOING).size)
        assertEquals(10, store.snapshot(RecordingKind.MESSAGE).size)
    }

    fun `test clear empties every list`() {
        val store = RecordingStore(project)
        store.add(http(1))
        store.add(message(1))
        store.clear()
        assertTrue(RecordingKind.entries.all { store.snapshot(it).isEmpty() })
    }

    fun `test listeners hear about additions and contact`() {
        val store = RecordingStore(project)
        var calls = 0
        store.addListener(testRootDisposable) { calls++ }
        store.add(http(1))
        store.touch(5555)
        store.clear()
        assertEquals(3, calls)
        assertEquals(5555, store.controlPort)
        assertTrue(store.lastContact > 0)
    }

    fun `test the control port is kept when a later contact does not carry one`() {
        val store = RecordingStore(project)
        store.touch(4444)
        store.touch(null)
        assertEquals(4444, store.controlPort)
    }
}
