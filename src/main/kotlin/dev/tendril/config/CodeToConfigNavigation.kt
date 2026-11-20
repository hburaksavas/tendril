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
                    if (annotation.qualifiedName !in ProjectConfigProperties.PLACEHOLDER_ANNOTATIONS) {
                        return PsiReference.EMPTY_ARRAY
                    }

                    val placeholders = ProjectConfigProperties.PLACEHOLDER.findAll(literal.text).mapNotNull { m ->
                        m.groups[1]?.let { g -> ConfigKeyReference(literal, TextRange(g.range.first, g.range.last + 1)) }
                    }.toList()
                    if (placeholders.isNotEmpty()) return placeholders.toTypedArray()

                    // @FeignClient("credit-service"): Ctrl+Click jumps to the config that holds its address
                    if (annotation.qualifiedName in ProjectConfigProperties.FEIGN_CLIENT) {
                        val attribute = PsiTreeUtil.getParentOfType(literal, PsiNameValuePair::class.java)?.name ?: "value"
                        val text = literal.text
                        if (attribute in setOf("name", "value", "serviceId") && text.length > 2) {
                            return arrayOf(FeignServiceNameReference(literal, TextRange(1, text.length - 1)))
                        }
                    }
                    return PsiReference.EMPTY_ARRAY
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

/** The service name of a `@FeignClient`: resolves to the config entries that give its address. */
private class FeignServiceNameReference(element: PsiLiteralExpression, range: TextRange) :
    PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        dev.tendril.feign.FeignResolver.addressElements(element.project, rangeInElement.substring(element.text))
            .map { PsiElementResolveResult(it) }
            .toTypedArray()
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
