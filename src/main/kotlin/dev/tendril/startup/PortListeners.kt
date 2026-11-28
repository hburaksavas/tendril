package dev.tendril.startup

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File

/** A run of this IDE that can be stopped. */
class RunningApp(val name: String, val stop: () -> Unit)

/** What listens on a port: "java.exe (PID 1234)", and the IDE run it belongs to, if any. */
class PortListener(val description: String, val runningIn: RunningApp?)

/**
 * Finds the process listening on a TCP port with the system's own tools (`netstat` on Windows, `lsof` elsewhere).
 * Runs external commands: call off the EDT and outside read actions.
 */
object PortListeners {
    private val log = logger<PortListeners>()

    fun find(project: Project, port: Int): PortListener? {
        val pid = listenerPid(port) ?: return null
        val command = ProcessHandle.of(pid).flatMap { it.info().command() }.map { File(it).name }.orElse(null)
        val description = if (command != null) "$command (PID $pid)" else "PID $pid"
        return PortListener(description, runningApp(project, pid))
    }

    private fun listenerPid(port: Int): Long? = try {
        if (SystemInfo.isWindows) parseNetstat(run("netstat", "-ano", "-p", "TCP") + run("netstat", "-ano", "-p", "TCPv6"), port)
        else run("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN", "-t").lineSequence().firstNotNullOfOrNull { it.trim().toLongOrNull() }
    } catch (e: Exception) {
        log.info("Could not find the process listening on $port", e)
        null
    }

    /**
     * The PID in a `netstat -ano` row whose local address ends in `:port` and whose remote address is a wildcard
     * (a listening socket). The state column is not read: Windows translates it.
     */
    internal fun parseNetstat(output: String, port: Int): Long? {
        val row = Regex("^\\s*TCP\\s+\\S+:(\\d+)\\s+(?:0\\.0\\.0\\.0:0|\\[::]:0|\\*:\\*)\\s+\\S+\\s+(\\d+)\\s*$", RegexOption.IGNORE_CASE)
        return output.lineSequence().mapNotNull { row.find(it) }
            .firstOrNull { it.groupValues[1].toInt() == port }?.groupValues?.get(2)?.toLongOrNull()
    }

    private fun run(vararg command: String): String =
        CapturingProcessHandler(GeneralCommandLine(*command)).runProcess(5_000).stdout

    /** The IDE run whose process is [pid] or one of its ancestors (`mvn spring-boot:run` forks the application). */
    private fun runningApp(project: Project, pid: Long): RunningApp? {
        val ancestors = generateSequence(ProcessHandle.of(pid).orElse(null)) { it.parent().orElse(null) }
            .take(6).map { it.pid() }.toSet()
        for (descriptor in RunContentManager.getInstance(project).allDescriptors) {
            val handler = descriptor.processHandler ?: continue
            if (handler.isProcessTerminated || handler.isProcessTerminating) continue
            val handlerPid = (handler as? BaseProcessHandler<*>)?.process?.let { runCatching { it.pid() }.getOrNull() } ?: continue
            if (handlerPid in ancestors) return RunningApp(descriptor.displayName) { handler.destroyProcess() }
        }
        return null
    }
}
