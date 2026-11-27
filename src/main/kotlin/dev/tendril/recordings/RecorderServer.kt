package dev.tendril.recordings

import com.google.gson.JsonParser
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** The port and token the recorder agent uses to reach this IDE; stable across restarts so run configurations stay valid. */
@Service(Service.Level.APP)
@State(name = "TendrilRecorder", storages = [Storage("tendril-recorder.xml")])
class RecorderSettings : PersistentStateComponent<RecorderSettings.State> {
    class State {
        var token: String = ""
        var port: Int = 17843
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    val token: String
        get() {
            if (state.token.isEmpty()) state.token = UUID.randomUUID().toString().replace("-", "")
            return state.token
        }

    fun replaceToken(value: String) {
        state.token = value
    }

    var port: Int
        get() = state.port
        set(value) {
            state.port = value
        }

    companion object {
        fun getInstance(): RecorderSettings = service()
    }
}

/**
 * Receives what the recorder inside the application reports, on the loopback interface only and only with the shared
 * token, and can reach back into the application (republish a message) through the recorder's control server.
 */
@Service(Service.Level.APP)
class RecorderServer : Disposable {
    private val stores = ConcurrentHashMap<String, RecordingStore>()
    private var server: HttpServer? = null

    /** The port actually bound, or 0 when the server could not start. */
    @Volatile
    var port: Int = 0
        private set

    fun register(project: Project) {
        stores[key(project)] = RecordingStore.getInstance(project)
        ensureStarted()
    }

    fun unregister(project: Project) {
        stores.remove(key(project))
    }

    private fun key(project: Project) = project.locationHash

    @Synchronized
    private fun ensureStarted() {
        if (server != null) return
        val settings = RecorderSettings.getInstance()
        for (candidate in settings.port..settings.port + PORT_TRIES) {
            val created = runCatching {
                HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), candidate), 0)
            }.getOrNull() ?: continue
            created.createContext("/events") { handle(it) }
            created.executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "tendril-recorder-server").apply { isDaemon = true } }
            created.start()
            server = created
            port = candidate
            if (candidate != settings.port) {
                settings.port = candidate
                NotificationGroupManager.getInstance().getNotificationGroup("Tendril")?.createNotification(
                    "The recorder port is now $candidate. Add the recorder to the run configuration again to use it.",
                    NotificationType.WARNING,
                )?.notify(ProjectManager.getInstance().openProjects.firstOrNull())
            }
            return
        }
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val sent = exchange.requestHeaders.getFirst("X-Tendril-Token")
            if (!exchange.remoteAddress.address.isLoopbackAddress || sent != RecorderSettings.getInstance().token) {
                exchange.sendResponseHeaders(403, -1)
                return
            }
            val json = JsonParser.parseString(String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)).asJsonObject
            val projectKey = json.get("project")?.takeIf { it.isJsonPrimitive }?.asString
            val store = stores[projectKey] ?: stores.values.singleOrNull()
            if (store != null) {
                if (json.get("kind")?.asString == "hello") {
                    store.touch(json.get("control")?.asInt)
                    // a recorder that knows mocks reports the version it holds ("" for none)
                    dev.tendril.mocks.MockStore.getInstance(store.project)
                        .onHello(json.get("mocks")?.takeIf { it.isJsonPrimitive }?.asString)
                } else {
                    Recording.fromJson(json)?.let {
                        store.touch(null)
                        store.add(it)
                    }
                }
            }
            exchange.sendResponseHeaders(204, -1)
        } catch (e: Exception) {
            runCatching { exchange.sendResponseHeaders(400, -1) }
        } finally {
            exchange.close()
        }
    }

    /** Republishes a recorded message through the application's own RabbitTemplate / KafkaTemplate. */
    fun republish(project: Project, r: Recording): Result<String> = runCatching {
        val port = RecordingStore.getInstance(project).controlPort
        check(port > 0) { "The application has not connected yet. Start it with the recorder first." }
        val form = buildList {
            add("transport" to (r.transport ?: ""))
            add("destination" to (r.destination ?: ""))
            add("exchange" to (r.exchange ?: ""))
            add("routingKey" to (r.routingKey ?: ""))
            add("contentType" to (r.contentType ?: ""))
            add("bodyEncoding" to (r.bodyEncoding ?: "text"))
            add("body" to (r.body ?: ""))
            r.headers.forEach { (k, v) -> add("h.$k" to v) }
        }
        post(port, "/replay", form)
        "Republished"
    }

    /**
     * Sends a new message written in the IDE through the running application (its own connection settings): to a
     * Kafka topic, or to a Rabbit exchange and routing key. Answers where it went. Blocks: call off the EDT.
     */
    fun sendMessage(project: Project, form: List<Pair<String, String>>): Result<String> = runCatching {
        val port = RecordingStore.getInstance(project).controlPort
        check(port > 0 && RecorderAgent.isConnected(project)) { "The application is not connected. Start it with the recorder first." }
        post(port, "/send", form)
    }

    private fun post(port: Int, path: String, form: List<Pair<String, String>>): String {
        val body = form.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)
        }
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-Tendril-Token", RecorderSettings.getInstance().token)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        // a recorder from before this endpoint answers 404
        check(response.statusCode() != 404) { "Restart the application: its recorder is older than this plugin" }
        check(response.statusCode() == 200) { response.body().ifBlank { "HTTP ${response.statusCode()}" } }
        return response.body()
    }

    override fun dispose() {
        server?.stop(0)
    }

    companion object {
        private const val PORT_TRIES = 20
        fun getInstance(): RecorderServer = service()
    }
}

/** Starts listening as soon as a project opens, so the application can report from the first request on. */
class RecordingsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        RecorderServer.getInstance().register(project)
    }
}

/** Puts the agent jar in a stable place and builds the JVM argument that attaches it. */
object RecorderAgent {
    private const val RESOURCE = "/recorder/tendril-recorder.jar"

    /** `~/.tendril/tendril-recorder.jar`, refreshed when the plugin carries a different jar. */
    fun jarPath(): Path {
        val target = Path.of(System.getProperty("user.home"), ".tendril", "tendril-recorder.jar")
        val bytes = RecorderAgent::class.java.getResourceAsStream(RESOURCE)?.use { it.readAllBytes() }
            ?: error("The recorder jar is missing from the plugin")
        if (!Files.exists(target) || Files.size(target) != bytes.size.toLong()) {
            Files.createDirectories(target.parent)
            val temp = Files.createTempFile(target.parent, "recorder", ".jar")
            Files.write(temp, bytes)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    /** The agent argument as one JVM parameter, unquoted: for a parameter list, which does its own quoting. */
    fun rawArgument(project: Project): String {
        RecorderServer.getInstance().register(project)
        val jar = jarPath().toString().replace('\\', '/')
        return "-javaagent:$jar=port=${RecorderServer.getInstance().port},token=${RecorderSettings.getInstance().token},project=${project.locationHash}"
    }

    /** `-javaagent:<jar>=port=...,token=...,project=...`, quoted when it contains a space (for a command line). */
    fun argument(project: Project): String = rawArgument(project).let { if (' ' in it) "\"$it\"" else it }

    /** Matches an agent argument written by [argument], whatever the port or token were. */
    val ARGUMENT_PATTERN = Regex("""\"?-javaagent:[^\s"]*tendril-recorder\.jar=[^\s"]*\"?""")

    /** True while the application has reported in the last 40 seconds (it announces itself every 15). */
    fun isConnected(project: Project): Boolean {
        val store = RecordingStore.getInstance(project)
        return store.lastContact > 0 && System.currentTimeMillis() - store.lastContact < 40_000
    }
}
