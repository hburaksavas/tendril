package dev.tendril.feign

import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import dev.tendril.SpringTestCase
import dev.tendril.config.TendrilSettings
import dev.tendril.http.RequestHost
import dev.tendril.ui.RowPainter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

class FeignAddressActionTest : SpringTestCase() {
    fun `test unresolved client opens the address dialog by mouse and keyboard`() {
        myFixture.addClass("""
            package app;
            @org.springframework.cloud.openfeign.FeignClient(name = "address-action")
            public interface LedgerClient {}
        """.trimIndent())
        val panel = FeignPanel(project, object : RequestHost { override fun sendRequest(text: String) {} }) {}
        Disposer.register(testRootDisposable, panel)
        panel.show(listOf("(default)"), FeignScanner.scan(project).map { FeignResolver.resolve(project, it, null) })
        val tree = UIUtil.findComponentOfType(panel, Tree::class.java)!!
        tree.setSize(600, 200)
        tree.setSelectionRow(0)
        val bounds = tree.getRowBounds(0)
        val node = tree.getPathForRow(0).lastPathComponent
        val cell = tree.cellRenderer.getTreeCellRendererComponent(tree, node, true, true, true, 0, true) as RowPainter
        assertEquals(listOf("No address   ", "Set address…"), cell.secondary.map { it.text })
        val answers = ArrayDeque(listOf("https://first.example", "https://second.example"))
        val old = TestDialogManager.setTestInputDialog { answers.removeFirst() }
        val urls = TendrilSettings.getInstance(project).state.serviceUrls
        try {
            val enter = KeyEvent(tree, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n')
            tree.keyListeners.forEach { it.keyPressed(enter) }
            assertEquals("https://first.example", urls["address-action"])
            val mouse = MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, 0,
                bounds.x + cell.preferredSize.width - 18, bounds.y + bounds.height / 2, 1, false, MouseEvent.BUTTON1)
            tree.mouseListeners.forEach { it.mouseClicked(mouse) }
            assertEquals("https://second.example", urls["address-action"])
        } finally {
            TestDialogManager.setTestInputDialog(old)
            urls.remove("address-action")
        }
    }
}
