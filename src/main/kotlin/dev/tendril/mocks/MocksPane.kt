package dev.tendril.mocks

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import dev.tendril.endpoints.toolbarOf
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/** The Mocks tab of the Recordings window: every mock file, each one on or off, and what the application holds. */
internal class MocksPane(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val store = MockStore.getInstance(project)
    private val list = CheckBoxList<MockEntry>()
    private val allOn = JBCheckBox("Mocks on", store.enabled).apply {
        toolTipText = "Off sends no mocks to the application; the files stay"
    }
    private val status = SimpleColoredComponent()
    private var shown: List<MockEntry> = emptyList()
    private var rebuilding = false

    init {
        list.emptyText.setText("No mocks yet")
            .appendLine("Right-click a Feign or controller method and choose Mock Response,")
            .appendLine("or use Mock This Response on a recorded call.")
        list.setCheckBoxListListener { index, value ->
            if (rebuilding) return@setCheckBoxListListener
            val entry = list.getItemAt(index) ?: return@setCheckBoxListListener
            if (entry.definition == null) refresh(force = true) else store.setEnabled(entry, value)
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selected()?.let(store::open)
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> selected()?.let(store::open)
                    KeyEvent.VK_DELETE -> deleteSelected()
                }
            }
        })
        allOn.addItemListener { if (allOn.isSelected != store.enabled) store.enabled = allOn.isSelected }

        val top = JPanel(BorderLayout()).apply {
            border = dev.tendril.ui.Chrome.topRow()
            add(
                toolbarOf(
                    "TendrilMocks", this@MocksPane,
                    object : AnAction("Open", "Open the mock file", AllIcons.Actions.EditSource), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) {
                            selected()?.let(store::open)
                        }
                    },
                    object : AnAction("Delete", "Delete the mock file", AllIcons.General.Remove), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) = deleteSelected()
                    },
                    object : AnAction("Show Folder", "Select .tendril/mocks in the Project view", AllIcons.Nodes.Folder), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) {
                            val dir = store.directory() ?: return
                            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)
                            if (vf == null) Messages.showInfoMessage(project, "There are no mocks yet: the folder is created with the first one.", "Mocks")
                            else ProjectView.getInstance(project).select(null, vf, true)
                        }
                    },
                    object : AnAction("Reload", "Read the mock files again and send them to the application", AllIcons.Actions.Refresh), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) = store.reload()
                    },
                ),
                BorderLayout.WEST,
            )
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(allOn)
                add(status)
            }, BorderLayout.CENTER)
        }
        add(top, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list, true).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        store.addListener(parent) { ApplicationManager.getApplication().invokeLater({ refresh() }, ModalityState.any()) }
        refresh()
    }

    private fun selected(): MockEntry? = list.selectedIndex.takeIf { it >= 0 }?.let { list.getItemAt(it) }

    private fun deleteSelected() {
        val entry = selected() ?: return
        val answer = Messages.showYesNoDialog(
            project, "Delete the mock file ${entry.file.fileName}? It can be restored from Local History.", "Delete Mock",
            Messages.getQuestionIcon(),
        )
        if (answer == Messages.YES) store.delete(entry)
    }

    private fun refresh(force: Boolean = false) {
        allOn.isSelected = store.enabled
        status.clear()
        status.append(store.status, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        val entries = store.entries
        if (!force && entries == shown) return
        shown = entries
        val selectedFile = selected()?.file
        rebuilding = true
        try {
            list.clear()
            for (entry in entries) {
                val d = entry.definition
                val text = if (d == null) "${entry.name}   cannot be read: ${entry.error}"
                else "${d.label.ifEmpty { entry.name }}   ${if (d.isFeign) "Feign" else "endpoint"}  ${d.summary}"
                list.addItem(entry, text, d?.enabled == true)
            }
            entries.indexOfFirst { it.file == selectedFile }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
        } finally {
            rebuilding = false
        }
    }
}
