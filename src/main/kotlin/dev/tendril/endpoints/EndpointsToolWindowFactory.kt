package dev.tendril.endpoints

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.pom.Navigatable
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class EndpointsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = EndpointsPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
        toolWindow.setTitleActions(listOf(object : AnAction("Refresh", null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = panel.refresh()
        }))
        panel.refresh()
    }
}

private class EndpointsPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<Endpoint>()
    private val list = JBList(model)
    private val search = SearchTextField()
    private var all: List<Endpoint> = emptyList()

    init {
        list.cellRenderer = SimpleListCellRenderer.create { label, e, _ ->
            label.text = e?.let {
                "${it.httpMethod.padEnd(7)} ${it.path}   (${it.className}.${it.method.element?.name})"
            } ?: ""
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigate()
            }
        })
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        add(search, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
    }

    fun refresh() {
        ReadAction.nonBlocking<List<Endpoint>> { EndpointScanner.scan(project) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) {
                all = it
                applyFilter()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun applyFilter() {
        val q = search.text.trim().lowercase()
        model.clear()
        all.filter { q.isEmpty() || "${it.httpMethod} ${it.path} ${it.className}".lowercase().contains(q) }
            .forEach(model::addElement)
    }

    private fun navigate() {
        val m = list.selectedValue?.method?.element ?: return
        (m as? Navigatable)?.takeIf { it.canNavigate() }?.navigate(true)
    }
}
