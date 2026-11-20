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

private class RecordingsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
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
private class RecordingPane(private val project: Project, private val kind: RecordingKind) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<Recording>()
    private val list = JBList(model)
    private val request = textArea()
    private val response = textArea()
    private val replay = textArea()
    private val details = JBTabbedPane()
    private val replayButton = JButton(if (kind == RecordingKind.MESSAGE) "Republish" else if (kind == RecordingKind.OUTGOING) "Send again" else "Replay")
    private val openButton = JButton("Open in request editor")
    private val hint = JBLabel(" ")

    init {
        list.cellRenderer = RecordingRenderer(kind)
        list.emptyText.setText("Nothing recorded yet").appendLine(emptyHint())
        list.addListSelectionListener { if (!it.valueIsAdjusting) select() }

        details.addTab("Request", JBScrollPane(request))
        details.addTab(if (kind == RecordingKind.MESSAGE) "Message" else "Response", JBScrollPane(response))
        details.addTab("Replay", JBScrollPane(replay))

        replayButton.addActionListener { replaySelected() }
        openButton.addActionListener { openSelected() }
        openButton.isVisible = kind != RecordingKind.MESSAGE
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(replayButton)
            add(openButton)
            add(hint.apply { foreground = JBColor.GRAY })
        }
        val right = JPanel(BorderLayout()).apply {
            add(actions, BorderLayout.NORTH)
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
        model.clear()
        recordings.forEach { model.addElement(it) }
        list.setSelectedValue(selected, true)
        if (list.selectedIndex < 0 && model.size() > 0) list.selectedIndex = 0
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
        request.text = r?.let { RecordingText.request(it) }.orEmpty()
        response.text = r?.let { RecordingText.response(it) }.orEmpty()
        replay.text = ""
        request.caretPosition = 0
        response.caretPosition = 0
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
        details.selectedIndex = 2
        replay.text = "Sending…"
        replayButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = if (kind == RecordingKind.MESSAGE) {
                RecorderServer.getInstance().republish(project, r).fold({ it }, { "Failed: ${it.message}" })
            } else {
                runCatching { HttpExecutor.send(RecordingText.asRequest(r)) }
                    .fold({ RecordingText.comparison(r, it) }, { "The request failed: ${it.message ?: it.javaClass.simpleName}" })
            }
            ApplicationManager.getApplication().invokeLater({
                replay.text = text
                replay.caretPosition = 0
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

    private fun textArea() = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        margin = JBUI.insets(4, 6)
    }
}

private class RecordingRenderer(private val kind: RecordingKind) : ColoredListCellRenderer<Recording>() {
    override fun customizeCellRenderer(list: JList<out Recording>, value: Recording?, index: Int, selected: Boolean, hasFocus: Boolean) {
        val r = value ?: return
        when (kind) {
            RecordingKind.MESSAGE -> {
                append((r.transport ?: "message").padEnd(7), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append(r.destination ?: "")
                r.routingKey?.takeIf { it.isNotEmpty() && it != r.destination }?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                r.error?.let { append("  failed", SimpleTextAttributes.ERROR_ATTRIBUTES) }
            }
            else -> {
                val method = r.method ?: "GET"
                append(method.padEnd(7), HttpMethodColors.attributes(method))
                append(RecordingText.shortUrl(r, kind))
                r.status?.let { append("  $it", statusAttributes(it)) }
                if (r.status == null && r.error != null) append("  failed", SimpleTextAttributes.ERROR_ATTRIBUTES)
            }
        }
        append("  ${RecordingText.TIME.format(Date(r.time))}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (r.durationMs > 0) append("  ${r.durationMs} ms", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun statusAttributes(status: Int): SimpleTextAttributes {
        val color = when (status) {
            in 200..299 -> JBColor(0x2E7D32, 0x6AAB73)
            in 300..399 -> JBColor(0xB26A00, 0xE0A44D)
            else -> JBColor(0xC62828, 0xF28B82)
        }
        return SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
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

    fun request(r: Recording): String = buildString {
        if (r.kind == RecordingKind.MESSAGE) {
            append("${r.transport} · ${r.destination}\n")
            if (!r.exchange.isNullOrEmpty()) append("exchange     ${r.exchange}\n")
            if (!r.routingKey.isNullOrEmpty()) append("routing key  ${r.routingKey}\n")
            r.contentType?.let { append("content type ${it}\n") }
            r.detail?.let { append("$it\n") }
            if (r.headers.isNotEmpty()) append("\nHeaders\n").append(r.headers.joinToString("\n") { "${it.first}: ${it.second}" }).append('\n')
            return@buildString
        }
        append("${r.method} ${r.url}\n")
        r.handler?.let { append("handled by $it\n") }
        r.client?.let { append("client $it\n") }
        append("\n")
        r.headers.forEach { append("${it.first}: ${it.second}\n") }
        r.body?.let { append("\n").append(HttpExecutor.pretty(it)).append('\n') }
    }

    fun response(r: Recording): String = buildString {
        if (r.kind == RecordingKind.MESSAGE) {
            append(r.body?.let { HttpExecutor.pretty(it) } ?: "(no body)")
            r.error?.let { append("\n\nThe listener failed: $it") }
            return@buildString
        }
        if (r.status == null) {
            append(r.error ?: "No response was recorded")
            return@buildString
        }
        append("${r.status} ${HttpExecutor.reason(r.status)}\n".replace("  ", " "))
        r.responseHeaders.forEach { append("${it.first}: ${it.second}\n") }
        r.responseBody?.let { append("\n").append(HttpExecutor.pretty(it)).append('\n') }
        r.error?.let { append("\n$it") }
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
