package dev.tendril.http

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/** A built-in HTTP client: edit a request in `.http` syntax, pick an environment, send it, read the response. */
class HttpToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HttpPanel(project)
        Disposer.register(toolWindow.disposable, panel)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }

    companion object {
        const val ID = "HTTP Requests"

        /** Shows the tool window with [text] as the request. */
        fun open(project: Project, text: String) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            toolWindow.activate {
                (toolWindow.contentManager.contents.firstOrNull()?.component as? HttpPanel)?.setRequest(text)
            }
        }
    }
}

private class HttpPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val request = JBTextArea(12, 60)
    private val environments = ComboBox<String>()
    private val send = JButton("Send", AllIcons.Actions.Execute)
    private val status = JBLabel(" ")
    private val body = readOnlyArea()
    private val headers = readOnlyArea()

    init {
        request.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        request.text = "### Example\nGET http://localhost:8080/actuator/health\n"
        request.registerKeyboardAction(
            { send() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_FOCUSED,
        )
        send.toolTipText = "Send the request under the caret (Ctrl+Enter)"
        send.addActionListener { send() }
        reloadEnvironments()

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        bar.add(send)
        bar.add(JBLabel("Environment:"))
        bar.add(environments)
        bar.add(JBLabel("<html><small>Variables: <code>@name = value</code> lines or http-client.env.json</small></html>"))

        val tabs = JBTabbedPane()
        tabs.addTab("Body", JBScrollPane(body))
        tabs.addTab("Headers", JBScrollPane(headers))
        val response = JPanel(BorderLayout())
        response.add(status, BorderLayout.NORTH)
        response.add(tabs, BorderLayout.CENTER)

        val splitter = JBSplitter(true, 0.4f)
        splitter.firstComponent = JBScrollPane(request)
        splitter.secondComponent = response
        add(bar, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
    }

    override fun dispose() {}

    /** Puts [text] into the editor; variables nobody defines get an empty `@name = ` line to fill in. */
    fun setRequest(text: String) {
        reloadEnvironments()
        val known = selectedVariables().keys + HttpRequestText.fileVariables(text).keys
        val missing = HttpRequestText.variables(text).filter { it !in known }
        val prefix = missing.joinToString("") { "@$it = \n" }
        request.text = prefix + text
        runCatching { request.caretPosition = request.getLineStartOffset(missing.size) }
        request.requestFocusInWindow()
    }

    private fun reloadEnvironments() {
        val previous = environments.selectedItem as? String
        val names = HttpEnvironments.load(project).keys.toList()
        environments.removeAllItems()
        environments.addItem(NONE)
        names.forEach { environments.addItem(it) }
        environments.selectedItem = previous?.takeIf { it in names } ?: NONE
    }

    private fun selectedVariables(): Map<String, String> {
        val name = environments.selectedItem as? String ?: return emptyMap()
        return HttpEnvironments.load(project)[name].orEmpty()
    }

    private fun send() {
        val text = request.text
        val line = runCatching { request.getLineOfOffset(request.caretPosition) }.getOrDefault(0)
        val variables = selectedVariables() + HttpRequestText.fileVariables(text)
        val parsed = HttpRequestText.parse(HttpRequestText.block(text, line), variables).getOrElse {
            showError(it.message ?: "Invalid request")
            return
        }

        send.isEnabled = false
        status.foreground = JBColor.GRAY
        status.text = "Sending ${parsed.method} ${parsed.url} …"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { HttpExecutor.send(parsed) }
            ApplicationManager.getApplication().invokeLater({
                send.isEnabled = true
                result.fold({ show(it) }, { showError("Request failed: ${it.message ?: it.javaClass.simpleName}") })
            }, ModalityState.any())
        }
    }

    private fun show(r: HttpResult) {
        status.foreground = when (r.status) {
            in 200..299 -> JBColor(0x2E7D32, 0x6AAB73)
            in 300..399 -> JBColor(0xB26A00, 0xE0A44D)
            else -> JBColor(0xC62828, 0xF28B82)
        }
        status.text = "${r.status} ${HttpExecutor.reason(r.status)}  ·  ${r.millis} ms  ·  ${size(r.bytes)}"
        body.text = HttpExecutor.pretty(r.body).take(MAX_BODY)
        body.caretPosition = 0
        headers.text = r.headers.joinToString("\n") { "${it.first}: ${it.second}" }
        headers.caretPosition = 0
    }

    private fun showError(message: String) {
        status.foreground = JBColor(0xC62828, 0xF28B82)
        status.text = message
        body.text = ""
        headers.text = ""
    }

    private fun size(bytes: Int) = if (bytes < 1024) "$bytes B" else "%.1f KB".format(bytes / 1024.0)

    private fun readOnlyArea() = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
    }

    private companion object {
        const val NONE = "(none)"
        const val MAX_BODY = 1_000_000
    }
}
