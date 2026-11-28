package dev.tendril.startup

import com.intellij.execution.filters.ConsoleFilterProviderEx
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfoBase
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.concurrency.AppExecutorUtil

/** Adds [StartupFailureFilter] to the Run and Debug consoles. */
class StartupFailureFilterProvider : ConsoleFilterProviderEx {
    override fun getDefaultFilters(project: Project, scope: GlobalSearchScope): Array<Filter> =
        arrayOf(StartupFailureFilter(project, scope))

    override fun getDefaultFilters(project: Project): Array<Filter> =
        getDefaultFilters(project, GlobalSearchScope.allScope(project))
}

/**
 * Links the parts of Spring Boot's "APPLICATION FAILED TO START" report to the bean, config key, file position or
 * port they are about. Matching is plain text; the project is only consulted when a link is clicked.
 */
class StartupFailureFilter(private val project: Project, private val scope: GlobalSearchScope) : Filter, DumbAware {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val links = StartupReport.links(line)
        if (links.isEmpty()) return null
        val offset = entireLength - line.length
        return Filter.Result(links.map { Filter.ResultItem(offset + it.start, offset + it.end, StartupHyperlink(scope, it.target)) })
    }
}

private class StartupHyperlink(private val scope: GlobalSearchScope, private val target: StartupTarget) : HyperlinkInfoBase() {

    override fun navigate(project: Project, hyperlinkLocationPoint: RelativePoint?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val listener = (target as? StartupTarget.Port)?.let { PortListeners.find(project, it.port) }
            ReadAction.nonBlocking<List<StartupChoice>> { StartupChoices(project, scope).of(target, listener) }
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState()) { show(project, it, hyperlinkLocationPoint) }
                .submit(AppExecutorUtil.getAppExecutorService())
        }
    }

    private fun show(project: Project, choices: List<StartupChoice>, at: RelativePoint?) {
        if (choices.isEmpty()) {
            NotificationGroupManager.getInstance().getNotificationGroup("Tendril")
                .createNotification("${describe(target)} is not in the project", NotificationType.INFORMATION)
                .notify(project)
            return
        }
        showChoices(project, describe(target), choices, at)
    }

    private fun describe(target: StartupTarget): String = when (target) {
        is StartupTarget.Type -> target.fqn.substringAfterLast('.')
        is StartupTarget.InjectionPoint -> target.fqn.substringAfterLast('.')
        is StartupTarget.BeanDefinition -> "Bean '${target.name}'"
        is StartupTarget.ConfigKey -> target.key
        is StartupTarget.Origin -> target.resource.substringAfterLast('/')
        is StartupTarget.Port -> "Port ${target.port}"
    }
}

/** Performs a lone navigation at once; otherwise lists [choices] (with submenus) at [at]. Call on the EDT. */
internal fun showChoices(project: Project, title: String, choices: List<StartupChoice>, at: RelativePoint?) {
    val single = choices.singleOrNull()
    if (single != null && !single.fix && single.children.isEmpty()) return single.perform(project)
    val popup = JBPopupFactory.getInstance().createListPopup(step(project, title, choices))
    if (at != null) popup.show(at) else popup.showCenteredInCurrentWindow(project)
}

private fun step(project: Project, title: String?, choices: List<StartupChoice>): BaseListPopupStep<StartupChoice> =
    object : BaseListPopupStep<StartupChoice>(title, choices) {
        override fun getTextFor(value: StartupChoice) = value.text
        override fun getIconFor(value: StartupChoice) = value.icon
        override fun hasSubstep(selectedValue: StartupChoice) = selectedValue.children.isNotEmpty()
        override fun onChosen(selectedValue: StartupChoice, finalChoice: Boolean): PopupStep<*>? =
            if (selectedValue.children.isNotEmpty()) step(project, null, selectedValue.children)
            else doFinalStep { selectedValue.perform(project) }
    }
