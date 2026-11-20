package dev.tendril.recordings.maven

import com.intellij.execution.RunManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import dev.tendril.recordings.RecorderAgent
import org.jetbrains.idea.maven.execution.MavenRunConfiguration
import org.jetbrains.idea.maven.execution.MavenRunner

/**
 * Adds the recorder to a Maven run configuration that starts the application with `spring-boot:run`, or removes it
 * again. The agent goes into the `spring-boot.run.jvmArguments` Maven property, which the plugin hands to the
 * forked application JVM.
 */
class AddRecorderToMavenRunAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val configurations = RunManager.getInstance(project).allSettings
            .filter { it.configuration is MavenRunConfiguration }
            .sortedByDescending { isSpringBootRun(it.configuration as MavenRunConfiguration) }
        if (configurations.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "There is no Maven run configuration yet. Create one with the goal spring-boot:run, then add the recorder to it.",
                "Recorder",
            )
            return
        }
        val labels = configurations.map { s ->
            val c = s.configuration as MavenRunConfiguration
            val state = if (hasRecorder(project, c)) "recorder on" else if (isSpringBootRun(c)) "spring-boot:run" else "other goals"
            "${s.name}   ($state)"
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(labels)
            .setTitle("Toggle the recorder on a Maven run configuration")
            .setItemChosenCallback { chosen ->
                val index = labels.indexOf(chosen)
                if (index >= 0) toggle(project, configurations[index].name, configurations[index].configuration as MavenRunConfiguration)
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun isSpringBootRun(c: MavenRunConfiguration) = c.runnerParameters.goals.any { it.contains("spring-boot:run") }

    private fun settings(project: Project, c: MavenRunConfiguration) =
        c.runnerSettings ?: MavenRunner.getInstance(project).settings.clone().also { c.runnerSettings = it }

    private fun hasRecorder(project: Project, c: MavenRunConfiguration): Boolean =
        RecorderAgent.ARGUMENT_PATTERN.containsMatchIn(c.runnerSettings?.mavenProperties?.get(PROPERTY).orEmpty())

    private fun toggle(project: Project, name: String, c: MavenRunConfiguration) {
        val settings = settings(project, c)
        val current = settings.mavenProperties[PROPERTY].orEmpty()
        val without = current.replace(RecorderAgent.ARGUMENT_PATTERN, "").trim().replace(Regex("\\s{2,}"), " ")
        val message: String
        if (RecorderAgent.ARGUMENT_PATTERN.containsMatchIn(current)) {
            if (without.isEmpty()) settings.mavenProperties.remove(PROPERTY) else settings.mavenProperties[PROPERTY] = without
            message = "Recorder removed from '$name'."
        } else {
            val argument = runCatching { RecorderAgent.argument(project) }.getOrElse {
                Messages.showErrorDialog(project, it.message ?: "The recorder could not be prepared", "Recorder")
                return
            }
            settings.mavenProperties[PROPERTY] = listOf(without, argument).filter { it.isNotEmpty() }.joinToString(" ")
            message = if (isSpringBootRun(c)) "Recorder added to '$name'. Run it again to start recording."
            else "Recorder added to '$name', but its goals do not include spring-boot:run, so it only applies to that goal."
        }
        NotificationGroupManager.getInstance().getNotificationGroup("Tendril")
            ?.createNotification(message, NotificationType.INFORMATION)?.notify(project)
    }

    private companion object {
        const val PROPERTY = "spring-boot.run.jvmArguments"
    }
}
