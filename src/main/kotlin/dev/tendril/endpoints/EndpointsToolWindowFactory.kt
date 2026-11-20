package dev.tendril.endpoints

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.pom.Navigatable
import com.intellij.ui.*
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.tendril.feign.FeignPanel
import dev.tendril.http.HttpRequestPanel
import dev.tendril.http.RequestHost
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * One window for HTTP: the endpoints this application serves (Incoming), the Feign clients it calls (Outgoing),
 * and a request section at the bottom that opens when you send a request from either tab.
 */
class EndpointsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val root = ApiRootPanel(project)
        Disposer.register(toolWindow.disposable, root)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(root, "", false))
        root.refresh()
    }

    companion object {
        const val ID = "Spring Endpoints"

        /** Shows the window and opens [text] in its request section; [outgoing] also switches to the Outgoing tab. */
        fun sendRequest(project: Project, text: String, outgoing: Boolean) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            toolWindow.activate {
                val root = toolWindow.contentManager.contents.firstOrNull()?.component as? ApiRootPanel ?: return@activate
                if (outgoing) root.showOutgoing()
                root.sendRequest(text)
            }
        }

        /** Runs [block], a request taken from a `.http` file ([fileText]), and shows the response in the bottom section. */
        fun runRequest(project: Project, block: String, fileText: String) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            toolWindow.activate {
                val root = toolWindow.contentManager.contents.firstOrNull()?.component as? ApiRootPanel ?: return@activate
                root.runRequest(block, fileText)
            }
        }
    }
}

internal class ApiRootPanel(project: Project) : JPanel(BorderLayout()), Disposable, RequestHost {
    private val tabs = JBTabbedPane()
    private val incoming = EndpointsPanel(project, this) { tabs.setTitleAt(0, "Incoming  $it") }
    private val outgoing = FeignPanel(project, this) { tabs.setTitleAt(1, "Outgoing  $it") }
    private val request = HttpRequestPanel(project) { hideRequest() }
    private val splitter = OnePixelSplitter(true, "tendril.api.splitter", 0.55f)

    init {
        tabs.addTab("Incoming", incoming)
        tabs.addTab("Outgoing", outgoing)
        splitter.firstComponent = tabs
        add(splitter, BorderLayout.CENTER)
        Disposer.register(this, incoming)
        Disposer.register(this, outgoing)
    }

    override fun dispose() {}

    fun refresh() {
        incoming.refresh()
        outgoing.refresh()
    }

    fun showOutgoing() {
        tabs.selectedComponent = outgoing
    }

    override fun sendRequest(text: String) {
        request.setRequest(text)
        splitter.secondComponent = request
        revalidate()
        repaint()
    }

    fun runRequest(block: String, fileText: String) {
        splitter.secondComponent = request
        revalidate()
        repaint()
        request.runBlock(block, fileText)
    }

    private fun hideRequest() {
        splitter.secondComponent = null
        revalidate()
        repaint()
    }
}

/** A toolbar for a panel's own actions, laid out in the panel's top row. */
internal fun toolbarOf(place: String, target: JPanel, vararg actions: AnAction): javax.swing.JComponent {
    val toolbar = ActionManager.getInstance().createActionToolbar(place, DefaultActionGroup(*actions), true)
    toolbar.targetComponent = target
    toolbar.component.border = JBUI.Borders.empty()
    return toolbar.component
}

internal fun toggleAction(text: String, icon: Icon, get: () -> Boolean, set: (Boolean) -> Unit): AnAction =
    object : ToggleAction(text, null, icon), DumbAware {
        override fun isSelected(e: AnActionEvent) = get()
        override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

internal class EndpointsPanel(
    private val project: Project,
    private val host: RequestHost,
    private val onCount: (Int) -> Unit,
) : JPanel(BorderLayout()), Disposable {
    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    private val search = SearchTextField()
    private var all: List<Endpoint> = emptyList()
    private val props = PropertiesComponent.getInstance(project)

    var grouped: Boolean
        get() = props.getBoolean(GROUPED_KEY, false)
        set(value) {
            props.setValue(GROUPED_KEY, value, false)
            applyFilter()
        }

    /** Focus mode: only the endpoints declared in the file open in the active editor. */
    var focusMode: Boolean
        get() = props.getBoolean(FOCUS_KEY, false)
        set(value) {
            props.setValue(FOCUS_KEY, value, false)
            applyFilter()
        }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = EndpointRenderer()
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigate()
            }
        })
        PopupHandler.installPopupMenu(tree, contextMenu(), "TendrilEndpointsPopup")
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })

        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 4)
            add(
                toolbarOf(
                    "TendrilEndpoints", this@EndpointsPanel,
                    object : AnAction("Refresh", "Scan the project again", AllIcons.Actions.Refresh), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) = refresh()
                    },
                    toggleAction("Group by Controller", AllIcons.Actions.GroupBy, { grouped }) { grouped = it },
                    toggleAction("Only the Active Editor's Endpoints", AllIcons.General.Locate, { focusMode }) { focusMode = it },
                ),
                BorderLayout.WEST,
            )
            add(search, BorderLayout.CENTER)
        }
        add(top, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (focusMode) refresh() // also picks up endpoints added since the last scan
                }
            },
        )
    }

    override fun dispose() {}

    fun refresh() {
        ReadAction.nonBlocking<List<Endpoint>> { EndpointScanner.cached(project) }
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) {
                all = it
                onCount(it.size)
                applyFilter()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun applyFilter() {
        val q = search.text.trim().lowercase()
        val activeFile = if (focusMode) FileEditorManager.getInstance(project).selectedEditor?.file else null

        val shown = all.filter { e ->
            (q.isEmpty() || "${e.httpMethod} ${e.path} ${e.className}".lowercase().contains(q)) &&
                (!focusMode || (activeFile != null && e.method.virtualFile == activeFile))
        }

        val empty = tree.emptyText
        when {
            all.isEmpty() -> empty.setText("No endpoints found").appendLine("Classes annotated with @RestController or @Controller are listed here.")
            focusMode && activeFile == null -> empty.setText("No editor is open").appendLine("Open a controller to see its endpoints.")
            focusMode && shown.isEmpty() -> empty.setText("No endpoints in ${activeFile?.name}").appendLine("Turn off focus mode to see all endpoints.")
            else -> empty.setText("Nothing matches \"${search.text.trim()}\"")
        }

        root.removeAllChildren()
        if (grouped) {
            shown.groupBy { it.className }.toSortedMap().forEach { (controller, endpoints) ->
                val node = DefaultMutableTreeNode(controller)
                endpoints.forEach { node.add(DefaultMutableTreeNode(it)) }
                root.add(node)
            }
        } else {
            shown.forEach { root.add(DefaultMutableTreeNode(it)) }
        }
        treeModel.reload()
        if (grouped) TreeUtil.expandAll(tree)
    }

    private fun selectedEndpoint(): Endpoint? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Endpoint

    private fun navigate() {
        val m = selectedEndpoint()?.method?.element ?: return
        (m as? Navigatable)?.takeIf { it.canNavigate() }?.navigate(true)
    }

    private fun contextMenu(): ActionGroup {
        fun action(text: String, run: (Endpoint) -> Unit) = object : AnAction(text) {
            override fun actionPerformed(e: AnActionEvent) {
                selectedEndpoint()?.let(run)
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedEndpoint() != null
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        return DefaultActionGroup(
            action("Send Request") {
                host.sendRequest(ReadAction.compute<String, Throwable> { EndpointRequests.http(project, it) })
            },
            Separator.create(),
            action("Go to Source") { navigate() },
            action("Copy URL") { copy("URL", ReadAction.compute<String, Throwable> { EndpointRequests.url(project, it) }) },
            action("Copy as cURL") { copy("cURL command", ReadAction.compute<String, Throwable> { EndpointRequests.curl(project, it) }) },
            action("Open as .http File") { openHttpScratch(it) },
        )
    }

    private fun copy(what: String, text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        NotificationGroupManager.getInstance().getNotificationGroup("Tendril")
            ?.createNotification("$what copied to the clipboard", NotificationType.INFORMATION)?.notify(project)
    }

    private fun openHttpScratch(endpoint: Endpoint) {
        val text = ReadAction.compute<String, Throwable> { EndpointRequests.http(project, endpoint) }
        val fileName = "${endpoint.httpMethod}_${endpoint.path.trim('/').replace(Regex("[^A-Za-z0-9]+"), "_")}.http"
        EndpointRequests.openHttpScratch(project, fileName, text)
    }

    private companion object {
        const val GROUPED_KEY = "tendril.endpoints.grouped"
        const val FOCUS_KEY = "tendril.endpoints.focusMode"
    }
}

internal object HttpMethodColors {
    private val GREEN = JBColor(0x2E7D32, 0x6AAB73)
    private val ORANGE = JBColor(0xB26A00, 0xE0A44D)
    private val BLUE = JBColor(0x1E66F5, 0x6FA8FF)
    private val PURPLE = JBColor(0x7E57C2, 0xB39DDB)
    private val RED = JBColor(0xC62828, 0xF28B82)

    fun attributes(method: String): SimpleTextAttributes {
        val color = when (method) {
            "GET" -> GREEN
            "POST" -> ORANGE
            "PUT" -> BLUE
            "PATCH" -> PURPLE
            "DELETE" -> RED
            else -> JBColor.GRAY
        }
        return SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
    }
}

private class EndpointRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val item = node.userObject) {
            is Endpoint -> {
                append(item.httpMethod.padEnd(7), HttpMethodColors.attributes(item.httpMethod))
                append(item.path)
                append("   ${item.className}.${item.methodName}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is String -> {
                icon = AllIcons.Nodes.Class
                append(item, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${node.childCount}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
}
