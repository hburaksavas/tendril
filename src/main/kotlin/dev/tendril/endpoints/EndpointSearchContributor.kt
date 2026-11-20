package dev.tendril.endpoints

import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.ide.actions.searcheverywhere.WeightedSearchEverywhereContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.Processor
import javax.swing.JList
import javax.swing.ListCellRenderer

/** "Go to Endpoint": adds an Endpoints tab to Search Everywhere (double Shift). */
class EndpointSearchContributorFactory : SearchEverywhereContributorFactory<Endpoint> {
    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<Endpoint> =
        EndpointSearchContributor(initEvent.getRequiredData(CommonDataKeys.PROJECT))

    override fun isAvailable(project: Project): Boolean = true
}

private class EndpointSearchContributor(private val project: Project) :
    WeightedSearchEverywhereContributor<Endpoint> {

    override fun getSearchProviderId() = "TendrilEndpoints"
    override fun getGroupName() = "Endpoints"
    override fun getSortWeight() = 350
    override fun showInFindResults() = false
    override fun isShownInSeparateTab() = true

    override fun fetchWeightedElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in FoundItemDescriptor<Endpoint>>,
    ) {
        val endpoints = ReadAction.compute<List<Endpoint>, RuntimeException> {
            if (DumbService.isDumb(project)) emptyList() else EndpointScanner.cached(project)
        }
        val tokens = pattern.lowercase().split(' ', '\t').filter { it.isNotEmpty() }
        for (e in endpoints) {
            progressIndicator.checkCanceled()
            val haystack = "${e.httpMethod} ${e.path} ${e.className}.${e.methodName}".lowercase()
            if (tokens.all { it in haystack }) {
                // shorter paths first, then alphabetical via the platform's secondary ordering
                if (!consumer.process(FoundItemDescriptor(e, 1000 - e.path.length))) return
            }
        }
    }

    override fun getElementsRenderer(): ListCellRenderer<in Endpoint> =
        object : ColoredListCellRenderer<Endpoint>() {
            override fun customizeCellRenderer(
                list: JList<out Endpoint>, value: Endpoint?, index: Int, selected: Boolean, hasFocus: Boolean,
            ) {
                value ?: return
                icon = AllIcons.Nodes.Method
                append(value.httpMethod.padEnd(7), HttpMethodColors.attributes(value.httpMethod))
                append(value.path)
                append("   ${value.className}.${value.methodName}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

    override fun getDataForItem(element: Endpoint, dataId: String): Any? = null

    override fun processSelectedItem(selected: Endpoint, modifiers: Int, searchText: String): Boolean {
        val method = ReadAction.compute<Navigatable?, RuntimeException> { selected.method.element as? Navigatable }
        method?.takeIf { it.canNavigate() }?.navigate(true)
        return true
    }
}
