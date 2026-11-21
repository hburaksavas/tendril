package dev.tendril.beans

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import dev.tendril.TendrilIcons
import dev.tendril.javaViewOf

/** Bean definition (stereotype class or @Bean method) -> the fields and parameters it is injected into. */
class BeanUsagesLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun getName() = "Tendril: bean injected into"

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        val declaration = element.parent as? PsiNameIdentifierOwner ?: return
        if (declaration.nameIdentifier != element) return
        val owner = javaViewOf(declaration)?.takeIf { it is PsiClass || it is PsiMethod } ?: return

        val points = BeanModels.get(element.project).injectedInto[owner] ?: return
        if (points.isEmpty()) return

        val names = points.map { "${it.owner.type.name}.${it.name}" }
        val shown = names.take(MAX_NAMES).joinToString("<br>")
        val more = if (names.size > MAX_NAMES) "<br>(+${names.size - MAX_NAMES} more)" else ""

        result.add(
            NavigationGutterIconBuilder.create(TendrilIcons.BeanUsages)
                .setTargets(points.map { it.element })
                .setTooltipText("<html>Injected into<br>$shown$more</html>")
                .setPopupTitle("Injected into")
                .createLineMarkerInfo(element),
        )
    }

    private companion object {
        const val MAX_NAMES = 5
    }
}
