package dev.tendril.beans

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.pom.Navigatable
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.PsiElement
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

private class BeanRow(
    val name: String,
    val typeName: String,
    val label: String,
    val module: String?,
    val injectedInto: Int,
    val pointer: SmartPsiElementPointer<PsiElement>,
)

/** "Spring Beans" tool window: every bean the project defines, searchable, double-click to open. */
class BeansToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BeansPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
        toolWindow.setTitleActions(listOf(object : AnAction("Refresh", null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = panel.refresh()
        }))
        panel.refresh()
    }
}

private class BeansPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<BeanRow>()
    private val list = JBList(model)
    private val search = SearchTextField()
    private var all: List<BeanRow> = emptyList()

    init {
        list.cellRenderer = SimpleListCellRenderer.create { label, row, _ ->
            label.text = row?.let {
                val used = if (it.injectedInto > 0) "  ←${it.injectedInto}" else ""
                "${it.name} : ${it.typeName}  [${it.label}]${it.module?.let { m -> "  ($m)" }.orEmpty()}$used"
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
        ReadAction.nonBlocking<List<BeanRow>> { rows() }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) {
                all = it
                applyFilter()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun rows(): List<BeanRow> {
        val beans = BeanModels.get(project)
        val pointers = SmartPointerManager.getInstance(project)
        return beans.beans.map {
            BeanRow(
                it.name, it.type.name ?: "?", it.label,
                ModuleUtilCore.findModuleForPsiElement(it.element)?.name,
                beans.injectedInto[it.element]?.size ?: 0,
                pointers.createSmartPsiElementPointer(it.element),
            )
        }.sortedWith(compareBy({ it.label }, { it.name }))
    }

    private fun applyFilter() {
        val q = search.text.trim().lowercase()
        model.clear()
        all.filter { q.isEmpty() || "${it.name} ${it.typeName} ${it.label} ${it.module}".lowercase().contains(q) }
            .forEach(model::addElement)
    }

    private fun navigate() {
        val element = list.selectedValue?.pointer?.element ?: return
        (element as? Navigatable)?.takeIf { it.canNavigate() }?.navigate(true)
    }
}
