package dev.tendril.logs

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.ClassUtil
import dev.tendril.actuator.ActuatorLoggers
import dev.tendril.startup.ConfigWriter
import dev.tendril.startup.StartupChoice
import dev.tendril.startup.StartupChoices

/** The classes a (possibly shortened) logger name stands for, and the full name when it is known. */
class ResolvedLogger(val logger: String, val classes: List<PsiClass>, val fullName: String?)

/** What a click on a logger name offers: the class, and its level in the running application or in the config files. */
class LoggerChoices(private val project: Project, scope: GlobalSearchScope) {

    private val config = StartupChoices(project, scope)

    /** Call in a read action in smart mode. */
    fun resolve(logger: String): ResolvedLogger {
        val classes = PsiShortNamesCache.getInstance(project)
            .getClassesByName(LoggerNames.simpleName(logger), GlobalSearchScope.allScope(project))
            .filter { c -> c.qualifiedName?.let { LoggerNames.matches(logger, it) } == true }
            .distinctBy { it.qualifiedName }
            .sortedWith(compareBy({ !config.inSource(it) }, { it.qualifiedName }))
            .take(MAX_CLASSES)
        val fullName = if (LoggerNames.isComplete(logger)) logger.substringBefore("$$")
        else classes.mapNotNull { ClassUtil.getJVMClassName(it) }.distinct().singleOrNull()
        return ResolvedLogger(logger, classes, fullName)
    }

    /**
     * Call in a read action. [actuator] is the running application's Actuator address and [level] the logger's
     * level there; both null when the application does not answer.
     */
    fun of(resolved: ResolvedLogger, actuator: String?, level: String?): List<StartupChoice> {
        val choices = mutableListOf<StartupChoice>()
        val qualify = resolved.classes.size > 1
        for (cls in resolved.classes) {
            choices += config.goTo("Go to ${if (qualify) cls.qualifiedName else cls.name}", cls)
        }
        val name = resolved.fullName ?: return choices
        val short = name.substringAfterLast('.')

        if (actuator != null && level != null) {
            val levels = ActuatorLoggers.LEVELS.map { l ->
                StartupChoice(l, null, fix = true) { setRunning(it, actuator, name, l) }
            } + StartupChoice("Reset (inherit from parent)", null, fix = true) { setRunning(it, actuator, name, null) }
            choices += StartupChoice("Level of $short in the running application ($level)", AllIcons.Actions.Execute, fix = true, children = levels)
        }

        val key = "logging.level.$name"
        val entries = config.configEntries(key)
        if (entries.isNotEmpty()) {
            for (entry in entries.take(MAX_CLASSES)) {
                val pointer = com.intellij.psi.SmartPointerManager.createPointer(entry)
                choices += StartupChoice("Change $key in ${config.where(entry)}", AllIcons.Actions.IntentionBulb, fix = true,
                    children = ActuatorLoggers.LEVELS.map { l ->
                        StartupChoice(l, null, fix = true) { p -> pointer.element?.let { ConfigWriter.set(p, it, l) } }
                    })
            }
        } else {
            for (file in config.mainConfigFiles()) {
                choices += StartupChoice("Add $key to ${config.label(file)}", AllIcons.Actions.IntentionBulb, fix = true,
                    children = ActuatorLoggers.LEVELS.map { l ->
                        // `logging.level.app.OrderService: DEBUG` under `logging: level:`, not one yml level per package
                        StartupChoice(l, null, fix = true) { ConfigWriter.add(it, file, key, l, listOf("logging", "level", name)) }
                    })
            }
        }
        return choices
    }

    private fun setRunning(project: Project, actuator: String, logger: String, level: String?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = ActuatorLoggers.setLevel(actuator, logger, level)
            val short = logger.substringAfterLast('.')
            val (text, type) = result.fold(
                { (if (level == null) "$short inherits its level again" else "$short logs $level now") to NotificationType.INFORMATION },
                { "Could not change the level of $logger: ${it.message}. The loggers endpoint must be exposed and writable." to NotificationType.WARNING },
            )
            NotificationGroupManager.getInstance().getNotificationGroup("Tendril").createNotification(text, type).notify(project)
        }
    }

    private companion object {
        const val MAX_CLASSES = 5
    }
}
