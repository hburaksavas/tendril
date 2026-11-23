package dev.tendril.ui

import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import javax.swing.JTabbedPane

/**
 * The IDE's own tab strip (the one tool windows and editors use), so tabs follow every theme. Plain Swing tabbed
 * panes are drawn by the look and feel and can vanish against a dark tool window.
 */
internal class TabStrip(project: com.intellij.openapi.project.Project, parent: com.intellij.openapi.Disposable) {
    private val tabs = com.intellij.ui.tabs.JBTabsFactory.createTabs(project, parent)
    private val infos = mutableListOf<com.intellij.ui.tabs.TabInfo>()

    val component: javax.swing.JComponent get() = tabs.component

    val selectedIndex: Int get() = infos.indexOf(tabs.selectedInfo).coerceAtLeast(0)

    fun add(title: String, content: javax.swing.JComponent) {
        val info = com.intellij.ui.tabs.TabInfo(content).setText(title)
        infos += info
        tabs.addTab(info)
    }

    /** A tab that shows only [icon]; the name (and whatever [setTooltip] adds later) is the tooltip. */
    fun addIconTab(icon: javax.swing.Icon, tooltip: String, content: javax.swing.JComponent) {
        val info = com.intellij.ui.tabs.TabInfo(content).setIcon(icon).setTooltipText(tooltip)
        infos += info
        tabs.addTab(info)
    }

    fun setTooltip(index: Int, tooltip: String) {
        infos.getOrNull(index)?.setTooltipText(tooltip)
    }

    fun setTitle(index: Int, title: String) {
        infos.getOrNull(index)?.setText(title)
    }

    fun select(content: javax.swing.JComponent) {
        infos.firstOrNull { it.component === content }?.let { tabs.select(it, true) }
    }

    fun onSelect(listener: () -> Unit) {
        tabs.addListener(object : com.intellij.ui.tabs.TabsListener {
            override fun selectionChanged(oldSelection: com.intellij.ui.tabs.TabInfo?, newSelection: com.intellij.ui.tabs.TabInfo?) = listener()
        })
    }
}

/** Shared chrome: a quiet hairline under a panel's top row and above its status line, instead of boxes. */
internal object Chrome {
    fun topRow(vertical: Int = 2, horizontal: Int = 4): javax.swing.border.Border? = JBUI.Borders.compound(
        JBUI.Borders.customLineBottom(com.intellij.ui.JBColor.border()), JBUI.Borders.empty(vertical, horizontal),
    )

    fun statusLine(): javax.swing.border.Border? = JBUI.Borders.compound(
        JBUI.Borders.customLineTop(com.intellij.ui.JBColor.border()), JBUI.Borders.empty(3, 10),
    )
}

/** Headers and body in one view: two captioned sections one above the other, the divider is draggable. */
internal object Sections {
    private fun captioned(title: String, content: javax.swing.JComponent) = javax.swing.JPanel(java.awt.BorderLayout()).apply {
        add(
            com.intellij.ui.components.JBLabel(title.uppercase()).apply {
                foreground = com.intellij.ui.JBColor.GRAY
                font = com.intellij.util.ui.UIUtil.getLabelFont(com.intellij.util.ui.UIUtil.FontSize.MINI)
                border = Chrome.topRow(3, 8)
            },
            java.awt.BorderLayout.NORTH,
        )
        add(content, java.awt.BorderLayout.CENTER)
    }

    fun stacked(
        key: String, headersTitle: String, headers: javax.swing.JComponent, bodyTitle: String, body: javax.swing.JComponent,
    ): javax.swing.JComponent = com.intellij.ui.OnePixelSplitter(true, key, 0.3f).apply {
        firstComponent = captioned(headersTitle, headers)
        secondComponent = captioned(bodyTitle, body)
    }
}
