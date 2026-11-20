package dev.tendril.http

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAwareAction
import dev.tendril.ui.Editors
import dev.tendril.ui.Palette
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import com.intellij.ui.OnePixelSplitter

/** Something that can show a request in the HTTP section of the endpoints window. */
internal interface RequestHost {
    fun sendRequest(text: String)
}

/**
 * The request section at the bottom of the endpoints window: a `.http`-style editor on top, the response below.
 * Ctrl+Enter sends the request under the caret.
 */
internal class HttpRequestPanel(private val project: Project, private val onClose: () -> Unit) : JPanel(BorderLayout()) {
    private val request = Editors.editable(project, HttpFileType)
    private val environments = ComboBox<String>()
    private val send = JButton("Send", AllIcons.Actions.Execute)
    private val status = SimpleColoredComponent()
    private val body = Editors.viewer(project)
    private val headers = Editors.viewer(project)

    init {
        DumbAwareAction.create { send() }.registerCustomShortcutSet(CommonShortcuts.CTRL_ENTER, request)
        send.toolTipText = "Send the request under the caret (Ctrl+Enter)"
        send.addActionListener { send() }
        reloadEnvironments()

        val close = JButton(AllIcons.Actions.Close).apply {
            toolTipText = "Hide the request section"
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusable = false
            addActionListener { onClose() }
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(JBLabel("Request").apply { font = font.deriveFont(Font.BOLD) })
            add(JBLabel("Environment").apply { foreground = JBColor.GRAY })
            add(environments)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(send)
            add(close)
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(3, 8)
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }

        val tabs = JBTabbedPane()
        tabs.addTab("Body", body)
        tabs.addTab("Headers", headers)
        val response = JPanel(BorderLayout()).apply {
            add(status.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }

        val splitter = OnePixelSplitter(true, "tendril.http.splitter", 0.4f)
        splitter.firstComponent = request
        splitter.secondComponent = response
        add(header, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        showHint("Write a request, then press Ctrl+Enter. Use @name = value lines or http-client.env.json for {{variables}}.")
    }

    /** Puts [text] into the editor; variables nobody defines get an empty `@name = ` line to fill in. */
    fun setRequest(text: String) {
        reloadEnvironments()
        val known = selectedVariables().keys + HttpRequestText.fileVariables(text).keys
        val missing = HttpRequestText.variables(text).filter { it !in known }
        request.text = missing.joinToString("") { "@$it = \n" } + text
        // the caret goes to the first request line, below the variable lines to fill in
        request.editor?.caretModel?.moveToLogicalPosition(LogicalPosition(missing.size, 0))
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
        val line = request.editor?.caretModel?.logicalPosition?.line ?: 0
        val variables = selectedVariables() + HttpRequestText.fileVariables(text)
        val parsed = HttpRequestText.parse(HttpRequestText.block(text, line), variables).getOrElse {
            showError(it.message ?: "The request could not be read")
            return
        }
        execute(parsed)
    }

    /** Runs a request taken from an editor file; variables come from the environment and the file's `@name = value` lines. */
    fun runBlock(block: String, fileText: String) {
        reloadEnvironments()
        val variables = selectedVariables() + HttpRequestText.fileVariables(fileText)
        val parsed = HttpRequestText.parse(block, variables).getOrElse {
            showError(it.message ?: "The request could not be read")
            return
        }
        execute(parsed)
    }

    private fun execute(parsed: ParsedRequest) {
        send.isEnabled = false
        showHint("Sending ${parsed.method} ${parsed.url}")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { HttpExecutor.send(parsed) }
            ApplicationManager.getApplication().invokeLater({
                send.isEnabled = true
                result.fold({ show(it) }, { showError("The request failed: ${it.message ?: it.javaClass.simpleName}") })
            }, ModalityState.any())
        }
    }

    private fun show(r: HttpResult) {
        val color = when (r.status) {
            in 200..299 -> Palette.OK
            in 300..399 -> Palette.REDIRECT
            else -> Palette.FAILED
        }
        status.clear()
        status.append("${r.status} ${HttpExecutor.reason(r.status)}".trim(), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
        status.append("   ${r.millis} ms   ${size(r.bytes)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        val pretty = HttpExecutor.pretty(r.body).take(MAX_BODY)
        Editors.show(body, pretty, Editors.typeOf(pretty))
        headers.text = r.headers.joinToString("\n") { "${it.first}: ${it.second}" }
    }

    private fun showError(message: String) {
        status.clear()
        status.append(message, SimpleTextAttributes.ERROR_ATTRIBUTES)
        Editors.show(body, "", PlainTextFileType.INSTANCE)
        Editors.show(headers, "", PlainTextFileType.INSTANCE)
    }

    private fun showHint(message: String) {
        status.clear()
        status.append(message, SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun size(bytes: Int) = if (bytes < 1024) "$bytes B" else "%.1f KB".format(bytes / 1024.0)

    private companion object {
        const val NONE = "(none)"
        const val MAX_BODY = 1_000_000
    }
}
