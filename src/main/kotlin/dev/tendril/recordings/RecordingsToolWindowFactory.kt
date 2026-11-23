package dev.tendril.recordings

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.*
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import dev.tendril.endpoints.EndpointsToolWindowFactory
import dev.tendril.endpoints.Endpoint
import dev.tendril.endpoints.EndpointScanner
import dev.tendril.descriptorOf
import dev.tendril.readInBackground
import dev.tendril.endpoints.HttpMethodColors
import dev.tendril.endpoints.toolbarOf
import dev.tendril.http.HttpExecutor
import dev.tendril.http.HttpFileType
import dev.tendril.ui.Editors
import dev.tendril.ui.Fragment
import dev.tendril.ui.Palette
import dev.tendril.ui.PainterListRenderer
import dev.tendril.ui.RowPainter
import com.intellij.openapi.fileTypes.PlainTextFileType
import dev.tendril.http.HttpResult
import dev.tendril.http.ParsedRequest
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent

/**
 * What the application did while it ran with the recorder: the requests it served, the calls it made and the
 * messages it received, each with a replay.
 */
class RecordingsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RecordingsPanel(project)
        Disposer.register(toolWindow.disposable, panel)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }

    companion object {
        fun showRoute(project: Project, endpoint: Endpoint) {
            val window = ToolWindowManager.getInstance(project).getToolWindow("Recordings") ?: return
            window.activate {
                (window.contentManager.getContent(0)?.component as? RecordingsPanel)?.showRoute(endpoint)
            }
        }
    }
}

internal class RecordingsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val store = RecordingStore.getInstance(project)
    private val status = SimpleColoredComponent()
    private val tabs = dev.tendril.ui.TabStrip(project, this)
    private val panes = mapOf(
        RecordingKind.INCOMING to RecordingPane(project, RecordingKind.INCOMING, this, ::copyArgument),
        RecordingKind.OUTGOING to RecordingPane(project, RecordingKind.OUTGOING, this, ::copyArgument),
        RecordingKind.MESSAGE to RecordingPane(project, RecordingKind.MESSAGE, this, ::copyArgument),
    )

    init {
        tabs.addIconTab(AllIcons.Actions.Download, "Incoming requests", panes.getValue(RecordingKind.INCOMING))
        tabs.addIconTab(AllIcons.Actions.Upload, "Outgoing calls", panes.getValue(RecordingKind.OUTGOING))
        tabs.addIconTab(AllIcons.Toolwindows.ToolWindowMessages, "Messages", panes.getValue(RecordingKind.MESSAGE))

        val top = JPanel(BorderLayout()).apply {
            border = dev.tendril.ui.Chrome.topRow()
            add(
                toolbarOf(
                    "TendrilRecordings", this@RecordingsPanel,
                    object : AnAction(
                        "Add Recorder to Maven Run Configuration",
                        "Attach the recorder to a spring-boot:run configuration", AllIcons.General.Add,
                    ), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) = addToMavenRun()
                    },
                    object : AnAction("Copy Agent Argument", "Copy the -javaagent argument for any other run configuration", AllIcons.Actions.Copy),
                        DumbAware {
                        override fun actionPerformed(e: AnActionEvent) = copyArgument()
                    },
                    object : AnAction("Clear", "Remove all recordings", AllIcons.Actions.GC), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) = store.clear()
                    },
                ),
                BorderLayout.WEST,
            )
            add(status.apply { border = JBUI.Borders.empty(0, 8) }, BorderLayout.EAST)
            add(keepSpinner(), BorderLayout.CENTER)
        }
        add(top, BorderLayout.NORTH)
        add(tabs.component, BorderLayout.CENTER)

        store.addListener(this) { ApplicationManager.getApplication().invokeLater({ update() }, ModalityState.any()) }
        // the connection state fades after the application stops, with no event to tell us
        Timer(5000) { update() }.apply { isRepeats = true; start() }.also { timer -> Disposer.register(this) { timer.stop() } }
        update()
    }

    override fun dispose() {}

    fun showRoute(endpoint: Endpoint) {
        val pane = panes.getValue(RecordingKind.INCOMING)
        pane.showRoute(endpoint)
        tabs.select(pane)
    }

    /** "Keep [10] per tab": how many recordings of each kind are held; a typed number replaces the default. */
    private fun keepSpinner(): JPanel {
        val spinner = JSpinner(SpinnerNumberModel(store.max, 1, RecordingStore.LIMIT, 1))
        spinner.toolTipText = "How many recordings of each kind are kept"
        spinner.addChangeListener { store.max = (spinner.value as Number).toInt() }
        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(8)
            add(JBLabel("Keep last").apply { foreground = JBColor.GRAY })
            add(spinner.apply { preferredSize = java.awt.Dimension(JBUI.scale(90), preferredSize.height) })
        }
    }

    private fun update() {
        panes.forEach { (kind, pane) ->
            pane.show(store.snapshot(kind))
            tabs.setTooltip(kind.ordinal, "${TITLES.getValue(kind)}: ${store.snapshot(kind).size}")
        }
        status.clear()
        val port = RecorderServer.getInstance().port
        if (port == 0) {
            status.append("Not listening", SimpleTextAttributes.ERROR_ATTRIBUTES)
        } else if (RecorderAgent.isConnected(project)) {
            status.append("Application connected", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x2E7D32, 0x6AAB73)))
            status.append("   port $port", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        } else {
            status.append("Waiting for the application", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            status.append("   port $port", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private fun addToMavenRun() {
        val action = ActionManager.getInstance().getAction("Tendril.AddRecorderToMavenRun")
        if (action == null) {
            Messages.showInfoMessage(
                project, "Maven support is not available in this IDE. Use \"Copy Agent Argument\" and add it to the VM options of your run configuration.",
                "Recorder",
            )
            return
        }
        ActionUtil.invokeAction(action, com.intellij.ide.DataManager.getInstance().getDataContext(this), "TendrilRecordings", null, null)
    }

    private fun copyArgument() {
        val argument = runCatching { RecorderAgent.argument(project) }.getOrElse {
            Messages.showErrorDialog(project, it.message ?: "The recorder could not be prepared", "Recorder")
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(argument))
        NotificationGroupManager.getInstance().getNotificationGroup("Tendril")
            ?.createNotification("Agent argument copied. Add it to the JVM options of the run configuration.", NotificationType.INFORMATION)
            ?.notify(project)
    }

    private companion object {
        val TITLES = mapOf(RecordingKind.INCOMING to "Incoming", RecordingKind.OUTGOING to "Outgoing", RecordingKind.MESSAGE to "Messages")
    }
}

/** A list of the latest recordings of one kind, and the details of the selected one. */
internal class RecordingPane(
    private val project: Project, private val kind: RecordingKind, parent: com.intellij.openapi.Disposable = project,
    private val onSetup: () -> Unit = {},
) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<Recording>()
    private val list = object : JBList<Recording>(model) {
        /** Clicking below the last row would otherwise select the last recording. */
        override fun locationToIndex(location: java.awt.Point): Int {
            val index = super.locationToIndex(location)
            if (index < 0) return index
            return if (getCellBounds(index, index)?.contains(location) == true) index else -1
        }
    }
    private var shown: List<Recording> = emptyList()
    private var shownRecording: Recording? = null
    private var shownOnce = false
    private var rebuilding = false
    private var route: Endpoint? = null
    private val clearRoute = ActionLink("").apply {
        isVisible = false
        addActionListener { setRoute(null) }
    }
    private val pendingRecordings = mutableSetOf<Recording>()
    private val newRecordings = ActionLink("").apply {
        isVisible = false
        addActionListener {
            list.selectedIndex = 0
            list.ensureIndexIsVisible(0)
            pendingRecordings.clear()
            updateNewRecordings()
        }
    }
    private val pathSearch = SearchTextField(false).apply {
        textEditor.emptyText.text = "Filter by path"
        textEditor.accessibleContext.accessibleName = "Filter recordings by path"
    }
    private val errorsOnly = JBCheckBox("Errors only").apply {
        toolTipText = "Show HTTP 4xx and 5xx responses and failed requests"
    }
    private val renderer = PainterListRenderer<Recording>(rightAligned = true, bind = ::bindRow)
    private val summary = SimpleColoredComponent()
    private val facts = SimpleColoredComponent()
    private val request = Editors.viewer(project)
    private val requestHeaders = Editors.viewer(project)
    private val response = Editors.viewer(project)
    private val responseHeaders = Editors.viewer(project)
    private val replay = Editors.viewer(project)
    private val sql = Editors.viewer(project)
    private val details = dev.tendril.ui.TabStrip(project, parent)
    private val timeline = WaterfallPanel()
    private val replayButton = ActionLink(if (kind == RecordingKind.MESSAGE) "Republish" else if (kind == RecordingKind.OUTGOING) "Send again" else "Replay").apply { icon = AllIcons.Actions.Execute }
    private val openButton = ActionLink("Open as .http").apply { icon = AllIcons.Actions.EditSource }
    private val sourceButton = ActionLink("Go to Controller").apply { isVisible = false }
    private val hint = JBLabel(" ")

    init {
        list.cellRenderer = renderer
        updateEmptyText()
        errorsOnly.addItemListener { refreshList() }
        pathSearch.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refreshList()
        })
        list.addListSelectionListener { if (!it.valueIsAdjusting && !rebuilding) select() }

        val sections = "tendril.recordings.sections"
        if (kind == RecordingKind.MESSAGE) {
            details.add("Routing", request)
            details.add("Message", dev.tendril.ui.Sections.stacked("$sections.message", "Headers", responseHeaders, "Payload", response))
        } else {
            details.add("Request", dev.tendril.ui.Sections.stacked("$sections.request", "Headers", requestHeaders, "Body", request))
            details.add("Response", dev.tendril.ui.Sections.stacked("$sections.response", "Headers", responseHeaders, "Body", response))
        }
        details.add("Replay", replay)
        // where the time of a served request went: the calls made while serving it, on one axis
        if (kind == RecordingKind.INCOMING) {
            details.add("Timeline", ScrollPaneFactory.createScrollPane(timeline, true))
            details.add("SQL", sql)
        }

        replayButton.addActionListener { replaySelected() }
        openButton.addActionListener { openSelected() }
        sourceButton.addActionListener { navigateToController(parent) }
        openButton.isVisible = kind != RecordingKind.MESSAGE
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 14, 0)).apply {
            isOpaque = false
            add(replayButton)
            add(openButton)
            add(sourceButton)
            add(hint.apply { foreground = JBColor.GRAY })
        }
        val titles = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(summary, BorderLayout.NORTH)
            add(facts.apply { border = JBUI.Borders.emptyTop(2) }, BorderLayout.CENTER)
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(10, 12, 8, 12))
            add(titles, BorderLayout.NORTH)
            add(actions.apply { border = JBUI.Borders.emptyTop(6) }, BorderLayout.SOUTH)
        }
        val right = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(details.component, BorderLayout.CENTER)
        }
        val splitter = OnePixelSplitter(false, "tendril.recordings.$kind", 0.42f)
        splitter.firstComponent = JPanel(BorderLayout()).apply {
            if (kind != RecordingKind.MESSAGE) {
                add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    border = JBUI.Borders.empty(4, 8)
                    add(pathSearch, BorderLayout.NORTH)
                    add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                        add(errorsOnly, BorderLayout.WEST)
                        add(clearRoute, BorderLayout.CENTER)
                        add(newRecordings, BorderLayout.EAST)
                    }, BorderLayout.SOUTH)
                }, BorderLayout.NORTH)
            }
            add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
        }
        splitter.secondComponent = right
        add(splitter, BorderLayout.CENTER)
        select()
    }

    fun show(recordings: List<Recording>) {
        // The window refreshes on a timer; rebuilding identical content would reset what the user is reading.
        if (recordings.size == shown.size && recordings.indices.all { recordings[it] === shown[it] }) return
        val selected = list.selectedValue
        if (kind != RecordingKind.MESSAGE && selected != null && selected in recordings) {
            pendingRecordings.retainAll(recordings.toSet())
            pendingRecordings.addAll(recordings.filterNot { it in shown })
        } else {
            pendingRecordings.clear()
        }
        shown = recordings
        refreshList()
    }

    fun showRoute(endpoint: Endpoint) {
        pathSearch.text = ""
        errorsOnly.isSelected = false
        setRoute(endpoint)
    }

    private fun setRoute(endpoint: Endpoint?) {
        route = endpoint
        clearRoute.text = endpoint?.let { "Clear route filter (${it.httpMethod} ${it.path})" }.orEmpty()
        clearRoute.isVisible = endpoint != null
        refreshList()
    }

    private fun refreshList() {
        val query = pathSearch.text.trim()
        val recordings = shown.filter { recording ->
            val matchesPath = query.isEmpty() || RecordingText.shortUrl(recording, kind).contains(query, ignoreCase = true)
            val matchesError = !errorsOnly.isSelected || recording.error != null || (recording.status ?: 0) in 400..599
            matchesPath && matchesError && route?.let { RecordingText.matchesRoute(recording, it) } != false
        }
        updateEmptyText()
        val selected = list.selectedValue
        renderer.painter.font = list.font
        renderer.painter.badgeWidth = renderer.painter.badgeColumn(recordings.map { badgeOf(it) } + "DELETE")
        rebuilding = true
        try {
            model.clear()
            recordings.forEach { model.addElement(it) }
            list.setSelectedValue(selected, true)
            if (list.selectedIndex < 0 && model.size() > 0) list.selectedIndex = 0
        } finally {
            rebuilding = false
        }
        select()
    }

    private fun updateNewRecordings() {
        val count = (0 until model.size()).count { model.getElementAt(it) in pendingRecordings }
        newRecordings.text = if (count == 1) "1 new recording" else "$count new recordings"
        newRecordings.isVisible = count > 0
    }

    private fun badgeOf(r: Recording) = if (kind == RecordingKind.MESSAGE) (r.transport ?: "message").uppercase() else r.method ?: "GET"

    private fun primaryOf(r: Recording): List<Fragment> =
        if (kind == RecordingKind.MESSAGE) {
            listOfNotNull(
                Fragment(r.destination.orEmpty()),
                r.routingKey?.takeIf { it.isNotEmpty() && it != r.destination }?.let { Fragment("  $it", Palette.MUTED) },
            )
        } else Palette.path(RecordingText.shortUrl(r, kind))

    private fun bindRow(p: RowPainter, r: Recording) {
        p.badge = badgeOf(r)
        p.badgeColor = if (kind == RecordingKind.MESSAGE) Palette.messageTransport(r.transport) else Palette.method(r.method)
        p.primary = primaryOf(r)
        p.secondary = buildList {
            when {
                r.status != null -> add(Fragment("${r.status}", Palette.status(r.status), bold = true))
                r.error != null -> add(Fragment("failed", Palette.FAILED, bold = true))
            }
            if (kind == RecordingKind.MESSAGE) {
                if (r.durationMs > 0) add(Fragment("   ${r.durationMs} ms", Palette.MUTED))
                add(Fragment("   ${RecordingText.TIME.format(Date(r.time))}", Palette.MUTED))
            } else {
                add(Fragment("   ${r.durationMs} ms", Palette.MUTED))
            }
        }
    }

    private fun updateEmptyText() {
        if (route != null || shown.isNotEmpty() && (errorsOnly.isSelected || pathSearch.text.isNotBlank())) {
            list.emptyText.setText(if (route == null && pathSearch.text.isBlank()) "No matching errors" else "No matching recordings")
                .appendLine("Clear filters", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                    pathSearch.text = ""
                    errorsOnly.isSelected = false
                    setRoute(null)
                }
        } else {
            list.emptyText.setText("Nothing recorded yet").appendLine(emptyHint())
                .appendLine("Copy agent argument", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { onSetup() }
        }
    }

    private fun emptyHint() = when (kind) {
        RecordingKind.INCOMING -> "Requests the application serves appear here, with their responses."
        RecordingKind.OUTGOING -> "Calls made by Feign clients appear here."
        RecordingKind.MESSAGE -> "Messages received by @RabbitListener and @KafkaListener appear here."
    }

    private fun select() {
        val r = list.selectedValue
        pendingRecordings.remove(r)
        updateNewRecordings()
        if (shownOnce && r === shownRecording) return // same recording: keep the scroll position of the editors
        shownOnce = true
        shownRecording = r
        replayButton.isEnabled = r != null && canReplay(r)
        openButton.isEnabled = r != null
        sourceButton.isVisible = kind == RecordingKind.INCOMING && r?.let { it.handler != null || it.pattern != null } == true
        hint.text = if (r != null && !canReplay(r)) cannotReplay(r) else " "
        summary.clear()
        facts.clear()
        if (r == null) {
            summary.append("Select a recording to see it here", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        } else {
            if (kind == RecordingKind.MESSAGE) {
                summary.append("${(r.transport ?: "message").uppercase()}  ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Palette.messageTransport(r.transport)))
                summary.append(r.destination.orEmpty(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            } else {
                summary.append("${r.method}  ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Palette.method(r.method)))
                summary.append(RecordingText.shortUrl(r, kind), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                r.status?.let {
                    summary.append(" · $it", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Palette.status(it)))
                }
                summary.append(" · ${r.durationMs} ms", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            val line = listOfNotNull(
                r.handler?.let { "handled by $it" }, r.client?.let { "client $it" },
                r.durationMs.takeIf { kind == RecordingKind.MESSAGE && it > 0 }?.let { "$it ms" },
                r.flow.takeIf { it > 0 }?.let { "flow $it" },
                RecordingText.TIME.format(Date(r.time)),
            ).joinToString("      ")
            facts.append(line, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        val requestText = r?.let { RecordingText.request(it) }.orEmpty()
        Editors.show(
            request, requestText,
            if (r != null && kind != RecordingKind.MESSAGE) Editors.typeOf(requestText) else PlainTextFileType.INSTANCE,
        )
        Editors.show(requestHeaders, r?.let { RecordingText.requestHeaders(it) }.orEmpty(), PlainTextFileType.INSTANCE)
        val body = r?.let { RecordingText.response(it) }.orEmpty()
        Editors.show(response, body, Editors.typeOf(body))
        Editors.show(responseHeaders, r?.let { RecordingText.responseHeaders(it) }.orEmpty(), PlainTextFileType.INSTANCE)
        Editors.show(replay, "", PlainTextFileType.INSTANCE)
        if (kind == RecordingKind.INCOMING) {
            timeline.show(r?.let { Waterfall.build(it, RecordingStore.getInstance(project).snapshot(RecordingKind.OUTGOING)) }.orEmpty())
            val queries = r?.sql.orEmpty()
            val count = queries.sumOf { it.count }
            details.setTitle(4, if (count > 0) "SQL ($count)" else "SQL")
            val text = when {
                r == null -> ""
                queries.isEmpty() -> "No SQL executions recorded. To capture prepared JDBC statements, enable tendril.recorder.sql=true in the running application."
                else -> queries.sortedByDescending { it.count }.joinToString("\n\n") { query ->
                    val millis = java.math.BigDecimal.valueOf(query.durationMicros, 3).stripTrailingZeros().toPlainString()
                    "${query.count} executions · $millis ms total\n${query.template}"
                }
            }
            Editors.show(sql, text, PlainTextFileType.INSTANCE)
        }
    }

    private fun navigateToController(parent: Disposable) {
        val recording = list.selectedValue ?: return
        readInBackground(project, parent, {
            val candidates = EndpointScanner.cached(project).filter { RecordingText.matchesRoute(recording, it) }
            val matchingHandler = candidates.filter { recording.handler == "${it.className.substringAfterLast('.')}.${it.methodName}" }
            val endpoint = matchingHandler.ifEmpty { candidates }.distinctBy { it.method }.singleOrNull()
            descriptorOf(endpoint?.method?.element)
        }) { source ->
            if (source != null) source.navigate(true)
            else Messages.showInfoMessage(project, "No unique controller was found for this recording.", "Go to Controller")
        }
    }

    private fun canReplay(r: Recording): Boolean = when (kind) {
        RecordingKind.MESSAGE -> r.replayable
        else -> r.bodyEncoding != "base64" && !r.url.isNullOrEmpty()
    }

    private fun cannotReplay(r: Recording): String = when (kind) {
        RecordingKind.MESSAGE -> "This message was deserialized into an object, so it cannot be republished as it was."
        else -> "The body is binary and cannot be replayed."
    }

    private fun openSelected() {
        val r = list.selectedValue ?: return
        EndpointsToolWindowFactory.sendRequest(project, RecordingText.httpFile(r), outgoing = kind == RecordingKind.OUTGOING)
    }

    private fun replaySelected() {
        val r = list.selectedValue ?: return
        if (kind != RecordingKind.MESSAGE && !confirmTarget(r)) return
        details.select(replay)
        Editors.show(replay, "Sending…", PlainTextFileType.INSTANCE)
        replayButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = if (kind == RecordingKind.MESSAGE) {
                RecorderServer.getInstance().republish(project, r).fold({ it }, { "Failed: ${it.message}" })
            } else {
                runCatching { HttpExecutor.send(RecordingText.asRequest(r)) }
                    .fold({ RecordingText.comparison(r, it) }, { "The request failed: ${it.message ?: it.javaClass.simpleName}" })
            }
            ApplicationManager.getApplication().invokeLater({
                Editors.show(replay, text, PlainTextFileType.INSTANCE)
                replayButton.isEnabled = true
            }, ModalityState.any())
        }
    }

    /** A replay is a real request: ask before it goes to anything but this machine. */
    private fun confirmTarget(r: Recording): Boolean {
        val host = runCatching { URI.create(r.url ?: "").host }.getOrNull() ?: return true
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]") return true
        return Messages.showYesNoDialog(
            project, "This sends a real ${r.method} request to $host. Continue?", "Replay request", Messages.getWarningIcon(),
        ) == Messages.YES
    }
}

/** Text for the detail panes, and the conversion of a recording back into a request. */
internal object RecordingText {
    val TIME = SimpleDateFormat("HH:mm:ss")

    private val SKIP_ON_REPLAY = setOf("host", "content-length", "connection", "transfer-encoding", "expect", "upgrade", "accept-encoding")

    fun matchesRoute(recording: Recording, endpoint: Endpoint): Boolean {
        if (endpoint.httpMethod != "ANY" && !endpoint.httpMethod.equals(recording.method, ignoreCase = true)) return false
        val pattern = recording.pattern ?: shortUrl(recording, RecordingKind.INCOMING).substringBefore('?')
        return pattern == endpoint.path
    }

    fun shortUrl(r: Recording, kind: RecordingKind): String {
        val uri = runCatching { URI.create(r.url ?: "") }.getOrNull() ?: return r.url.orEmpty()
        val path = (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
        return if (kind == RecordingKind.OUTGOING) "${uri.host ?: ""}$path" else path
    }

    /** The Request tab: the body, formatted (method and address are in the title); for a message, its routing facts. */
    fun request(r: Recording): String = buildString {
        if (r.kind == RecordingKind.MESSAGE) {
            r.exchange?.takeIf { it.isNotEmpty() }?.let { append("exchange      $it\n") }
            r.routingKey?.takeIf { it.isNotEmpty() }?.let { append("routing key   $it\n") }
            r.contentType?.let { append("content type  $it\n") }
            r.detail?.let { append("$it\n") }
            if (isEmpty()) append("No routing information was recorded.")
            return@buildString
        }
        append(r.body?.let { HttpExecutor.pretty(it) } ?: "No body")
    }

    /** The Response (or Message) tab: the body, formatted. */
    fun response(r: Recording): String = buildString {
        if (r.kind == RecordingKind.MESSAGE) {
            append(r.body?.let { HttpExecutor.pretty(it) } ?: "No body")
            r.error?.let { append("\n\nThe listener failed: $it") }
            return@buildString
        }
        if (r.status == null) {
            append(r.error ?: "No response was recorded")
            return@buildString
        }
        append(r.responseBody?.let { HttpExecutor.pretty(it) } ?: "No body")
        r.error?.let { append("\n\n$it") }
    }

    private fun headerLines(list: List<Pair<String, String>>, empty: String) =
        if (list.isEmpty()) empty else list.joinToString("\n") { "${it.first}: ${it.second}" }

    /** The Request Headers tab. */
    fun requestHeaders(r: Recording): String = headerLines(r.headers, "No request headers were recorded.")

    /** The Response Headers tab; a message only has the headers it came with. */
    fun responseHeaders(r: Recording): String =
        if (r.kind == RecordingKind.MESSAGE) headerLines(r.headers, "No headers were recorded.")
        else headerLines(r.responseHeaders, "No response headers were recorded.")

    /** The request as a `.http` request; masked headers become a placeholder comment. */
    fun httpFile(r: Recording): String = buildString {
        append("### ${r.handler ?: r.client ?: "Recorded request"}\n")
        append("${r.method} ${r.url}\n")
        r.headers.filter { it.first.lowercase() !in SKIP_ON_REPLAY }.forEach { (name, value) ->
            if (value == "***") append("# $name: {{${name.replace(Regex("[^A-Za-z0-9]+"), "_")}}}\n") else append("$name: $value\n")
        }
        r.body?.let { append("\n").append(it).append('\n') }
    }

    fun asRequest(r: Recording): ParsedRequest = ParsedRequest(
        method = r.method ?: "GET",
        url = r.url ?: "",
        headers = r.headers.filter { it.first.lowercase() !in SKIP_ON_REPLAY && it.second != "***" },
        body = r.body,
    )

    /** The replay result next to what was recorded. */
    fun comparison(recorded: Recording, now: HttpResult): String = buildString {
        val sameStatus = recorded.status == now.status
        val before = recorded.responseBody?.let { HttpExecutor.pretty(it).trim() }
        val after = HttpExecutor.pretty(now.body).trim()
        val sameBody = before == after || (before == null && after.isEmpty())
        append(
            when {
                sameStatus && sameBody -> "Same as recorded\n"
                sameStatus -> "Same status, different body\n"
                else -> "Different: recorded ${recorded.status ?: "no response"}, now ${now.status}\n"
            },
        )
        append("${now.status} ${HttpExecutor.reason(now.status)}   ${now.millis} ms\n\n")
        now.headers.forEach { append("${it.first}: ${it.second}\n") }
        append("\n").append(after).append('\n')
        if (!sameBody && before != null) append("\n--- recorded body ---\n").append(before).append('\n')
    }
}
