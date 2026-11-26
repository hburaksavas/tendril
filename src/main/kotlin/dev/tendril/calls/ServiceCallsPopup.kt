package dev.tendril.calls

import com.intellij.icons.AllIcons
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.tendril.config.TendrilSettings
import dev.tendril.endpoints.EndpointScanner
import dev.tendril.feign.FeignScanner
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getUastParentOfType
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Right-click | Find Service Calls, inside a handler method of a controller or a mapped method of a Feign client:
 * who calls it across the projects linked under Settings | Tools | Tendril (React fetch calls, Feign calls of a BFF
 * and the endpoints that make them) and what it calls in turn. Hidden everywhere else.
 */
class FindServiceCallsAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null && file != null &&
            serviceMethodAt(file, editor.caretModel.offset) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val method = serviceMethodAt(file, editor.caretModel.offset) ?: return
        ServiceCallsPopup.show(
            project, SmartPointerManager.createPointer(method), JBPopupFactory.getInstance().guessBestPopupLocation(editor),
        )
    }

    companion object {
        private val CONTROLLERS = listOf(
            "org.springframework.web.bind.annotation.RestController", "org.springframework.stereotype.Controller",
        )

        /** The mapped controller or Feign method around [offset], in its Java view; null anywhere else. Needs a read action. */
        internal fun serviceMethodAt(file: PsiFile, offset: Int): PsiMethod? {
            val element = file.findElementAt(offset)?.takeIf { it !is PsiWhiteSpace }
                ?: file.findElementAt(maxOf(0, offset - 1)) ?: return null
            val method = element.getUastParentOfType<UMethod>()?.javaPsi ?: return null
            val cls = method.containingClass ?: return null
            val isFeign = cls.isInterface && FeignScanner.isClient(cls)
            val isEndpoint = !cls.isInterface && CONTROLLERS.any { cls.hasAnnotation(it) }
            if (!isFeign && !isEndpoint) return null
            return method.takeIf { EndpointScanner.mappingsOf(it).isNotEmpty() }
        }
    }
}

/** Reads the current and the linked projects and builds the [ServiceGraph]. */
object ServiceSources {
    /** Frontend files are read from disk; do this before taking a read action. */
    fun fetches(project: Project): Map<String, List<FetchCall>> =
        (listOfNotNull(project.basePath) + TendrilSettings.getInstance(project).state.linkedProjects)
            .associateWith { FetchScanner.cached(it) }

    /**
     * Needs a read action. A linked project that is open in another window is read live (unless it is indexing),
     * a closed one from the snapshot it saved the last time it was open.
     */
    fun load(project: Project, fetches: Map<String, List<FetchCall>>): List<ServiceSource> {
        val base = project.basePath ?: return emptyList()
        val out = mutableListOf(ServiceSource(base, project.name, ServiceSource.State.CURRENT, ServiceSnapshots.live(project), fetches[base].orEmpty()))
        for (path in TendrilSettings.getInstance(project).state.linkedProjects) {
            val open = openProject(path)
            val (state, snapshot) = when {
                open != null && !DumbService.isDumb(open) -> ServiceSource.State.OPEN to ServiceSnapshots.live(open)
                else -> ServiceMapStore.read(path)?.let { ServiceSource.State.SAVED to it } ?: (ServiceSource.State.NONE to null)
            }
            val name = open?.name ?: snapshot?.projectName ?: path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
            out += ServiceSource(path, name, state, snapshot, fetches[path].orEmpty())
        }
        return out
    }

    fun openProject(path: String?): Project? {
        path ?: return null
        val wanted = ServiceMapStore.normalise(path)
        return ProjectManager.getInstance().openProjects.firstOrNull {
            !it.isDisposed && it.basePath?.let(ServiceMapStore::normalise) == wanted
        }
    }
}

object ServiceCallsPopup {

    fun show(project: Project, pointer: SmartPsiElementPointer<PsiMethod>, point: RelativePoint) {
        object : Task.Backgroundable(project, "Finding service calls", true) {
            private var tree: Pair<String, ChainNode>? = null

            override fun run(indicator: ProgressIndicator) {
                val fetches = ServiceSources.fetches(project)
                tree = ReadAction.nonBlocking<Pair<String, ChainNode>?> {
                    val method = pointer.element ?: return@nonBlocking null
                    val location = ServiceSnapshots.locationOf(project, method) ?: return@nonBlocking null
                    build(ServiceGraph(ServiceSources.load(project, fetches)), location)
                }.inSmartMode(project).wrapProgress(indicator).expireWith(project).executeSynchronously()
            }

            override fun onSuccess() {
                val (title, root) = tree ?: return
                popup(project, title, root, point)
            }
        }.queue()
    }

    /** The tree for the handler or Feign method at [location] of the current project (source 0). */
    internal fun build(graph: ServiceGraph, location: Location): Pair<String, ChainNode>? {
        val own = graph.sources.firstOrNull()?.snapshot ?: return null
        val root = ChainNode(ChainNode.Kind.SECTION, "")
        val endpoints = own.endpoints.indices.filter { own.endpoints[it].location == location }.map { ServiceGraph.EndpointRef(0, it) }
        val feign = own.feign.indices.filter { own.feign[it].location == location }.map { ServiceGraph.FeignRef(0, it) }
        if (endpoints.isEmpty() && feign.isEmpty()) return null

        val title: String
        if (endpoints.isNotEmpty()) {
            title = endpoints.joinToString(", ") { graph.endpointNode(it).text }
            val callers = endpoints.flatMap { graph.callersOf(it) }
            root.children += section("Called by", callers, "No caller found in the linked projects")
            val calls = endpoints.flatMap { graph.callsOf(it) }
            if (calls.isNotEmpty()) root.children += section("Calls", calls, "")
        } else {
            title = feign.joinToString(", ") { graph.feignNode(it).text }
            root.children += section("Calls", feign.flatMap { graph.downstreamOf(it) }, "No endpoint of the linked projects matches this call")
            root.children += section("Called by", feign.flatMap { graph.upstreamOf(it) }, "No endpoint of this project makes this call")
        }
        root.children += ChainNode(ChainNode.Kind.SECTION, "Projects read").apply {
            graph.sources.forEach { children += ChainNode(ChainNode.Kind.NOTE, it.name, describe(it)) }
        }
        return title to root
    }

    private fun section(title: String, nodes: List<ChainNode>, empty: String) =
        ChainNode(ChainNode.Kind.SECTION, title).apply {
            if (nodes.isEmpty()) children += ChainNode(ChainNode.Kind.NOTE, empty) else children += nodes
        }

    private fun describe(s: ServiceSource): String {
        val parts = mutableListOf<String>()
        parts += when (s.state) {
            ServiceSource.State.CURRENT -> "this project"
            ServiceSource.State.OPEN -> "open"
            ServiceSource.State.SAVED -> "saved " + age(s.snapshot!!.generatedAt)
            ServiceSource.State.NONE -> if (s.fetches.isEmpty()) "not read yet: open it once in IntelliJ IDEA with Tendril" else "frontend"
        }
        s.snapshot?.let { parts += "${it.endpoints.size} endpoints, ${it.feign.size} Feign calls" }
        if (s.fetches.isNotEmpty()) parts += "${s.fetches.size} fetch calls"
        return parts.joinToString(" · ")
    }

    private fun age(at: Long): String {
        val minutes = (System.currentTimeMillis() - at) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    private fun popup(project: Project, title: String, root: ChainNode, point: RelativePoint) {
        fun node(c: ChainNode): DefaultMutableTreeNode = DefaultMutableTreeNode(c).apply { c.children.forEach { add(node(it)) } }
        val tree = Tree(DefaultTreeModel(node(root))).apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = Renderer()
        }
        TreeUtil.expandAll(tree)
        fun open() {
            val c = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ChainNode ?: return
            val location = c.location ?: return
            navigate(project, location, c.projectPath)
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) open()
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) open()
            }
        })
        val scroll = ScrollPaneFactory.createScrollPane(tree, true).apply {
            preferredSize = JBUI.size(620, minOf(480, 60 + 22 * tree.rowCount))
        }
        JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, tree)
            .setTitle(title)
            .setAdText("Double-click or Enter opens the code; link projects under Settings | Tools | Tendril")
            .setResizable(true).setMovable(true).setRequestFocus(true)
            .createPopup().show(point)
    }

    /** Opens [location] in the window of the project it belongs to when that is open, else in this one. */
    fun navigate(project: Project, location: Location, projectPath: String?) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(location.file) ?: return
        val target = ServiceSources.openProject(projectPath) ?: project
        OpenFileDescriptor(target, file, location.line, 0).navigate(true)
        if (target != project) ProjectUtil.focusProjectWindow(target, true)
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val c = (value as? DefaultMutableTreeNode)?.userObject as? ChainNode ?: return
            icon = when (c.kind) {
                ChainNode.Kind.ENDPOINT -> AllIcons.Nodes.Method
                ChainNode.Kind.FEIGN -> AllIcons.Nodes.Interface
                ChainNode.Kind.FETCH -> AllIcons.FileTypes.JavaScript
                else -> null
            }
            when (c.kind) {
                ChainNode.Kind.SECTION -> append(c.text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                ChainNode.Kind.NOTE -> append(c.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                else -> append(c.text)
            }
            if (c.detail.isNotEmpty()) append("   " + c.detail, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}
