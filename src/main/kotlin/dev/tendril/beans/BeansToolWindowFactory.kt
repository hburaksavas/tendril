package dev.tendril.beans

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.tendril.endpoints.toolbarOf
import dev.tendril.ui.Fragment
import dev.tendril.ui.PainterListRenderer
import dev.tendril.ui.Palette
import dev.tendril.ui.RowPainter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class BeanRow(
    val name: String,
    val typeName: String,
    val label: String,
    val module: String?,
    val injectedInto: Int,
    val pointer: SmartPsiElementPointer<PsiElement>?,
)

/** "Spring Beans" tool window: every bean the project defines, searchable, double-click to open. */
class BeansToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BeansPanel(project)
        Disposer.register(toolWindow.disposable, panel)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
        panel.refresh()
    }
}

internal class BeansPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val model = DefaultListModel<BeanRow>()
    private val list = JBList(model)
    private val search = SearchTextField()
    private var all: List<BeanRow> = emptyList()
    private val renderer = PainterListRenderer<BeanRow>(rightAligned = true, bind = ::bindRow)
    private val status = JBLabel().apply {
        foreground = JBColor.GRAY
        font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        border = dev.tendril.ui.Chrome.statusLine()
    }

    init {
        list.cellRenderer = renderer
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigate()
            }
        })
        search.textEditor.emptyText.text = "Filter by name, type or kind"
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })

        val top = JPanel(BorderLayout()).apply {
            border = dev.tendril.ui.Chrome.topRow()
            add(
                toolbarOf(
                    "TendrilBeans", this@BeansPanel,
                    object : AnAction("Refresh", "Scan the project again", AllIcons.Actions.Refresh), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) = refresh()
                    },
                ),
                BorderLayout.WEST,
            )
            add(search, BorderLayout.CENTER)
        }
        add(top, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    override fun dispose() {}

    fun refresh() {
        ReadAction.nonBlocking<List<BeanRow>> { rows() }
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { show(it) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    fun show(rows: List<BeanRow>) {
        all = rows
        applyFilter()
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

    private fun bindRow(p: RowPainter, row: BeanRow) {
        // calm rows: a colour dot for the kind instead of a chip, details muted on the right
        p.icon = dot(colorOf(row.label))
        p.badge = null
        p.primary = listOf(Fragment(row.name, bold = true), Fragment("   ${row.typeName}", Palette.MUTED))
        p.secondary = buildList {
            add(Fragment(kindOf(row.label), colorOf(row.label)))
            row.module?.let { add(Fragment("   $it", Palette.MUTED)) }
            if (row.injectedInto > 0) add(Fragment("   used in ${row.injectedInto}", Palette.MUTED))
        }
    }

    private fun dot(color: Color): javax.swing.Icon = object : javax.swing.Icon {
        override fun getIconWidth() = JBUI.scale(8)
        override fun getIconHeight() = JBUI.scale(8)
        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
            val g2 = g.create() as java.awt.Graphics2D
            try {
                com.intellij.util.ui.GraphicsUtil.setupAAPainting(g2)
                g2.color = color
                g2.fillOval(x, y, iconWidth, iconHeight)
            } finally {
                g2.dispose()
            }
        }
    }

    private fun applyFilter() {
        val q = search.text.trim().lowercase()
        model.clear()
        val shown = all.filter { q.isEmpty() || "${it.name} ${it.typeName} ${it.label} ${it.module}".lowercase().contains(q) }
        shown.forEach(model::addElement)

        renderer.painter.font = list.font
        renderer.painter.badgeWidth = 0
        status.text = buildString {
            append(if (shown.size == all.size) "${all.size} beans" else "${shown.size} of ${all.size} beans")
            val modules = all.mapNotNull { it.module }.distinct().size
            if (modules > 1) append("   $modules modules")
        }

        val empty = list.emptyText
        empty.clear()
        if (all.isEmpty()) {
            empty.appendText("No beans found")
            empty.appendLine("Classes with @Component, @Service, @Repository, @Controller and @Bean methods are listed here.")
            empty.appendLine("Scan again", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { refresh() }
        } else {
            empty.appendText("Nothing matches \"${search.text.trim()}\"")
            empty.appendLine("Clear the filter", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { search.text = "" }
        }
    }

    /** A short kind for the badge: "@Service" -> "Service", "Spring Data repository" -> "Repository". */
    private fun kindOf(label: String): String = when {
        label.contains("Data repository", ignoreCase = true) -> "Repository"
        label.contains("ConfigurationProperties") -> "Properties"
        label.contains("FeignClient") -> "Feign"
        else -> label.removePrefix("@").replace("RestController", "Controller")
    }

    private fun colorOf(label: String): Color = when {
        label.contains("Controller") -> Palette.PUT
        label.contains("Service") -> Palette.GET
        label.contains("Repository") || label.contains("Data") -> Palette.POST
        label.contains("Bean") || label.contains("Configuration") -> Palette.PATCH
        else -> Palette.OTHER
    }

    private fun navigate() {
        val element = list.selectedValue?.pointer?.element ?: return
        (element as? Navigatable)?.takeIf { it.canNavigate() }?.navigate(true)
    }
}
