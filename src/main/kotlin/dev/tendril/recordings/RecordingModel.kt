package dev.tendril.recordings

import com.google.gson.JsonObject
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

enum class RecordingKind { INCOMING, OUTGOING, MESSAGE }

data class SqlSummary(val template: String, val count: Long, val durationMicros: Long)

/** One event sent by the recorder inside the running application. */
class Recording(
    val kind: RecordingKind,
    val time: Long,
    val durationMs: Long,
    val flow: Long,
    val method: String?,
    val url: String?,
    val headers: List<Pair<String, String>>,
    val body: String?,
    val bodyEncoding: String?,
    val status: Int?,
    val responseHeaders: List<Pair<String, String>>,
    val responseBody: String?,
    val responseBodyEncoding: String?,
    val handler: String?,
    val pattern: String?,
    val client: String?,
    val error: String?,
    // messages
    val transport: String?,
    val destination: String?,
    val exchange: String?,
    val routingKey: String?,
    val contentType: String?,
    val replayable: Boolean,
    val detail: String?,
    val sql: List<SqlSummary> = emptyList(),
) {
    companion object {
        fun fromJson(o: JsonObject): Recording? {
            val kind = when (o.str("kind")) {
                "http_in" -> RecordingKind.INCOMING
                "http_out" -> RecordingKind.OUTGOING
                "message" -> RecordingKind.MESSAGE
                else -> return null
            }
            val extra = listOfNotNull(
                o.str("valueType")?.let { "value type $it" },
                o.long("partition")?.let { "partition $it" },
                o.long("offset")?.let { "offset $it" },
            ).joinToString(", ").ifEmpty { null }
            return Recording(
                kind = kind,
                time = o.long("time") ?: System.currentTimeMillis(),
                durationMs = o.long("durationMs") ?: 0,
                flow = o.long("flow") ?: 0,
                method = o.str("method"),
                url = o.str("url"),
                headers = o.pairs("headers"),
                body = o.str("body"),
                bodyEncoding = o.str("bodyEncoding"),
                status = o.long("status")?.toInt(),
                responseHeaders = o.pairs("responseHeaders"),
                responseBody = o.str("responseBody"),
                responseBodyEncoding = o.str("responseBodyEncoding"),
                handler = o.str("handler"),
                pattern = o.str("pattern"),
                client = o.str("client"),
                error = o.str("error"),
                transport = o.str("transport"),
                destination = o.str("destination"),
                exchange = o.str("exchange"),
                routingKey = o.str("routingKey"),
                contentType = o.str("contentType"),
                replayable = o.get("replayable")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                detail = extra,
                sql = o.get("sql")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { element ->
                    if (!element.isJsonObject) return@mapNotNull null
                    val summary = element.asJsonObject
                    SqlSummary(
                        summary.str("template") ?: return@mapNotNull null,
                        summary.long("count") ?: return@mapNotNull null,
                        summary.long("durationMicros") ?: return@mapNotNull null,
                    )
                }.orEmpty(),
            )
        }

        private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
        private fun JsonObject.long(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive }?.asLong
        private fun JsonObject.pairs(key: String): List<Pair<String, String>> =
            get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
                e.takeIf { it.isJsonArray && it.asJsonArray.size() == 2 }?.asJsonArray?.let { it[0].asString to it[1].asString }
            }.orEmpty()
    }
}

/** What the recorder sent for one project: the latest [max] recordings of each kind. */
@Service(Service.Level.PROJECT)
class RecordingStore(private val project: Project) : Disposable {
    private val lists = RecordingKind.entries.associateWith { ArrayDeque<Recording>() }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Port of the control server inside the application, announced by the recorder; 0 until it has connected. */
    @Volatile
    var controlPort: Int = 0

    @Volatile
    var lastContact: Long = 0

    /** How many recordings of each kind are kept; the user sets it in the Recordings window. */
    var max: Int
        get() = PropertiesComponent.getInstance(project).getInt(MAX_KEY, DEFAULT_MAX).coerceIn(1, LIMIT)
        set(value) {
            PropertiesComponent.getInstance(project).setValue(MAX_KEY, value.coerceIn(1, LIMIT), DEFAULT_MAX)
            synchronized(lists) { lists.values.forEach { while (it.size > max) it.removeLast() } }
            listeners.forEach { it() }
        }

    fun add(recording: Recording) {
        synchronized(lists) {
            val list = lists.getValue(recording.kind)
            list.addFirst(recording)
            while (list.size > max) list.removeLast()
        }
        listeners.forEach { it() }
    }

    fun snapshot(kind: RecordingKind): List<Recording> = synchronized(lists) { lists.getValue(kind).toList() }

    fun clear() {
        synchronized(lists) { lists.values.forEach { it.clear() } }
        listeners.forEach { it() }
    }

    fun touch(controlPort: Int?) {
        lastContact = System.currentTimeMillis()
        if (controlPort != null && controlPort > 0) this.controlPort = controlPort
        listeners.forEach { it() }
    }

    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        com.intellij.openapi.util.Disposer.register(parent) { listeners -= listener }
    }

    override fun dispose() {
        RecorderServer.getInstance().unregister(project)
    }

    companion object {
        const val DEFAULT_MAX = 10
        const val LIMIT = 1000
        private const val MAX_KEY = "tendril.recordings.max"
        fun getInstance(project: Project): RecordingStore = project.service()
    }
}
