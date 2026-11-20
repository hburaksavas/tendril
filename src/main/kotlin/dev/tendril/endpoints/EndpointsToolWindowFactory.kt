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
import com.intellij.pom.Navigatable
import com.intellij.ui.*
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class EndpointsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = EndpointsPanel(project)
        Disposer.register(toolWindow.disposable, panel)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
        toolWindow.setTitleActions(
            listOf(
                object : AnAction("Refresh", null, AllIcons.Actions.Refresh) {
                    override fun actionPerformed(e: AnActionEvent) = panel.refresh()
                },
                panel.toggle("Group by Controller", AllIcons.Actions.GroupBy, panel::grouped) { panel.grouped = it },
                panel.toggle(
                    "Focus Mode: Only the Active Editor's Endpoints", AllIcons.General.Locate, panel::focusMode,
                ) { panel.focusMode = it },
            ),
        )
        panel.refresh()
    }
}

private class EndpointsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
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
        add(search, BorderLayout.NORTH)
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

    fun toggle(text: String, icon: javax.swing.Icon, get: () -> Boolean, set: (Boolean) -> Unit): AnAction =
        object : ToggleAction(text, null, icon), DumbAware {
            override fun isSelected(e: AnActionEvent) = get()
            override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    fun refresh() {
        ReadAction.nonBlocking<List<Endpoint>> { EndpointScanner.cached(project) }
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) {
                all = it
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

        tree.emptyText.text = when {
            all.isEmpty() -> "No Spring endpoints found"
            focusMode && activeFile == null -> "Focus mode: no editor is open"
            focusMode && shown.isEmpty() -> "Focus mode: no endpoints in ${activeFile?.name}"
            else -> "Nothing matches"
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
            action("Go to Source") { navigate() },
            action("Copy URL") { copy("URL", ReadAction.compute<String, Throwable> { EndpointRequests.url(project, it) }) },
            action("Copy as cURL") { copy("cURL command", ReadAction.compute<String, Throwable> { EndpointRequests.curl(project, it) }) },
            action("Open as .http Request") { openHttpScratch(it) },
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
        val file = com.intellij.ide.scratch.ScratchRootType.getInstance()
            .createScratchFile(project, fileName, null, text) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
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
