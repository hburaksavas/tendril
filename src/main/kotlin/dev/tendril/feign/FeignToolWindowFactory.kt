package dev.tendril.feign

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.pom.Navigatable
import com.intellij.ui.*
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.tree.TreeUtil
import dev.tendril.config.ConfigProfiles
import dev.tendril.config.TendrilSettings
import dev.tendril.endpoints.EndpointRequests
import dev.tendril.endpoints.HttpMethodColors
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** Outgoing calls: every `@FeignClient` with the address it resolves to for the chosen profile. */
class FeignToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = FeignPanel(project)
        Disposer.register(toolWindow.disposable, panel)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
        toolWindow.setTitleActions(
            listOf(
                object : AnAction("Refresh", null, AllIcons.Actions.Refresh) {
                    override fun actionPerformed(e: AnActionEvent) = panel.refresh()
                },
                object : AnAction(
                    "Generate http-client.env.json",
                    "Write the resolved address of every client, per profile, to an HTTP Client environment file",
                    AllIcons.Actions.MenuSaveall,
                ) {
                    override fun actionPerformed(e: AnActionEvent) = panel.generateEnvFile()
                },
            ),
        )
        panel.refresh()
    }
}

private class ClientNode(val info: FeignClientInfo)
private class CallNode(val info: FeignClientInfo, val call: FeignCall)

private class FeignPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    private val search = SearchTextField()
    private val profiles = ComboBox<String>()
    private var all: List<FeignClientInfo> = emptyList()
    private var updatingProfiles = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = FeignRenderer()
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigate()
            }
        })
        PopupHandler.installPopupMenu(tree, contextMenu(), "TendrilFeignPopup")
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        profiles.addActionListener {
            if (updatingProfiles) return@addActionListener
            val selected = profiles.selectedItem as? String
            FeignResolver.selectProfile(project, selected?.takeIf { it != DEFAULT })
            refresh()
        }
        val top = JPanel(BorderLayout())
        top.add(profiles, BorderLayout.WEST)
        top.add(search, BorderLayout.CENTER)
        add(top, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    override fun dispose() {}

    fun refresh() {
        ReadAction.nonBlocking<Pair<List<String>, List<FeignClientInfo>>> {
            val known = listOf(DEFAULT) + ConfigProfiles.knownProfiles(project)
            val profile = FeignResolver.selectedProfile(project)
            known to FeignScanner.cached(project).map { FeignResolver.resolve(project, it, profile) }
        }
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { (known, infos) ->
                updatingProfiles = true
                profiles.removeAllItems()
                known.forEach { profiles.addItem(it) }
                profiles.selectedItem = FeignResolver.selectedProfile(project) ?: DEFAULT
                updatingProfiles = false
                all = infos
                applyFilter()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun applyFilter() {
        val q = search.text.trim().lowercase()
        tree.emptyText.text = if (all.isEmpty()) "No @FeignClient interfaces found" else "Nothing matches"
        root.removeAllChildren()
        for (info in all) {
            val calls = info.def.calls.filter { c ->
                q.isEmpty() || listOf(info.name, info.def.className, info.resolution.url.orEmpty(), c.subPath, c.methodName, c.httpMethod)
                    .any { it.lowercase().contains(q) }
            }
            if (calls.isEmpty() && q.isNotEmpty()) continue
            val node = DefaultMutableTreeNode(ClientNode(info))
            calls.forEach { node.add(DefaultMutableTreeNode(CallNode(info, it))) }
            root.add(node)
        }
        treeModel.reload()
        TreeUtil.expandAll(tree)
    }

    private fun selected(): Any? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject

    private fun navigate() {
        val target: Navigatable? = ReadAction.compute<Navigatable?, Throwable> {
            when (val s = selected()) {
                is CallNode -> s.call.method.element as? Navigatable
                is ClientNode -> s.info.def.cls.element as? Navigatable
                else -> null
            }
        }
        target?.takeIf { it.canNavigate() }?.navigate(true)
    }

    private fun contextMenu(): ActionGroup = object : ActionGroup() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            fun action(text: String, run: () -> Unit) = object : AnAction(text) {
                override fun actionPerformed(e: AnActionEvent) = run()
            }
            return when (val s = selected()) {
                is CallNode -> arrayOf(
                    action("Go to Source") { navigate() },
                    action("Copy URL") { copy("URL", ReadAction.compute<String, Throwable> { FeignRequests.url(s.info, s.call) }) },
                    action("Copy as cURL") {
                        copy("cURL command", ReadAction.compute<String, Throwable> { FeignRequests.curl(s.info, s.call) })
                    },
                    action("Send Request…") {
                        val text = ReadAction.compute<String, Throwable> { FeignRequests.http(s.info, s.call) }
                        dev.tendril.http.HttpToolWindowFactory.open(project, text)
                    },
                    action("Open as .http Request") { openHttp(s.info, s.call) },
                )
                is ClientNode -> buildList {
                    add(action("Go to Source") { navigate() })
                    s.info.resolution.url?.let { url -> add(action("Copy Address") { copy("Address", url) }) }
                    add(action("Set Address for '${s.info.name}'…") { setAddress(s.info) })
                    if (TendrilSettings.getInstance(project).state.serviceUrls.containsKey(s.info.name)) {
                        add(action("Clear Address Override") { clearAddress(s.info) })
                    }
                }.toTypedArray()
                else -> AnAction.EMPTY_ARRAY
            }
        }
    }

    private fun setAddress(info: FeignClientInfo) {
        val settings = TendrilSettings.getInstance(project).state
        val value = Messages.showInputDialog(
            project, "Base URL of service '${info.name}' (used when the client has no resolvable url):",
            "Tendril: Feign Service Address", null, settings.serviceUrls[info.name] ?: info.resolution.url.orEmpty(), null,
        )?.trim() ?: return
        if (value.isEmpty()) settings.serviceUrls.remove(info.name) else settings.serviceUrls[info.name] = value
        refresh()
    }

    private fun clearAddress(info: FeignClientInfo) {
        TendrilSettings.getInstance(project).state.serviceUrls.remove(info.name)
        refresh()
    }

    private fun copy(what: String, text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        notify("$what copied to the clipboard", NotificationType.INFORMATION)
    }

    private fun openHttp(info: FeignClientInfo, call: FeignCall) {
        val text = ReadAction.compute<String, Throwable> { FeignRequests.http(info, call) }
        EndpointRequests.openHttpScratch(project, FeignRequests.fileName(info, call), text)
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Tendril")
            ?.createNotification(message, type)?.notify(project)
    }

    /** Writes `http-client.env.json` (address per profile) and, if missing, a private file with token placeholders. */
    fun generateEnvFile() {
        ReadAction.nonBlocking<Map<String, Map<String, String>>> {
            val defs = FeignScanner.cached(project)
            val result = linkedMapOf<String, Map<String, String>>()
            for (profile in listOf<String?>(null) + ConfigProfiles.knownProfiles(project)) {
                val vars = linkedMapOf<String, String>()
                for (def in defs) {
                    val info = FeignResolver.resolve(project, def, profile)
                    info.resolution.url?.let { vars[variableName(info.name.ifBlank { def.className })] = it }
                }
                if (vars.isNotEmpty()) result[profile ?: "default"] = vars
            }
            result
        }
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { environments ->
                if (environments.isEmpty()) {
                    notify("No Feign client address could be resolved, nothing to write", NotificationType.WARNING)
                    return@finishOnUiThread
                }
                writeEnvFiles(environments)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun writeEnvFiles(environments: Map<String, Map<String, String>>) {
        val base = project.basePath?.let { Path.of(it) } ?: return
        val gson = GsonBuilder().setPrettyPrinting().create()
        val target = base.resolve("http-client.env.json")
        if (Files.exists(target) &&
            Messages.showYesNoDialog(
                project, "http-client.env.json already exists. Overwrite it?", "Tendril", Messages.getQuestionIcon(),
            ) != Messages.YES
        ) return

        val env = JsonObject()
        environments.forEach { (name, vars) -> env.add(name, JsonObject().also { o -> vars.forEach { (k, v) -> o.addProperty(k, v) } }) }
        Files.writeString(target, gson.toJson(env))

        val private = base.resolve("http-client.private.env.json")
        val createdPrivate = !Files.exists(private)
        if (createdPrivate) {
            val tokens = JsonObject()
            environments.keys.forEach { tokens.add(it, JsonObject().also { o -> o.addProperty("token", "") }) }
            Files.writeString(private, gson.toJson(tokens))
        }
        LocalFileSystem.getInstance().refreshNioFiles(listOf(target, private))
        notify(
            "Wrote http-client.env.json" + if (createdPrivate) " and http-client.private.env.json (add it to .gitignore)" else "",
            NotificationType.INFORMATION,
        )
    }

    /** `inventory-service` -> `inventoryServiceUrl` */
    private fun variableName(name: String): String =
        name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            .mapIndexed { i, part -> if (i == 0) part.replaceFirstChar { it.lowercase() } else part.replaceFirstChar { it.uppercase() } }
            .joinToString("") + "Url"

    private companion object {
        const val DEFAULT = "(default)"
    }
}

private class FeignRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
            is ClientNode -> {
                val info = item.info
                icon = AllIcons.Nodes.Interface
                append(info.name.ifBlank { info.def.className }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                val url = info.resolution.url
                if (url != null) {
                    append("   $url", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("   ${info.resolution.source}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                } else {
                    append("   unresolved", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    val missing = info.resolution.unresolved.joinToString(", ") { "\${$it}" }
                    append("   ${info.resolution.source}" + if (missing.isEmpty()) "" else ": $missing", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            is CallNode -> {
                append(item.call.httpMethod.padEnd(7), HttpMethodColors.attributes(item.call.httpMethod))
                append(FeignRequests.path(item.info, item.call))
                append("   ${item.call.methodName}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
}
