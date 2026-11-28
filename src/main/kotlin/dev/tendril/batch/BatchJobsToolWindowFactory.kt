package dev.tendril.batch

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import dev.tendril.descriptorOf
import dev.tendril.endpoints.toolbarOf
import dev.tendril.readInBackground
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class BatchJobsToolWindowFactory : ToolWindowFactory {
    override suspend fun isApplicableAsync(project: Project): Boolean =
        smartReadAction(project) { BatchJobs.isPresent(project) }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BatchJobsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        panel.refresh()
    }
}

private class BatchJobsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode())).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean,
            ) {
                val node = value as? DefaultMutableTreeNode ?: return
                val entry = node.userObject as? BatchJobs.Entry
                val isJob = entry != null && node.parent == tree.model.root
                icon = when {
                    entry == null -> AllIcons.Nodes.Folder
                    isJob -> AllIcons.Nodes.Class
                    entry.role != null -> AllIcons.Nodes.Method
                    else -> AllIcons.Nodes.Interface
                }
                append(entry?.label ?: node.toString(),
                    if (isJob || entry == null) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val detail = when {
                    isJob -> if (node.childCount == 1) "1 step" else "${node.childCount} steps"
                    else -> entry?.role
                }
                detail?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
        }
    }
    private var refreshId = 0

    init {
        tree.emptyText.text = "Loading batch jobs and steps…"
        val refresh = object : DumbAwareAction("Refresh", null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
        }
        val navigate = object : DumbAwareAction("Go to Declaration") {
            override fun actionPerformed(e: AnActionEvent) = navigate()
        }
        navigate.registerCustomShortcutSet(com.intellij.openapi.actionSystem.CustomShortcutSet(KeyStroke.getKeyStroke("ENTER")), tree, this)
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1 &&
                    tree.getPathForLocation(e.x, e.y) == tree.selectionPath) navigate()
            }
        })
        add(toolbarOf("Tendril.BatchJobs", this, refresh, navigate), BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
    }

    fun refresh() {
        val id = ++refreshId
        readInBackground(project, this, { BatchJobs.scan(project) }) { (jobs, steps) ->
            if (id != refreshId) return@readInBackground
            val root = DefaultMutableTreeNode()
            jobs.forEach { root.add(node(it)) }
            if (steps.isNotEmpty()) {
                root.add(DefaultMutableTreeNode("Steps without a linked job").apply {
                    steps.forEach { add(node(it)) }
                })
            }
            tree.emptyText.text = "No Spring Batch job or step beans found"
            tree.model = DefaultTreeModel(root)
            for (index in 0 until root.childCount) {
                tree.expandPath(TreePath(arrayOf(root, root.getChildAt(index))))
            }
        }
    }

    private fun node(entry: BatchJobs.Entry): DefaultMutableTreeNode =
        DefaultMutableTreeNode(entry).apply { entry.children.forEach { add(node(it)) } }

    private fun navigate() {
        val entry = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? BatchJobs.Entry ?: return
        readInBackground(project, this, { descriptorOf(entry.target.element) }) { it?.navigate(true) }
    }

    override fun dispose() {}
}
