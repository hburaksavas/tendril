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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import dev.tendril.feign.FeignPanel
import dev.tendril.descriptorOf
import dev.tendril.readInBackground
import dev.tendril.ui.Fragment
import dev.tendril.ui.Palette
import dev.tendril.ui.PainterTreeRenderer
import dev.tendril.ui.RowPainter
import dev.tendril.http.HttpScratch
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

        /** Opens [text] in a `.http` editor tab. */
        fun sendRequest(project: Project, text: String, @Suppress("UNUSED_PARAMETER") outgoing: Boolean = false) {
            HttpScratch.open(project, text)
        }
    }
}

internal class ApiRootPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, RequestHost {
    private val tabs = dev.tendril.ui.TabStrip(project, this)
    private val incoming = EndpointsPanel(project, this) { tabs.setTooltip(0, "Incoming endpoints: $it") }
    private val outgoing = FeignPanel(project, this) { tabs.setTooltip(1, "Outgoing Feign calls: $it") }

    init {
        tabs.addIconTab(AllIcons.Actions.Download, "Incoming endpoints", incoming)
        tabs.addIconTab(AllIcons.Actions.Upload, "Outgoing Feign calls", outgoing)
        add(tabs.component, BorderLayout.CENTER)
        Disposer.register(this, incoming)
        Disposer.register(this, outgoing)
    }

    override fun dispose() {}

    fun refresh() {
        incoming.refresh()
        outgoing.refresh()
    }

    override fun sendRequest(text: String) {
        HttpScratch.open(project, text)
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
    private var baseUrl: String? = null
    private val props = PropertiesComponent.getInstance(project)
    private val status = JBLabel().apply {
        foreground = JBColor.GRAY
        font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        border = dev.tendril.ui.Chrome.statusLine()
    }
    private val renderer = PainterTreeRenderer(::bindRow)

    /** Endpoints are always listed under their controller. */
    private val grouped = true

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
        tree.cellRenderer = renderer
        search.textEditor.emptyText.text = "Filter by path, method or controller"
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
            border = dev.tendril.ui.Chrome.topRow()
            add(
                toolbarOf(
                    "TendrilEndpoints", this@EndpointsPanel,
                    object : AnAction("Refresh", "Scan the project again", AllIcons.Actions.Refresh), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) = refresh()
                    },
                    toggleAction("Only the Active Editor's Endpoints", AllIcons.General.Locate, { focusMode }) { focusMode = it },
                ),
                BorderLayout.WEST,
            )
            add(search, BorderLayout.CENTER)
        }
        add(top, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

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
        ReadAction.nonBlocking<Pair<List<Endpoint>, String>> { EndpointScanner.cached(project) to EndpointRequests.baseUrl(project) }
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { (endpoints, base) -> show(endpoints, base) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    fun show(endpoints: List<Endpoint>, baseUrl: String? = null) {
        all = endpoints
        this.baseUrl = baseUrl
        onCount(endpoints.size)
        applyFilter()
    }

    private fun applyFilter() {
        val q = search.text.trim().lowercase()
        val activeFile = if (focusMode) FileEditorManager.getInstance(project).selectedEditor?.file else null

        val shown = all.filter { e ->
            (q.isEmpty() || "${e.httpMethod} ${e.path} ${e.className}".lowercase().contains(q)) &&
                (!focusMode || (activeFile != null && e.method.virtualFile == activeFile))
        }

        val empty = tree.emptyText
        empty.clear()
        when {
            all.isEmpty() -> {
                empty.appendText("No endpoints found")
                empty.appendLine("Classes annotated with @RestController or @Controller are listed here.")
                empty.appendLine("Scan again", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { refresh() }
            }
            focusMode && activeFile == null -> {
                empty.appendText("No editor is open")
                empty.appendLine("Open a controller to see its endpoints.")
                empty.appendLine("Show all endpoints", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { focusMode = false }
            }
            focusMode && shown.isEmpty() -> {
                empty.appendText("No endpoints in ${activeFile?.name}")
                empty.appendLine("Switch to another file, or")
                empty.appendLine("show all endpoints", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { focusMode = false }
            }
            else -> {
                empty.appendText("Nothing matches \"${search.text.trim()}\"")
                empty.appendLine("Clear the filter", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { search.text = "" }
            }
        }

        // columns: one badge width for the verbs in use, one path width so the controller column lines up
        renderer.painter.font = tree.font
        renderer.painter.badgeWidth = renderer.painter.badgeColumn(all.map { it.httpMethod } + "DELETE")
        renderer.painter.primaryColumn = minOf(
            JBUI.scale(420), shown.maxOfOrNull { renderer.painter.measure(Palette.path(it.path)) } ?: 0,
        )
        status.text = buildString {
            append(if (shown.size == all.size) "${all.size} endpoints" else "${shown.size} of ${all.size} endpoints")
            baseUrl?.let { append("   Base address $it") }
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

    private fun bindRow(p: RowPainter, item: Any?) {
        when (item) {
            is Endpoint -> {
                p.badge = item.httpMethod
                p.badgeColor = Palette.method(item.httpMethod)
                p.primary = Palette.path(item.path)
                p.secondary = listOf(Fragment("${item.className}.${item.methodName}", Palette.MUTED))
            }
            is String -> {
                p.icon = AllIcons.Nodes.Class
                p.primary = listOf(Fragment(item, bold = true))
                p.secondary = listOf(Fragment("${all.count { it.className == item }} endpoints", Palette.MUTED))
            }
        }
    }

    private fun selectedEndpoint(): Endpoint? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Endpoint

    private fun navigate() {
        val endpoint = selectedEndpoint() ?: return
        readInBackground(project, this, { descriptorOf(endpoint.method.element) }) { it?.navigate(true) }
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
                readInBackground(project, this, { EndpointRequests.http(project, it) }) { text -> host.sendRequest(text) }
            },
            Separator.create(),
            action("Go to Source") { navigate() },
            action("Copy URL") { readInBackground(project, this, { EndpointRequests.url(project, it) }) { copy("URL", it) } },
            action("Copy as cURL") {
                readInBackground(project, this, { EndpointRequests.curl(project, it) }) { copy("cURL command", it) }
            },
            action("Open as .http File") { openHttpScratch(it) },
        )
    }

    private fun copy(what: String, text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        NotificationGroupManager.getInstance().getNotificationGroup("Tendril")
            ?.createNotification("$what copied to the clipboard", NotificationType.INFORMATION)?.notify(project)
    }

    private fun openHttpScratch(endpoint: Endpoint) {
        val fileName = "${endpoint.httpMethod}_${endpoint.path.trim('/').replace(Regex("[^A-Za-z0-9]+"), "_")}.http"
        readInBackground(project, this, { EndpointRequests.http(project, endpoint) }) {
            EndpointRequests.openHttpScratch(project, fileName, it)
        }
    }

    private companion object {
        const val FOCUS_KEY = "tendril.endpoints.focusMode"
    }
}

internal object HttpMethodColors {
    fun color(method: String): java.awt.Color = Palette.method(method)

    fun attributes(method: String): SimpleTextAttributes =
        SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Palette.method(method))
}
