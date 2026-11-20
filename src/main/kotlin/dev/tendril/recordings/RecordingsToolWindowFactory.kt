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
import com.intellij.ui.*
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import dev.tendril.endpoints.EndpointsToolWindowFactory
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
import javax.swing.Timer

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
}

internal class RecordingsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val store = RecordingStore.getInstance(project)
    private val status = SimpleColoredComponent()
    private val tabs = JBTabbedPane()
    private val panes = mapOf(
        RecordingKind.INCOMING to RecordingPane(project, RecordingKind.INCOMING),
        RecordingKind.OUTGOING to RecordingPane(project, RecordingKind.OUTGOING),
        RecordingKind.MESSAGE to RecordingPane(project, RecordingKind.MESSAGE),
    )

    init {
        tabs.addTab("Incoming", panes.getValue(RecordingKind.INCOMING))
        tabs.addTab("Outgoing", panes.getValue(RecordingKind.OUTGOING))
        tabs.addTab("Messages", panes.getValue(RecordingKind.MESSAGE))

        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 4)
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
        }
        add(top, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)

        store.addListener(this) { ApplicationManager.getApplication().invokeLater({ update() }, ModalityState.any()) }
        // the connection state fades after the application stops, with no event to tell us
        Timer(5000) { update() }.apply { isRepeats = true; start() }.also { timer -> Disposer.register(this) { timer.stop() } }
        update()
    }

    override fun dispose() {}

    private fun update() {
        panes.forEach { (kind, pane) ->
            pane.show(store.snapshot(kind))
            tabs.setTitleAt(kind.ordinal, "${TITLES.getValue(kind)}  ${store.snapshot(kind).size}")
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
internal class RecordingPane(private val project: Project, private val kind: RecordingKind) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<Recording>()
    private val list = JBList(model)
    private val renderer = PainterListRenderer<Recording>(rightAligned = true, bind = ::bindRow)
    private val summary = SimpleColoredComponent()
    private val facts = SimpleColoredComponent()
    private val request = Editors.viewer(project)
    private val response = Editors.viewer(project)
    private val headers = Editors.viewer(project)
    private val replay = Editors.viewer(project)
    private val details = JBTabbedPane()
    private val replayButton = JButton(if (kind == RecordingKind.MESSAGE) "Republish" else if (kind == RecordingKind.OUTGOING) "Send again" else "Replay")
    private val openButton = JButton("Open in request editor")
    private val hint = JBLabel(" ")

    init {
        list.cellRenderer = renderer
        list.emptyText.setText("Nothing recorded yet").appendLine(emptyHint())
        list.addListSelectionListener { if (!it.valueIsAdjusting) select() }

        details.addTab("Request", request)
        details.addTab(if (kind == RecordingKind.MESSAGE) "Message" else "Response", response)
        details.addTab("Headers", headers)
        details.addTab("Replay", replay)

        replayButton.addActionListener { replaySelected() }
        openButton.addActionListener { openSelected() }
        openButton.isVisible = kind != RecordingKind.MESSAGE
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(replayButton)
            add(openButton)
            add(hint.apply { foreground = JBColor.GRAY })
        }
        val titles = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(summary, BorderLayout.NORTH)
            add(facts.apply { border = JBUI.Borders.emptyTop(2) }, BorderLayout.CENTER)
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10, 6, 10)
            add(titles, BorderLayout.NORTH)
            add(actions.apply { border = JBUI.Borders.emptyTop(6) }, BorderLayout.SOUTH)
        }
        val right = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(details, BorderLayout.CENTER)
        }
        val splitter = OnePixelSplitter(false, "tendril.recordings.$kind", 0.42f)
        splitter.firstComponent = JBScrollPane(list)
        splitter.secondComponent = right
        add(splitter, BorderLayout.CENTER)
        select()
    }

    fun show(recordings: List<Recording>) {
        val selected = list.selectedValue
        // columns: one badge width, one text width, so status and time line up
        renderer.painter.font = list.font
        renderer.painter.badgeWidth = renderer.painter.badgeColumn(recordings.map { badgeOf(it) } + "DELETE")
        renderer.painter.primaryColumn = minOf(
            JBUI.scale(360), recordings.maxOfOrNull { renderer.painter.measure(primaryOf(it)) } ?: 0,
        )
        model.clear()
        recordings.forEach { model.addElement(it) }
        list.setSelectedValue(selected, true)
        if (list.selectedIndex < 0 && model.size() > 0) list.selectedIndex = 0
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
            if (kind == RecordingKind.MESSAGE && r.error != null) add(Fragment("failed", Palette.FAILED, bold = true))
            if (r.durationMs > 0) add(Fragment("   ${r.durationMs} ms", Palette.MUTED))
            add(Fragment("   ${RecordingText.TIME.format(Date(r.time))}", Palette.MUTED))
        }
    }

    private fun emptyHint() = when (kind) {
        RecordingKind.INCOMING -> "Requests the application serves appear here, with their responses."
        RecordingKind.OUTGOING -> "Calls made by Feign clients appear here."
        RecordingKind.MESSAGE -> "Messages received by @RabbitListener and @KafkaListener appear here."
    }

    private fun select() {
        val r = list.selectedValue
        replayButton.isEnabled = r != null && canReplay(r)
        openButton.isEnabled = r != null
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
                    summary.append("   $it ${HttpExecutor.reason(it)}".trimEnd(), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Palette.status(it)))
                }
            }
            val line = listOfNotNull(
                r.handler?.let { "handled by $it" }, r.client?.let { "client $it" },
                r.durationMs.takeIf { it > 0 }?.let { "$it ms" }, r.flow.takeIf { it > 0 }?.let { "flow $it" },
                RecordingText.TIME.format(Date(r.time)),
            ).joinToString("      ")
            facts.append(line, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        Editors.show(request, r?.let { RecordingText.request(it) }.orEmpty(), if (r != null && kind != RecordingKind.MESSAGE) HttpFileType else PlainTextFileType.INSTANCE)
        val body = r?.let { RecordingText.response(it) }.orEmpty()
        Editors.show(response, body, Editors.typeOf(body))
        Editors.show(headers, r?.let { RecordingText.headers(it) }.orEmpty(), PlainTextFileType.INSTANCE)
        Editors.show(replay, "", PlainTextFileType.INSTANCE)
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
        details.selectedIndex = 3
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

    fun shortUrl(r: Recording, kind: RecordingKind): String {
        val uri = runCatching { URI.create(r.url ?: "") }.getOrNull() ?: return r.url.orEmpty()
        val path = (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
        return if (kind == RecordingKind.OUTGOING) "${uri.host ?: ""}$path" else path
    }

    /** The Request tab: for HTTP, in `.http` syntax (so it gets that highlighting); for a message, its routing facts. */
    fun request(r: Recording): String = buildString {
        if (r.kind == RecordingKind.MESSAGE) {
            r.exchange?.takeIf { it.isNotEmpty() }?.let { append("exchange      $it\n") }
            r.routingKey?.takeIf { it.isNotEmpty() }?.let { append("routing key   $it\n") }
            r.contentType?.let { append("content type  $it\n") }
            r.detail?.let { append("$it\n") }
            if (isEmpty()) append("No routing information was recorded.")
            return@buildString
        }
        append("${r.method} ${r.url}\n")
        r.headers.forEach { append("${it.first}: ${it.second}\n") }
        r.body?.let { append("\n").append(HttpExecutor.pretty(it)).append('\n') }
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

    /** The Headers tab. */
    fun headers(r: Recording): String = buildString {
        fun section(title: String, list: List<Pair<String, String>>) {
            if (list.isEmpty()) return
            if (isNotEmpty()) append('\n')
            append(title).append('\n')
            list.forEach { append("  ${it.first}: ${it.second}\n") }
        }
        section(if (r.kind == RecordingKind.MESSAGE) "Message headers" else "Request headers", r.headers)
        section("Response headers", r.responseHeaders)
        if (isEmpty()) append("No headers were recorded.")
    }

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
