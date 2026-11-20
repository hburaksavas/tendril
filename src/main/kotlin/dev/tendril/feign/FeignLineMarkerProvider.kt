package dev.tendril.feign

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import dev.tendril.endpoints.EndpointRequests

/** On a mapped method of a `@FeignClient` interface: opens the call as an `.http` request for the chosen profile. */
class FeignLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName() = "Tendril: Feign client request"

    override fun getIcon() = AllIcons.Actions.Execute

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null
        val method = element.parent as? PsiMethod ?: return null
        val cls = method.containingClass?.takeIf { it.isInterface } ?: return null
        val def = FeignScanner.defOf(cls) ?: return null
        if (def.calls.none { it.method.element == method }) return null

        val handler = GutterIconNavigationHandler<PsiElement> { _, e ->
            val project = e.project
            val m = e.parent as? PsiMethod ?: return@GutterIconNavigationHandler
            val owner = m.containingClass ?: return@GutterIconNavigationHandler
            val d = FeignScanner.defOf(owner) ?: return@GutterIconNavigationHandler
            val info = FeignResolver.resolve(project, d, FeignResolver.selectedProfile(project))
            val call = d.calls.firstOrNull { it.method.element == m } ?: return@GutterIconNavigationHandler
            dev.tendril.http.HttpToolWindowFactory.open(project, FeignRequests.http(info, call))
        }
        return LineMarkerInfo(
            element, element.textRange, AllIcons.Actions.Execute,
            { "Send this Feign call (Tendril HTTP)" }, handler, GutterIconRenderer.Alignment.LEFT,
            { "Send Feign call in Tendril HTTP" },
        )
    }
}
