package dev.tendril.config

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.*
import dev.tendril.TendrilIcons

/** yml / properties key -> the Java code that consumes it: gutter icon on the key. */
class ConfigKeyLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun getName() = "Tendril: config key to Java code"

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        val key = ConfigNavigation.keyOf(element) ?: return
        val targets = ConfigNavigation.codeTargets(element.project, key, element)
        if (targets.isEmpty()) return

        result.add(
            NavigationGutterIconBuilder.create(TendrilIcons.ConfigToCode)
                .setTargets(targets)
                .setTooltipText("<html><b>$key</b> is used in<br>${describe(targets)}</html>")
                .setPopupTitle("Code using '$key'")
                .createLineMarkerInfo(element),
        )
    }

    /** "ClassA.field, ClassB.other (+2 more)" for the tooltip. */
    private fun describe(targets: List<PsiElement>): String {
        val names = targets.map(::nameOf)
        val shown = names.take(MAX_NAMES).joinToString("<br>") { it.replace("<", "&lt;") }
        return if (names.size > MAX_NAMES) "$shown<br>(+${names.size - MAX_NAMES} more)" else shown
    }

    private fun nameOf(e: PsiElement): String = when (e) {
        is PsiClass -> e.name ?: "?"
        is PsiParameter -> "${(e.declarationScope as? PsiMethod)?.containingClass?.name ?: "?"}.${e.name}"
        is PsiMember -> "${e.containingClass?.name ?: "?"}.${e.name}"
        else -> e.text.take(40)
    }

    private companion object {
        const val MAX_NAMES = 5
    }
}

/** Ctrl+Click on a yml / properties key jumps to the Java code that consumes it. */
class ConfigKeyGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val key = ConfigNavigation.keyOf(element) ?: return null
        return ConfigNavigation.codeTargets(element.project, key, element).toTypedArray().takeIf { it.isNotEmpty() }
    }
}
