package dev.tendril

import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorTextField
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import dev.tendril.actuator.ActuatorNode
import dev.tendril.actuator.ActuatorPanel
import dev.tendril.recordings.HttpRecordingRenderer
import dev.tendril.recordings.Recording
import dev.tendril.recordings.RecordingKind
import dev.tendril.recordings.RecordingPane
import dev.tendril.recordings.RecordingStore
import dev.tendril.recordings.RecordingsPanel
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class RequestViewsTest : BasePlatformTestCase() {
    private fun Component.descendants(): Sequence<Component> = sequence {
        yield(this@descendants)
        if (this@descendants is Container) components.forEach { yieldAll(it.descendants()) }
    }

    private fun recording(path: String) = Recording.fromJson(JsonObject().apply {
        addProperty("kind", "http_in")
        addProperty("url", "http://localhost/$path")
        addProperty("method", "GET")
        addProperty("status", 200)
    })!!

    fun `test concurrent replays keep their own results and button state`() {
        val replies = mutableMapOf<Recording, (String) -> Unit>()
        val pane = RecordingPane(project, RecordingKind.INCOMING, testRootDisposable,
            sendReplay = { r, done -> replies[r] = done })
        val a = recording("a")
        val b = recording("b")
        pane.show(listOf(a, b))
        val list = pane.descendants().filterIsInstance<JList<*>>().first { it.model.size == 2 && it.model.getElementAt(0) === a }
        val button = pane.descendants().filterIsInstance<ActionLink>().first { it.text == "Replay request" }
        button.doClick()
        val result = pane.descendants().filterIsInstance<EditorTextField>().first { it.text == "Sending…" }
        list.selectedIndex = 1
        assertEquals("", result.text)
        button.doClick()
        replies.getValue(a)("Result A")
        assertEquals("Sending…", result.text)
        assertFalse(button.isEnabled)
        replies.getValue(b)("Result B")
        assertEquals("Result B", result.text)
        list.selectedIndex = 0
        assertEquals("Result A", result.text)
        assertTrue(button.isEnabled)
    }

    fun `test cancelling and confirming clear through the recordings toolbar`() {
        val pane = RecordingsPanel(project)
        Disposer.register(testRootDisposable, pane)
        val store = RecordingStore.getInstance(project)
        val first = recording("first")
        store.add(first)
        val clear = pane.descendants().filterIsInstance<ActionToolbar>()
            .flatMap { it.actionGroup.getChildren(null).asSequence() }
            .first { it.templateText == "Clear all recordings…" }
        var choice = Messages.CANCEL
        TestDialogManager.setTestDialog({ message ->
            assertTrue(message, "Incoming: 1" in message && "Outgoing: 0" in message && "Messages: 0" in message)
            choice
        }, testRootDisposable)
        try {
            clear.actionPerformed(TestActionEvent.createTestEvent(clear))
            assertEquals(listOf(first), store.snapshot(RecordingKind.INCOMING))
            choice = Messages.OK
            clear.actionPerformed(TestActionEvent.createTestEvent(clear))
            assertEmpty(store.snapshot(RecordingKind.INCOMING))
        } finally {
            store.clear()
        }
    }

    fun `test stale Actuator success and error cannot replace the latest refresh`() {
        val requests = mutableListOf<Pair<String, (Result<JsonObject>) -> Unit>>()
        val pane = ActuatorPanel(project) { address, done -> requests += address to done }
        Disposer.register(testRootDisposable, pane)
        val url = pane.descendants().filterIsInstance<SearchTextField>().first { it.toolTipText?.startsWith("Actuator base URL") == true }
        url.text = "http://a"
        pane.refreshAll()
        url.text = "http://b"
        pane.refreshAll()
        pane.refreshAll()
        assertEquals(listOf("http://a/health", "http://b/health", "http://b/health"), requests.map { it.first })
        fun status(value: String) = Result.success(JsonObject().apply { addProperty("status", value) })
        val tree = pane.descendants().filterIsInstance<JTree>().first()
        requests[0].second(status("OLD"))
        assertEquals(0, tree.rowCount)
        requests[2].second(status("UP"))
        requests[1].second(Result.failure(IllegalStateException("late failure")))
        val row = (tree.getPathForRow(0).lastPathComponent as DefaultMutableTreeNode).userObject as ActuatorNode
        assertEquals("application: UP", row.label)
    }

    fun `test recording row moves metadata down when narrow and keeps route suffix`() {
        val r = recording("inventory/" + "long-segment/".repeat(5) + "sku-9")
        val list = JList(arrayOf(r))
        val renderer = HttpRecordingRenderer()
        fun layout(width: Int): List<JLabel> {
            list.fixedCellWidth = width
            renderer.getListCellRendererComponent(list, r, 0, false, false)
            renderer.setSize(width, renderer.preferredSize.height)
            renderer.doLayout()
            return renderer.components.filterIsInstance<JLabel>()
        }
        val narrow = layout(240)
        assertTrue(narrow.any { "…" in it.text && it.text.endsWith("sku-9") })
        assertTrue(narrow.first { " ms" in it.text }.y > narrow.first { it.text == "200" }.y)
        val wide = layout(1400)
        assertEquals(wide.first { it.text == "200" }.y, wide.first { " ms" in it.text }.y)
        assertTrue(wide.any { it.text.startsWith("/inventory/") && "…" !in it.text })
    }
}
