package dev.tendril.http

import com.intellij.ide.scratch.ScratchRootType
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import dev.tendril.ui.Editors
import dev.tendril.ui.Palette
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JToggleButton

/** Something that can open a request for editing. */
internal interface RequestHost {
    fun sendRequest(text: String)
}

/** Requests are written and run in a real `.http` editor tab; this is where they are opened. */
internal object HttpScratch {
    private const val ENV_KEY = "tendril.http.environment"

    fun selectedEnvironment(project: Project): String? =
        PropertiesComponent.getInstance(project).getValue(ENV_KEY)?.takeIf { it.isNotBlank() }

    fun selectEnvironment(project: Project, name: String?) =
        PropertiesComponent.getInstance(project).setValue(ENV_KEY, name.orEmpty())

    fun variables(project: Project): Map<String, String> =
        selectedEnvironment(project)?.let { HttpEnvironments.load(project)[it] }.orEmpty()

    /** Opens [text] as a scratch `.http` file; variables nobody defines get an empty `@name = ` line to fill in. */
    fun open(project: Project, text: String, fileName: String = "request.http") {
        // Creating a file is a write action; callers may be in a "modality any" callback, which is not write-safe.
        ApplicationManager.getApplication().invokeLater({ create(project, text, fileName) }, ModalityState.nonModal(), project.disposed)
    }

    private fun create(project: Project, text: String, fileName: String) {
        val known = variables(project).keys + HttpRequestText.fileVariables(text).keys
        val missing = HttpRequestText.variables(text).filter { it !in known }
        val content = missing.joinToString("") { "@$it = \n" } + text
        val file = ScratchRootType.getInstance().createScratchFile(project, fileName, null, content) ?: return
        // the caret goes to the first request line, below the variable lines to fill in
        val editors = FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, file, missing.size, 0), true)
        editors?.caretModel?.moveToLogicalPosition(LogicalPosition(missing.size, 0))
    }
}

/**
 * The response of the request run from a `.http` editor: status, body (formatted) and headers. The request itself
 * lives in the editor; Ctrl+Enter or the gutter arrow sends it.
 */
internal class HttpResponsePanel(private val project: Project) : JPanel(BorderLayout()) {
    private val environments = ComboBox<String>()
    private val status = SimpleColoredComponent()
    private val body = Editors.viewer(project)
    private val headers = Editors.viewer(project)
    private val headerToggle = JToggleButton("Headers").apply {
        horizontalAlignment = JToggleButton.LEFT
        isContentAreaFilled = false
        border = dev.tendril.ui.Chrome.topRow(3, 8)
        isVisible = false
    }
    private var headerCount = 0
    private var updatingEnvironments = false

    init {
        reloadEnvironments()
        environments.toolTipText = "Variables of this environment fill the {{placeholders}} of a request"
        environments.addActionListener {
            if (!updatingEnvironments) HttpScratch.selectEnvironment(project, (environments.selectedItem as? String)?.takeIf { it != NONE })
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(JBLabel("Env").apply { foreground = JBColor.GRAY; font = font.deriveFont(Font.PLAIN) })
            add(environments)
        }
        val header = JPanel(BorderLayout()).apply {
            border = dev.tendril.ui.Chrome.topRow(4, 8)
            add(status.apply { border = JBUI.Borders.emptyRight(12) }, BorderLayout.WEST)
            add(left, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        val headerSection = JPanel(BorderLayout()).apply {
            add(headerToggle, BorderLayout.NORTH)
            add(headers, BorderLayout.CENTER)
        }
        headers.isVisible = false
        headerToggle.addActionListener {
            updateHeaderVisibility()
            headerSection.revalidate()
            headerSection.repaint()
        }
        val response = JPanel(BorderLayout()).apply {
            add(headerSection, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(JBLabel("BODY").apply {
                    foreground = JBColor.GRAY
                    font = com.intellij.util.ui.UIUtil.getLabelFont(com.intellij.util.ui.UIUtil.FontSize.MINI)
                    border = dev.tendril.ui.Chrome.topRow(3, 8)
                }, BorderLayout.NORTH)
                add(body, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
        add(response, BorderLayout.CENTER)
        showHint("Open a .http file and press Ctrl+Enter, or use the arrow in its gutter. The response appears here.")
    }

    private fun reloadEnvironments() {
        updatingEnvironments = true
        val names = HttpEnvironments.load(project).keys.toList()
        environments.removeAllItems()
        environments.addItem(NONE)
        names.forEach { environments.addItem(it) }
        environments.selectedItem = HttpScratch.selectedEnvironment(project)?.takeIf { it in names } ?: NONE
        updatingEnvironments = false
    }

    /** Runs a request taken from an editor file; variables come from the environment and the file's `@name = value` lines. */
    fun runBlock(block: String, fileText: String) {
        reloadEnvironments()
        val variables = HttpScratch.variables(project) + HttpRequestText.fileVariables(fileText)
        val parsed = HttpRequestText.parse(block, variables).getOrElse {
            showError(it.message ?: "The request could not be read")
            return
        }
        execute(parsed)
    }

    private fun execute(parsed: ParsedRequest) {
        showHint("Sending ${parsed.method} ${parsed.url}")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { HttpExecutor.send(parsed) }
            ApplicationManager.getApplication().invokeLater({
                result.fold({ show(it) }, { showError("The request failed: ${it.message ?: it.javaClass.simpleName}") })
            }, ModalityState.any())
        }
    }

    internal fun show(r: HttpResult) {
        val color = when (r.status) {
            in 200..299 -> Palette.OK
            in 300..399 -> Palette.REDIRECT
            else -> Palette.FAILED
        }
        status.clear()
        status.append("${r.status} ${HttpExecutor.reason(r.status)}".trim(), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
        status.append("   ${r.millis} ms", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        status.append("   ${size(r.bytes)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        val pretty = HttpExecutor.pretty(r.body).take(MAX_BODY)
        Editors.show(body, pretty, Editors.typeOf(pretty))
        Editors.show(headers, r.headers.joinToString("\n") { "${it.first}: ${it.second}" }, PlainTextFileType.INSTANCE)
        headerCount = r.headers.size
        headerToggle.isVisible = headerCount > 0
        headerToggle.isSelected = headerCount in 1..3
        updateHeaderVisibility()
        val lineHeight = headers.editor?.lineHeight ?: headers.getFontMetrics(headers.font).height
        headers.preferredSize = java.awt.Dimension(0, lineHeight * headerCount.coerceAtMost(6) + JBUI.scale(6))
        revalidate()
        repaint()
    }

    private fun updateHeaderVisibility() {
        val expanded = headerToggle.isSelected
        headerToggle.text = if (expanded) "Hide headers ($headerCount)" else "Show headers ($headerCount)"
        headers.isVisible = expanded
    }

    private fun showError(message: String) {
        status.clear()
        status.append(message, SimpleTextAttributes.ERROR_ATTRIBUTES)
        Editors.show(body, "", PlainTextFileType.INSTANCE)
        Editors.show(headers, "", PlainTextFileType.INSTANCE)
        headerToggle.isVisible = false
        headers.isVisible = false
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

/** The window the response of a `.http` request is shown in, below the editor. */
class HttpResponseToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(HttpResponsePanel(project), "", false))
    }

    companion object {
        const val ID = "HTTP Response"

        /** Runs [block], a request taken from a `.http` file ([fileText]), and shows the response. */
        fun run(project: Project, block: String, fileText: String) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            toolWindow.activate {
                (toolWindow.contentManager.contents.firstOrNull()?.component as? HttpResponsePanel)?.runBlock(block, fileText)
            }
        }
    }
}
