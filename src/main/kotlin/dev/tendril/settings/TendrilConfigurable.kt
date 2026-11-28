package dev.tendril.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.ide.RecentProjectsManagerBase
import dev.tendril.calls.ServiceMapStore
import dev.tendril.config.TendrilSettings
import dev.tendril.feign.FeignResolver
import dev.tendril.recordings.RecorderServer
import dev.tendril.recordings.RecorderSettings
import java.awt.Dimension
import java.util.UUID
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/** One row of the service address table. */
internal class AddressRow(var service: String, var url: String)

/** Settings | Tools | Tendril. */
class TendrilConfigurable(private val project: Project) : SearchableConfigurable {

    private val settings get() = TendrilSettings.getInstance(project)
    private val recorder get() = RecorderSettings.getInstance()

    // Working copies: nothing reaches the settings until Apply.
    internal val ignoredKeys = CollectionListModel<String>()
    internal val templates = CollectionListModel<String>()
    internal val linkedProjects = CollectionListModel<String>()
    internal val addresses = ListTableModel<AddressRow>(
        object : ColumnInfo<AddressRow, String>("Service name") {
            override fun valueOf(item: AddressRow) = item.service
            override fun isCellEditable(item: AddressRow?) = true
            override fun setValue(item: AddressRow, value: String) {
                item.service = value.trim()
            }
        },
        object : ColumnInfo<AddressRow, String>("Base URL") {
            override fun valueOf(item: AddressRow) = item.url
            override fun isCellEditable(item: AddressRow?) = true
            override fun setValue(item: AddressRow, value: String) {
                item.url = value.trim()
            }
        },
    )
    internal val port = JBIntSpinner(17843, 1024, 65535)
    private var token = ""

    override fun getId() = "dev.tendril.settings"
    override fun getDisplayName() = "Tendril"

    override fun createComponent(): JComponent {
        reset()
        return panel {
            group("Configuration") {
                row {
                    comment(
                        "Keys the \"unknown property\" inspection leaves alone. An entry also covers everything below it: " +
                            "\"jwt\" covers \"jwt.secret\".",
                    )
                }
                row { cell(ignoredKeysPanel()).align(Align.FILL) }
            }
            group("Feign clients") {
                row { label("Client addresses") }
                row { cell(addressPanel()).align(Align.FILL) }
                row { comment("Base URLs for name-only clients take precedence over configuration files.") }
                row { label("Additional address patterns") }
                row { cell(templatePanel()).align(Align.FILL) }
                row { comment("Use {name} for the client name, for example <code>urls.{name}.base</code>.") }
                collapsibleGroup("Show built-in patterns") {
                    FeignResolver.NAME_KEYS.forEach { key -> row { comment("<code>$key</code>") } }
                }
            }
            group("Service calls") {
                row {
                    comment(
                        "Other projects of the system (backend, BFF, React frontend) that this one is linked to. Controller " +
                            "and Feign methods get a gutter icon showing who calls them across these projects (fetch calls, " +
                            "Feign calls and the endpoints that make them) and what they call. A project open in another " +
                            "window is read live; a closed one from what Tendril saved the last time it was open; " +
                            "a frontend from its files.",
                    )
                }
                row { cell(linkedProjectsPanel()).align(Align.FILL) }
            }
            group("Recorder") {
                row("Port:") {
                    cell(port)
                    comment("Where the IDE listens for the recorder in the application. A new port applies after the IDE restarts.")
                }
                row {
                    button("Regenerate Token") { token = UUID.randomUUID().toString().replace("-", "") }
                    comment("Run configurations that already carry the recorder need to be updated afterwards.")
                }
            }
        }
    }

    private fun ignoredKeysPanel(): JComponent {
        val list = JBList(ignoredKeys).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
        return ToolbarDecorator.createDecorator(list)
            .setAddAction {
                ask("Key or prefix to ignore:", "Ignore Key", "")?.let { ignoredKeys.add(it) }
            }
            .setEditAction {
                val index = list.selectedIndex
                if (index >= 0) ask("Key or prefix to ignore:", "Edit Ignored Key", ignoredKeys.getElementAt(index))?.let { ignoredKeys.setElementAt(it, index) }
            }
            .createPanel().apply { preferredSize = Dimension(JBUI.scale(520), JBUI.scale(90)) }
    }

    private fun linkedProjectsPanel(): JComponent {
        val list = JBList(linkedProjects).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = com.intellij.ui.SimpleListCellRenderer.create("") { path ->
                "${projectName(path)}   $path"
            }
        }
        return ToolbarDecorator.createDecorator(list)
            .setAddAction { button -> chooseProject(button) }
            .createPanel().apply { preferredSize = Dimension(JBUI.scale(520), JBUI.scale(90)) }
    }

    /** Recently opened projects that are not linked yet, then a folder chooser for a project never opened here. */
    private fun chooseProject(button: com.intellij.ui.AnActionButton) {
        val own = project.basePath?.let(ServiceMapStore::normalise)
        val linked = linkedProjects.items.map(ServiceMapStore::normalise).toSet()
        val recent = RecentProjectsManagerBase.getInstanceEx().getRecentPaths()
            .filter { ServiceMapStore.normalise(it) != own && ServiceMapStore.normalise(it) !in linked }
        val folder = "Choose Folder…"
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(recent + folder)
            .setTitle("Link Project")
            .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { path ->
                if (path == folder) folder else "${projectName(path)}   $path"
            })
            .setItemChosenCallback { chosen ->
                if (chosen == folder) {
                    val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null)?.let { linkedProjects.add(it.path) }
                } else linkedProjects.add(chosen)
            }
            .createPopup()
            .show(button.preferredPopupPoint)
    }

    private fun projectName(path: String): String =
        RecentProjectsManagerBase.getInstanceEx().getProjectName(path).ifBlank { path.trimEnd('/').substringAfterLast('/') }

    private fun templatePanel(): JComponent {
        val list = JBList(templates).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
        val validator = object : InputValidator {
            override fun checkInput(inputString: String?) = inputString?.contains("{name}") == true
            override fun canClose(inputString: String?) = checkInput(inputString)
        }
        return ToolbarDecorator.createDecorator(list)
            .setAddAction {
                ask("Config key, with {name} for the service name:", "Add Address Key", "", validator)?.let { templates.add(it) }
            }
            .setEditAction {
                val index = list.selectedIndex
                if (index >= 0) ask("Config key, with {name} for the service name:", "Edit Address Key", templates.getElementAt(index), validator)
                    ?.let { templates.setElementAt(it, index) }
            }
            .createPanel().apply { preferredSize = Dimension(JBUI.scale(520), JBUI.scale(70)) }
    }

    private fun addressPanel(): JComponent {
        val table = TableView(addresses)
        table.setShowGrid(true)
        return ToolbarDecorator.createDecorator(table)
            .setAddAction {
                addresses.addRow(AddressRow("", ""))
                val row = addresses.rowCount - 1
                table.setRowSelectionInterval(row, row)
                table.editCellAt(row, 0)
            }
            .setRemoveAction { com.intellij.ui.TableUtil.removeSelectedItems(table) }
            .createPanel().apply { preferredSize = Dimension(JBUI.scale(520), JBUI.scale(130)) }
    }

    private fun ask(message: String, title: String, initial: String, validator: InputValidator? = null): String? =
        Messages.showInputDialog(project, message, title, null, initial, validator)?.trim()?.takeIf { it.isNotEmpty() }

    override fun isModified(): Boolean {
        val s = settings.state
        return ignoredKeys.items != s.ignoredKeys ||
            templates.items != s.nameKeyTemplates ||
            linkedProjects.items != s.linkedProjects ||
            addressMap() != s.serviceUrls ||
            port.number != recorder.port ||
            token != recorder.token
    }

    override fun apply() {
        val s = settings.state
        s.ignoredKeys = ignoredKeys.items.toMutableList()
        s.nameKeyTemplates = templates.items.toMutableList()
        s.linkedProjects = linkedProjects.items.toMutableList()
        s.serviceUrls = addressMap().toMutableMap()
        settings.changed()
        recorder.port = port.number
        if (token != recorder.token) recorder.replaceToken(token)
        RecorderServer.getInstance() // the server keeps the port it started with until the IDE restarts
        DaemonCodeAnalyzer.getInstance(project).restart("Tendril settings")
    }

    override fun reset() {
        val s = settings.state
        ignoredKeys.replaceAll(s.ignoredKeys.toList())
        templates.replaceAll(s.nameKeyTemplates.toList())
        linkedProjects.replaceAll(s.linkedProjects.toList())
        addresses.items = s.serviceUrls.entries.sortedBy { it.key }.map { AddressRow(it.key, it.value) }.toMutableList()
        port.number = recorder.port
        token = recorder.token
    }

    /** Rows with both a name and an address; the last row for a name wins. */
    private fun addressMap(): Map<String, String> =
        addresses.items.map { it.service.trim() to it.url.trim() }
            .filter { (service, url) -> service.isNotEmpty() && url.isNotEmpty() }
            .toMap()
}
