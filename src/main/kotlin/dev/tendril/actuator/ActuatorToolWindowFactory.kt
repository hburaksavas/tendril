package dev.tendril.actuator

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.ui.PopupHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.tree.TreeUtil
import dev.tendril.endpoints.EndpointRequests
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** Reads the Actuator endpoints (health, beans, mappings, env) of a running Spring Boot app. */
class ActuatorToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ActuatorPanel(project)
        Disposer.register(toolWindow.disposable, panel)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
        toolWindow.setTitleActions(
            listOf(object : AnAction("Refresh", "Fetch the actuator data again", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = panel.refreshAll()
            }),
        )
    }
}

/** A row of an actuator tree. [group] rows only carry children. [fqn] makes the row navigable to a class. */
internal data class ActuatorNode(
    val label: String,
    val detail: String = "",
    val color: SimpleTextAttributes? = null,
    val fqn: String? = null,
    /** Identifier used by context-menu actions (e.g. a logger name). */
    val key: String? = null,
    val children: List<ActuatorNode> = emptyList(),
)

private class ActuatorPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val url = SearchTextField(false).apply {
        text = defaultUrl()
        toolTipText = "Actuator base URL (management.endpoints.web.base-path)"
    }
    private val tabs = JBTabbedPane()
    private val views = listOf(
        ActuatorView("Health", "health", ::parseHealth),
        ActuatorView("Beans", "beans", ::parseBeans),
        ActuatorView("Mappings", "mappings", ::parseMappings),
        ActuatorView("Env", "env", ::parseEnv),
        ActuatorView("Loggers", "loggers", ::parseLoggers),
        ActuatorView("Conditions", "conditions", ::parseConditions),
    )
    private val loggersView = views[4]

    init {
        add(url, BorderLayout.NORTH)
        views.forEach { tabs.addTab(it.title, it.component) }
        add(tabs, BorderLayout.CENTER)
        url.textEditor.addActionListener { refreshAll() }
        tabs.addChangeListener { views[tabs.selectedIndex].let { if (!it.loaded) fetch(it) } }
        views.forEach { it.onNavigate = ::navigateToClass }
        loggersView.actions = { node ->
            val name = node.key
            if (name == null) emptyList() else
                (LOG_LEVELS.map { level -> loggerAction("Set $level", name, level) } +
                    loggerAction("Reset (inherit from parent)", name, null))
        }
        views[0].setMessage("Press Refresh (or Enter in the URL field) to read the running application")
    }

    override fun dispose() {}

    fun refreshAll() {
        views.forEach { it.loaded = false }
        fetch(views[tabs.selectedIndex])
    }

    private fun defaultUrl(): String = try {
        ReadAction.compute<String, Throwable> { EndpointRequests.baseUrl(project) } + "/actuator"
    } catch (_: Throwable) {
        "http://localhost:8080/actuator"
    }

    private fun fetch(view: ActuatorView) {
        val base = url.text.trim().trimEnd('/')
        view.setMessage("Loading $base/${view.path} …")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { get("$base/${view.path}") }
            ApplicationManager.getApplication().invokeLater({
                view.loaded = true
                result.fold(
                    { view.show(it) },
                    { view.setMessage(describe(it, base, view.path)) },
                )
            }, ModalityState.any())
        }
    }

    private fun get(address: String): JsonObject {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        val request = HttpRequest.newBuilder(URI.create(address))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.spring-boot.actuator.v3+json, application/json")
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        // /health answers 503 when DOWN but still carries the JSON we want to show
        if (response.statusCode() != 200 && response.statusCode() != 503) throw HttpStatus(response.statusCode())
        return JsonParser.parseString(response.body()).asJsonObject
    }

    private fun loggerAction(text: String, logger: String, level: String?): AnAction =
        object : AnAction(text), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = setLogLevel(logger, level)
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }

    /** POST /loggers/{name} {"configuredLevel": level}; a null level resets the logger. */
    private fun setLogLevel(logger: String, level: String?) {
        val base = url.text.trim().trimEnd('/')
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val body = if (level == null) """{"configuredLevel":null}""" else """{"configuredLevel":"$level"}"""
                val request = HttpRequest.newBuilder(URI.create("$base/loggers/$logger"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build()
                val status = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                    .send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                if (status !in 200..299) throw HttpStatus(status)
            }
            ApplicationManager.getApplication().invokeLater({
                result.onSuccess { fetch(loggersView) }.onFailure {
                    NotificationGroupManager.getInstance().getNotificationGroup("Tendril")
                        ?.createNotification(
                            "Could not change the level of $logger: ${it.message}. " +
                                "The loggers endpoint must be exposed and writable.",
                            NotificationType.WARNING,
                        )?.notify(project)
                }
            }, ModalityState.any())
        }
    }

    private class HttpStatus(val code: Int) : RuntimeException("HTTP $code")

    private fun describe(error: Throwable, base: String, path: String): String = when {
        error is HttpStatus && error.code == 404 ->
            "$path is not exposed. Set management.endpoints.web.exposure.include=health,beans,mappings,env"
        error is HttpStatus -> "Request failed: HTTP ${error.code}"
        error is IllegalArgumentException -> "Invalid URL: $base"
        else -> "Cannot reach $base/$path — is the application running with spring-boot-starter-actuator?"
    }

    private fun navigateToClass(fqn: String) {
        val clean = fqn.substringBefore("$$").replace('$', '.')
        val target = ReadAction.compute<Navigatable?, Throwable> {
            JavaPsiFacade.getInstance(project).findClass(clean, GlobalSearchScope.allScope(project)) as? Navigatable
        }
        target?.takeIf { it.canNavigate() }?.navigate(true)
    }
}

private class ActuatorView(
    val title: String,
    val path: String,
    private val parse: (JsonObject) -> List<ActuatorNode>,
) {
    var loaded = false
    var onNavigate: (String) -> Unit = {}

    /** Context-menu actions for the selected row; empty means no menu. */
    var actions: (ActuatorNode) -> List<AnAction> = { emptyList() }

    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val search = SearchTextField()
    private var nodes: List<ActuatorNode> = emptyList()
    val component = JPanel(BorderLayout())

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                t: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
            ) {
                val n = (value as? DefaultMutableTreeNode)?.userObject as? ActuatorNode ?: return
                append(n.label, n.color ?: SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (n.detail.isNotEmpty()) append("   ${n.detail}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val n = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ActuatorNode
                n?.fqn?.let(onNavigate)
            }
        })
        PopupHandler.installPopupMenu(
            tree,
            object : ActionGroup() {
                override fun getChildren(e: AnActionEvent?): Array<AnAction> {
                    val n = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ActuatorNode
                    return if (n == null) AnAction.EMPTY_ARRAY else actions(n).toTypedArray()
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            "TendrilActuatorPopup",
        )
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuild()
            override fun removeUpdate(e: DocumentEvent) = rebuild()
            override fun changedUpdate(e: DocumentEvent) = rebuild()
        })
        if (path != "health") component.add(search, BorderLayout.NORTH)
        component.add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    fun setMessage(text: String) {
        nodes = emptyList()
        tree.emptyText.text = text
        rebuild()
    }

    fun show(json: JsonObject) {
        nodes = parse(json)
        tree.emptyText.text = "Nothing matches"
        rebuild()
    }

    private fun rebuild() {
        val q = search.text.trim().lowercase()
        root.removeAllChildren()
        for (n in nodes) filtered(n, q)?.let { root.add(it) }
        model.reload()
        if (q.isNotEmpty() || path == "health") TreeUtil.expandAll(tree)
    }

    /** Keeps a node when it (or any descendant) matches; a matching group keeps all its children. */
    private fun filtered(n: ActuatorNode, q: String): DefaultMutableTreeNode? {
        val self = q.isEmpty() || "${n.label} ${n.detail}".lowercase().contains(q)
        val node = DefaultMutableTreeNode(n)
        if (n.children.isEmpty()) return if (self) node else null
        val kids = n.children.mapNotNull { filtered(it, if (self) "" else q) }
        if (kids.isEmpty() && !self) return null
        kids.forEach { node.add(it) }
        return node
    }
}

// ---- parsers -------------------------------------------------------------------------------------------------

private val UP = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x2E7D32, 0x6AAB73))
private val DOWN = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0xC62828, 0xF28B82))
private val OTHER = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0xB26A00, 0xE0A44D))

private fun JsonElement?.obj(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

private fun parseHealth(json: JsonObject): List<ActuatorNode> {
    fun node(name: String, o: JsonObject): ActuatorNode {
        val status = o.str("status") ?: "UNKNOWN"
        val color = when (status) { "UP" -> UP; "DOWN", "OUT_OF_SERVICE" -> DOWN; else -> OTHER }
        val kids = mutableListOf<ActuatorNode>()
        o.get("components").obj()?.entrySet()?.forEach { (k, v) -> v.obj()?.let { kids += node(k, it) } }
        o.get("details").obj()?.entrySet()?.forEach { (k, v) ->
            if (v.isJsonObject) v.obj()?.let { kids += node(k, it) }
            else kids += ActuatorNode(k, v.toString().trim('"'))
        }
        return ActuatorNode("$name: $status", color = color, children = kids)
    }
    return listOf(node("application", json))
}

private fun parseBeans(json: JsonObject): List<ActuatorNode> {
    val contexts = json.get("contexts").obj() ?: return emptyList()
    val out = mutableListOf<ActuatorNode>()
    for ((ctxName, ctx) in contexts.entrySet()) {
        val beans = ctx.obj()?.get("beans").obj() ?: continue
        val kids = beans.entrySet().sortedBy { it.key }.map { (name, b) ->
            val o = b.obj()
            val type = o?.str("type").orEmpty()
            val deps = o?.get("dependencies")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty()
            ActuatorNode(
                label = name,
                detail = type.substringAfterLast('.') + "  (${o?.str("scope") ?: "?"})",
                fqn = type.takeIf { it.isNotEmpty() },
                children = if (deps.isEmpty()) emptyList() else listOf(
                    ActuatorNode("depends on", color = SimpleTextAttributes.GRAYED_ATTRIBUTES,
                        children = deps.map { ActuatorNode(it) }),
                ),
            )
        }
        out += ActuatorNode(ctxName, "${kids.size} beans", children = kids)
    }
    return out
}

private fun parseMappings(json: JsonObject): List<ActuatorNode> {
    val contexts = json.get("contexts").obj() ?: return emptyList()
    val out = mutableListOf<ActuatorNode>()
    for ((ctxName, ctx) in contexts.entrySet()) {
        val servlets = ctx.obj()?.get("mappings").obj()?.get("dispatcherServlets").obj() ?: continue
        val rows = mutableListOf<ActuatorNode>()
        for ((_, list) in servlets.entrySet()) {
            if (!list.isJsonArray) continue
            for (m in list.asJsonArray) {
                val o = m.obj() ?: continue
                val cond = o.get("details").obj()?.get("requestMappingConditions").obj()
                val methods = cond?.get("methods")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty()
                val patterns = cond?.get("patterns")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty()
                val handler = o.str("handler").orEmpty()
                val verb = methods.joinToString("|").ifEmpty { "ANY" }
                val fqn = handler.substringBefore('#').takeIf { '#' in handler && it.contains('.') }
                rows += ActuatorNode(
                    label = "${verb.padEnd(7)} ${patterns.joinToString(", ").ifEmpty { o.str("predicate").orEmpty() }}",
                    detail = handler.substringAfterLast('.'),
                    color = SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_BOLD,
                        when (verb) { "GET" -> UP.fgColor; "DELETE" -> DOWN.fgColor; else -> OTHER.fgColor },
                    ),
                    fqn = fqn,
                )
            }
        }
        out += ActuatorNode(ctxName, "${rows.size} mappings", children = rows.sortedBy { it.label })
    }
    return out
}

private fun parseEnv(json: JsonObject): List<ActuatorNode> {
    val out = mutableListOf<ActuatorNode>()
    val profiles = json.get("activeProfiles")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty()
    out += ActuatorNode("Active profiles", profiles.joinToString(", ").ifEmpty { "(default)" })
    json.get("propertySources")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { src ->
        val o = src.obj() ?: return@forEach
        val props = o.get("properties").obj()?.entrySet().orEmpty().sortedBy { it.key }.map { (k, v) ->
            val value = v.obj()?.get("value")?.let { if (it.isJsonPrimitive) it.asString else it.toString() }.orEmpty()
            ActuatorNode(k, value)
        }
        out += ActuatorNode(o.str("name").orEmpty(), "${props.size}", children = props)
    }
    return out
}

private val LOG_LEVELS = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF")

private fun parseLoggers(json: JsonObject): List<ActuatorNode> {
    val loggers = json.get("loggers").obj() ?: return emptyList()
    return loggers.entrySet().sortedWith(compareBy({ it.key != "ROOT" }, { it.key })).map { (name, v) ->
        val o = v.obj()
        val effective = o?.str("effectiveLevel") ?: "?"
        val configured = o?.str("configuredLevel")
        val color = when (effective) {
            "TRACE", "DEBUG" -> OTHER
            "ERROR", "OFF" -> DOWN
            else -> null
        }
        ActuatorNode(
            label = name,
            detail = effective + (configured?.let { "  (configured: $it)" } ?: ""),
            color = color,
            fqn = name.takeIf { it.substringAfterLast('.').firstOrNull()?.isUpperCase() == true },
            key = name,
        )
    }
}

private fun parseConditions(json: JsonObject): List<ActuatorNode> {
    val contexts = json.get("contexts").obj() ?: return emptyList()
    val out = mutableListOf<ActuatorNode>()
    for ((ctxName, ctx) in contexts.entrySet()) {
        val c = ctx.obj() ?: continue
        fun messages(arr: JsonElement?): List<ActuatorNode> =
            arr?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.obj() }?.map {
                ActuatorNode(it.str("condition").orEmpty().substringAfterLast('.'), it.str("message").orEmpty())
            }.orEmpty()

        val positive = c.get("positiveMatches").obj()?.entrySet().orEmpty().sortedBy { it.key }.map { (name, v) ->
            ActuatorNode(name.substringAfterLast('.'), name.substringBeforeLast('.', ""), UP, name, children = messages(v))
        }
        val negative = c.get("negativeMatches").obj()?.entrySet().orEmpty().sortedBy { it.key }.map { (name, v) ->
            ActuatorNode(
                name.substringAfterLast('.'), name.substringBeforeLast('.', ""), DOWN, name,
                children = messages(v.obj()?.get("notMatched")),
            )
        }
        out += ActuatorNode(
            ctxName,
            children = listOf(
                ActuatorNode("Positive matches", "${positive.size}", children = positive),
                ActuatorNode("Negative matches", "${negative.size}", children = negative),
            ),
        )
    }
    return out
}
