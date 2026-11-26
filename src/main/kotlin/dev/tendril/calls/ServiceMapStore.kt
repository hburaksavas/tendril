package dev.tendril.calls

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import com.intellij.psi.util.PsiModificationTracker
import dev.tendril.CodeStructureTracker
import dev.tendril.endpoints.EndpointScanner
import dev.tendril.feign.FeignScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Snapshots on disk, one per project, under `~/.tendril/service-map/`: what another IDE window reads about this
 * project while it is closed. Shared by every IDE on the machine.
 */
object ServiceMapStore {
    private val gson = GsonBuilder().create()
    private val log = logger<ServiceMapStore>()

    var directory: Path = Path.of(System.getProperty("user.home"), ".tendril", "service-map")

    /** Forward slashes, no trailing slash, lower case on case-insensitive file systems. */
    fun normalise(path: String): String {
        val p = path.replace('\\', '/').trimEnd('/')
        return if (SystemInfo.isFileSystemCaseSensitive) p else p.lowercase()
    }

    private fun fileOf(projectPath: String): Path {
        val digest = MessageDigest.getInstance("SHA-1").digest(normalise(projectPath).toByteArray())
        return directory.resolve(digest.take(10).joinToString("") { "%02x".format(it) } + ".json")
    }

    fun write(snapshot: ProjectSnapshot) {
        runCatching {
            Files.createDirectories(directory)
            val target = fileOf(snapshot.projectPath)
            val temp = Files.createTempFile(directory, "snapshot", ".tmp")
            Files.writeString(temp, gson.toJson(snapshot))
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { log.info("Could not save the service map of ${snapshot.projectPath}", it) }
    }

    /** The saved snapshot of the project at [projectPath], or null when there is none or it is from another version. */
    fun read(projectPath: String): ProjectSnapshot? {
        val file = fileOf(projectPath)
        if (!Files.isRegularFile(file)) return null
        return runCatching { gson.fromJson(Files.readString(file), ProjectSnapshot::class.java) }.getOrNull()
            ?.takeIf { it.version == ProjectSnapshot.VERSION && it.apps != null && it.endpoints != null && it.feign != null }
    }
}

/**
 * Keeps the saved snapshot of an open project current, in the background: after a change to declarations within
 * half a minute, after edits inside method bodies (which can add or remove a Feign call) at most every ten
 * minutes. Projects without endpoints or Feign clients save nothing and read no method bodies. The work runs in a
 * read action that gives way to typing.
 */
@Service(Service.Level.PROJECT)
class ServiceMapExporter(private val project: Project, private val cs: CoroutineScope) {
    private var savedCode = -1L
    private var savedStructure = -1L
    private var savedAt = 0L

    fun start() {
        cs.launch {
            delay(FIRST_DELAY)
            while (true) {
                runCatching { exportIfChanged() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                delay(PERIOD)
            }
        }
    }

    private suspend fun exportIfChanged() {
        if (project.isDisposed || project.basePath == null) return
        val structure = CodeStructureTracker.getInstance(project).modificationCount
        val code = PsiModificationTracker.getInstance(project).modificationCount
        if (code == savedCode && structure == savedStructure) return
        val now = System.currentTimeMillis()
        if (structure == savedStructure && now - savedAt < BODY_PERIOD) return
        val snapshot = smartReadAction(project) {
            val spring = EndpointScanner.cached(project).isNotEmpty() || FeignScanner.cached(project).isNotEmpty()
            if (spring) ServiceSnapshots.live(project) else null
        }
        if (snapshot != null) withContext(Dispatchers.IO) { ServiceMapStore.write(snapshot) }
        savedCode = code
        savedStructure = structure
        savedAt = now
    }

    companion object {
        private const val FIRST_DELAY = 20_000L
        private const val PERIOD = 30_000L
        private const val BODY_PERIOD = 10 * 60_000L

        fun getInstance(project: Project): ServiceMapExporter = project.service()
    }
}

class ServiceMapStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ServiceMapExporter.getInstance(project).start()
    }
}
