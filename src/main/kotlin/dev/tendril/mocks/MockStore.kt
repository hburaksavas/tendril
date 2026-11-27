package dev.tendril.mocks

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.tendril.recordings.RecorderAgent
import dev.tendril.recordings.RecorderSettings
import dev.tendril.recordings.RecordingStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The mocks of a project: the files under `.tendril/mocks`, read again whenever one changes, and sent to the
 * application running with the recorder. The application reports the version it holds every 15 seconds, so a
 * restarted application gets them again without anyone asking.
 */
@Service(Service.Level.PROJECT)
class MockStore(private val project: Project) : Disposable {
    @Volatile
    var entries: List<MockEntry> = emptyList()
        private set

    /** What the user last needs to know about the mocks in the application. */
    @Volatile
    var status: String = "Not sent yet"
        private set

    @Volatile
    private var applicationVersion: String? = null

    @Volatile
    private var applicationSupportsMocks: Boolean? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val pushing = AtomicBoolean()
    private val pushAgain = AtomicBoolean()

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val dir = directory()?.toString()?.replace('\\', '/') ?: return
                if (events.any { it.path.replace('\\', '/').startsWith(dir) }) reload()
            }
        })
        reload()
    }

    fun directory(): Path? = project.basePath?.let { MockFiles.directory(it) }

    /** All mocks on or off at once; off sends an empty set, the files stay. */
    var enabled: Boolean
        get() = PropertiesComponent.getInstance(project).getBoolean(ENABLED_KEY, true)
        set(value) {
            PropertiesComponent.getInstance(project).setValue(ENABLED_KEY, value, true)
            changed()
            push()
        }

    /** The rules the application should hold now, in order. */
    fun active(): List<Pair<String, MockDefinition>> = if (enabled) MockFiles.active(entries) else emptyList()

    fun reload() {
        ApplicationManager.getApplication().executeOnPooledThread {
            entries = directory()?.let { runCatching { MockFiles.read(it) }.getOrNull() }.orEmpty()
            changed()
            push()
        }
    }

    /** Called with the version the application reports; null when its recorder predates mocks. */
    fun onHello(version: String?) {
        applicationSupportsMocks = version != null
        if (version == null) {
            if (active().isNotEmpty()) setStatus("Restart the application: its recorder is older than this plugin and cannot mock")
            return
        }
        applicationVersion = version
        if (version != MockFiles.form(active()).second) push()
    }

    /** Sends the current set to the application, if one is connected; overlapping calls are merged. */
    fun push() {
        if (!pushing.compareAndSet(false, true)) {
            pushAgain.set(true)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                do {
                    pushAgain.set(false)
                    send()
                } while (pushAgain.get())
            } finally {
                pushing.set(false)
            }
        }
    }

    private fun send() {
        val rules = active()
        val recordings = RecordingStore.getInstance(project)
        val port = recordings.controlPort
        if (port <= 0 || !RecorderAgent.isConnected(project)) {
            setStatus(if (rules.isEmpty()) "No active mocks" else "${count(rules.size)} waiting for the application to start with the recorder")
            return
        }
        if (applicationSupportsMocks == false) return
        val (form, version) = MockFiles.form(rules)
        val result = runCatching {
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/mocks"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-Tendril-Token", RecorderSettings.getInstance().token)
                .POST(HttpRequest.BodyPublishers.ofString(form)).build()
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { response.body().ifBlank { "HTTP ${response.statusCode()}" } }
        }
        if (result.isSuccess) {
            applicationVersion = version
            setStatus(
                when {
                    !enabled -> "Mocks are off in the application"
                    rules.isEmpty() -> "No active mocks"
                    else -> "${count(rules.size)} active in the application"
                },
            )
        } else {
            setStatus("Could not send the mocks: ${result.exceptionOrNull()?.message}")
        }
    }

    private fun count(n: Int) = if (n == 1) "1 mock" else "$n mocks"

    private fun setStatus(text: String) {
        status = text
        changed()
    }

    private fun changed() = listeners.forEach { it() }

    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /** Writes a new mock file and opens it. Call on the EDT. */
    fun create(definition: MockDefinition): VirtualFile? {
        val dir = directory() ?: return null
        val name = MockFiles.freeName(dir, definition.label.ifEmpty { "mock" })
        val file = WriteCommandAction.writeCommandAction(project).withName("Create Mock").compute<VirtualFile, RuntimeException> {
            val folder = VfsUtil.createDirectoryIfMissing(dir.toString().replace('\\', '/')) ?: error("Cannot create $dir")
            folder.createChildData(this, name).also { VfsUtil.saveText(it, MockFiles.toText(definition)) }
        }
        FileEditorManager.getInstance(project).openFile(file, true)
        return file
    }

    fun open(entry: MockEntry) {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry.file)?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    /** Turns one mock on or off by editing its file (an open editor sees the change). Call on the EDT. */
    fun setEnabled(entry: MockEntry, on: Boolean) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry.file) ?: return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val text = runCatching { MockFiles.withEnabled(document.text, on) }.getOrNull() ?: return
        WriteCommandAction.runWriteCommandAction(project, if (on) "Enable Mock" else "Disable Mock", null, {
            document.setText(text)
            FileDocumentManager.getInstance().saveDocument(document)
        })
    }

    /** Deletes the file of [entry]. Call on the EDT, after asking. */
    fun delete(entry: MockEntry) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry.file) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Delete Mock", null, { file.delete(this) })
    }

    override fun dispose() {}

    companion object {
        private const val ENABLED_KEY = "tendril.mocks.enabled"
        fun getInstance(project: Project): MockStore = project.service()
    }
}
