package dev.tendril.messaging

import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.tendril.recordings.RecorderServer
import javax.swing.JComponent

/**
 * Right-click | Send Test Message inside a `@KafkaListener` or `@RabbitListener` method: a message written in the
 * IDE (a sample from the payload type to start from) is sent through the running application to the listener's
 * topic or queue. What the listener then does shows in the Messages tab of the Recordings window.
 */
class SendTestMessageAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null && file != null &&
            Listeners.methodAt(file, editor.caretModel.offset) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val method = Listeners.methodAt(file, editor.caretModel.offset) ?: return
        val target = Listeners.target(project, method) ?: return
        val dialog = SendMessageDialog(project, target, Listeners.samplePayload(target))
        if (!dialog.showAndGet()) return
        val messages = dialog.messages()
        dialog.remember()
        object : Task.Backgroundable(project, "Sending test message", true) {
            override fun run(indicator: ProgressIndicator) {
                val results = messages.map { RecorderServer.getInstance().sendMessage(project, it) }
                val failure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
                val text = failure?.let { "The message was not sent: ${it.message}" }
                    ?: results.map { it.getOrThrow() }.let { if (it.size == 1) it.single() else "${it.size} messages sent. ${it.last()}" }
                NotificationGroupManager.getInstance().getNotificationGroup("Tendril")
                    ?.createNotification(text, if (failure == null) NotificationType.INFORMATION else NotificationType.ERROR)
                    ?.apply {
                        if (failure == null) addAction(NotificationAction.createSimpleExpiring("Show in Recordings") {
                            ToolWindowManager.getInstance(project).getToolWindow("Recordings")?.activate(null)
                        })
                    }
                    ?.notify(project)
            }
        }.queue()
    }
}

internal class SendMessageDialog(private val project: Project, private val target: ListenerTarget, sample: String) : DialogWrapper(project) {
    private val store = PropertiesComponent.getInstance(project)
    private val key = "tendril.message.${target.transport}.${target.label}"

    private val destination = ComboBox(
        (listOfNotNull(target.routingKey) + target.destinations).distinct().ifEmpty { listOf("") }.toTypedArray(),
    ).apply { isEditable = true }
    private val exchange = JBTextField(target.exchange.orEmpty())
    private val messageKey = JBTextField()
    private val typeHeader = JBCheckBox("Add the type header __TypeId__ = ${target.payloadClass}", target.payloadClass != null).apply {
        toolTipText = "What Spring Kafka's JsonSerializer adds; a JsonDeserializer without a default type needs it"
    }
    private val headers = JBTextArea(3, 40).apply { emptyText.text = "Name: value, one per line" }
    private val payload = EditorTextField(
        store.getValue("$key.payload") ?: sample, project,
        if (target.isText && !target.batch) PlainTextFileType.INSTANCE else FileTypeManager.getInstance().getFileTypeByExtension("json"),
    ).apply {
        setOneLineMode(false)
        preferredSize = JBUI.size(560, 260)
    }

    init {
        title = "Send Test Message to ${target.label}"
        setOKButtonText("Send")
        store.getValue("$key.destination")?.let { destination.selectedItem = it }
        store.getValue("$key.exchange")?.let { exchange.text = it }
        store.getValue("$key.key")?.let { messageKey.text = it }
        store.getValue("$key.headers")?.let { headers.text = it }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        if (target.unresolved.isNotEmpty()) {
            row { comment("Could not read ${target.unresolved.joinToString(", ")}: fill in the ${if (target.isKafka) "topic" else "queue"}.") }
        }
        if (target.isKafka) {
            row("Topic:") { cell(destination).align(Align.FILL) }
            row("Key:") { cell(messageKey).align(Align.FILL).comment("Optional; decides the partition") }
        } else {
            row("Exchange:") { cell(exchange).align(Align.FILL).comment("Empty: the default exchange, which delivers to the queue named by the routing key") }
            row("Routing key:") { cell(destination).align(Align.FILL) }
        }
        row("Headers:") { cell(headers).align(Align.FILL) }
        if (target.isKafka && target.payloadClass != null) row { cell(typeHeader) }
        row(if (target.batch) "Payload (one message per element):" else "Payload:") {}
        row { cell(payload).align(Align.FILL) }
        row { comment("Sent through the running application, with its own connection settings. The listener's input shows in Recordings | Messages.") }
    }

    override fun doValidate(): ValidationInfo? {
        if (destinationText().isEmpty()) return ValidationInfo(if (target.isKafka) "Enter a topic" else "Enter a routing key", destination)
        if (target.isText && !target.batch) return null
        val json = runCatching { JsonParser.parseString(payload.text) }.getOrNull() ?: return ValidationInfo("The payload is not valid JSON", payload)
        if (target.batch && !json.isJsonArray) return ValidationInfo("A batch listener takes a JSON array: one message per element", payload)
        return null
    }

    private fun destinationText() = (destination.editor.item as? String ?: destination.selectedItem as? String).orEmpty().trim()

    /** The forms to post, one per message. */
    fun messages(): List<List<Pair<String, String>>> = MessageForms.build(
        target, destinationText(), exchange.text.trim(), messageKey.text.trim(), typeHeader.isSelected, headers.text, payload.text,
    )

    fun remember() {
        store.setValue("$key.payload", payload.text)
        store.setValue("$key.destination", destinationText())
        store.setValue("$key.exchange", exchange.text)
        store.setValue("$key.key", messageKey.text)
        store.setValue("$key.headers", headers.text)
    }

    override fun getPreferredFocusedComponent(): JComponent = payload
}

/** What the recorder's `/send` takes, built from the dialog's fields. */
internal object MessageForms {
    fun build(
        target: ListenerTarget, destination: String, exchange: String, key: String, typeHeader: Boolean,
        headers: String, payload: String,
    ): List<List<Pair<String, String>>> {
        val bodies = if (target.batch) {
            JsonParser.parseString(payload).asJsonArray.map { if (it.isJsonPrimitive) it.asString else it.toString() }
        } else listOf(if (target.isText) payload else JsonParser.parseString(payload).toString())
        val extra = headers.lines().mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else "h." + line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
        return bodies.map { body ->
            buildList {
                add("transport" to target.transport)
                add("body" to body)
                if (target.isKafka) {
                    add("destination" to destination)
                    key.takeIf { it.isNotEmpty() }?.let { add("key" to it) }
                    target.payloadClass?.takeIf { typeHeader }?.let { add("h.__TypeId__" to it) }
                } else {
                    add("exchange" to exchange)
                    add("routingKey" to destination)
                    add("contentType" to if (target.isText) "text/plain" else "application/json")
                }
                addAll(extra)
            }
        }
    }
}
