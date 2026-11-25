package dev.tendril.feign

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import dev.tendril.javaViewOf
import dev.tendril.readInBackground
import com.intellij.psi.PsiMethod

/** On a mapped method of a `@FeignClient` interface: opens the call as an `.http` request for the chosen profile. */
class FeignLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName() = "Tendril: Feign client request"

    override fun getIcon() = AllIcons.Actions.Execute

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val declaration = element.parent as? PsiNameIdentifierOwner ?: return null
        if (declaration.nameIdentifier != element) return null
        val method = javaViewOf(declaration) as? PsiMethod ?: return null
        val cls = method.containingClass?.takeIf { it.isInterface } ?: return null
        // This pass runs for every method name of every interface (repositories too): check the annotations only, and
        // leave building the whole client to the click.
        if (!FeignScanner.isClient(cls) || dev.tendril.endpoints.EndpointScanner.mappingsOf(method).isEmpty()) return null

        val handler = GutterIconNavigationHandler<PsiElement> { _, e ->
            val project = e.project
            // resolving the address reads config across modules: off the EDT, the request opens when it is ready
            readInBackground(project, project, {
                val m = (e.parent as? PsiNameIdentifierOwner)?.let { javaViewOf(it) } as? PsiMethod ?: return@readInBackground null
                val owner = m.containingClass ?: return@readInBackground null
                val d = FeignScanner.defOf(owner) ?: return@readInBackground null
                val info = FeignResolver.resolve(project, d, FeignResolver.selectedProfile(project))
                val call = d.calls.firstOrNull { it.method.element == m } ?: return@readInBackground null
                FeignRequests.http(info, call)
            }) { text ->
                if (text != null) dev.tendril.endpoints.EndpointsToolWindowFactory.sendRequest(project, text, outgoing = true)
            }
        }
        return LineMarkerInfo(
            element, element.textRange, AllIcons.Actions.Execute,
            { "Send this Feign call" }, handler, GutterIconRenderer.Alignment.LEFT,
            { "Send this Feign call" },
        )
    }
}
