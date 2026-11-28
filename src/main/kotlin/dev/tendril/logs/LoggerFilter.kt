package dev.tendril.logs

import com.intellij.execution.filters.ConsoleFilterProviderEx
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfoBase
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.awt.RelativePoint
import dev.tendril.actuator.ActuatorLoggers
import dev.tendril.startup.StartupChoice
import dev.tendril.startup.showChoices

/** Adds [LoggerFilter] to the Run and Debug consoles. */
class LoggerFilterProvider : ConsoleFilterProviderEx {
    override fun getDefaultFilters(project: Project, scope: GlobalSearchScope): Array<Filter> =
        arrayOf(LoggerFilter(project, scope))

    override fun getDefaultFilters(project: Project): Array<Filter> =
        getDefaultFilters(project, GlobalSearchScope.allScope(project))
}

/**
 * Links the logger column of Spring Boot's console lines. It is drawn as an inactive link (underlined on hover), so
 * a log full of links stays readable; the class is looked up only on click.
 */
class LoggerFilter(private val project: Project, private val scope: GlobalSearchScope) : Filter, DumbAware {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val range = LoggerNames.find(line) ?: return null
        val offset = entireLength - line.length
        val scheme = EditorColorsManager.getInstance().globalScheme
        return Filter.Result(listOf(Filter.ResultItem(
            offset + range.first, offset + range.last + 1, LoggerHyperlink(scope, line.substring(range)),
            scheme.getAttributes(CodeInsightColors.INACTIVE_HYPERLINK_ATTRIBUTES),
            scheme.getAttributes(CodeInsightColors.FOLLOWED_HYPERLINK_ATTRIBUTES),
            scheme.getAttributes(CodeInsightColors.HYPERLINK_ATTRIBUTES),
        )))
    }
}

private class LoggerHyperlink(private val scope: GlobalSearchScope, private val logger: String) : HyperlinkInfoBase() {

    override fun navigate(project: Project, hyperlinkLocationPoint: RelativePoint?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val choices = LoggerChoices(project, scope)
            val (resolved, actuator) = ReadAction.nonBlocking<Pair<ResolvedLogger, String>> {
                choices.resolve(logger) to ActuatorLoggers.address(project)
            }.inSmartMode(project).expireWith(project).executeSynchronously()
            // outside the read action: a network call to the application, short timeouts
            val level = resolved.fullName?.let { ActuatorLoggers.level(actuator, it) }
            val list = ReadAction.nonBlocking<List<StartupChoice>> { choices.of(resolved, actuator.takeIf { level != null }, level) }
                .inSmartMode(project).expireWith(project).executeSynchronously()
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (list.isEmpty()) {
                    NotificationGroupManager.getInstance().getNotificationGroup("Tendril")
                        .createNotification("No class in the project matches the logger $logger", NotificationType.INFORMATION)
                        .notify(project)
                } else {
                    showChoices(project, logger, list, hyperlinkLocationPoint)
                }
            }
        }
    }
}
