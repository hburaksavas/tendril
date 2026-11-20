package dev.tendril.config

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import dev.tendril.TendrilIcons

/** `@Value("${jwt.secret}")`: Ctrl+Click on the key part jumps to the key in application*.yml / .properties. */
class ValuePlaceholderReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiLiteralExpression::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY
                    if (literal.value !is String) return PsiReference.EMPTY_ARRAY
                    val annotation = PsiTreeUtil.getParentOfType(literal, PsiAnnotation::class.java)
                        ?: return PsiReference.EMPTY_ARRAY
                    if (annotation.qualifiedName != ProjectConfigProperties.VALUE) return PsiReference.EMPTY_ARRAY

                    return ProjectConfigProperties.PLACEHOLDER.findAll(literal.text).mapNotNull { m ->
                        m.groups[1]?.let { g -> ConfigKeyReference(literal, TextRange(g.range.first, g.range.last + 1)) }
                    }.toList().toTypedArray()
                }
            },
        )
    }
}

private class ConfigKeyReference(element: PsiLiteralExpression, range: TextRange) :
    PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val key = rangeInElement.substring(element.text)
        return ConfigNavigation.configElements(element.project, key, element)
            .map { PsiElementResolveResult(it) }
            .toTypedArray()
    }
}

/**
 * Java code -> config: gutter icon on fields / parameters / classes that consume a configuration key
 * (`@Value` and `@ConfigurationProperties`), navigating to the key in the yml / properties files.
 */
class CodeToConfigLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun getName() = "Tendril: Java code to config key"

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        if (element !is PsiIdentifier) return
        val owner = element.parent as? PsiNameIdentifierOwner ?: return
        if (owner.nameIdentifier != element) return

        val project = element.project
        val keys = ConfigMetadata.ownScan(project).keysByElement[owner] ?: return
        val targets = keys.flatMap { ConfigNavigation.configElements(project, it, element) }.distinct()
        if (targets.isEmpty()) return

        result.add(
            NavigationGutterIconBuilder.create(TendrilIcons.CodeToConfig)
                .setTargets(targets)
                .setCellRenderer { ConfigTargetRenderer() }
                .setTooltipText("Navigate to configuration property")
                .setPopupTitle("Configuration for ${keys.joinToString()}")
                .createLineMarkerInfo(element),
        )
    }
}
