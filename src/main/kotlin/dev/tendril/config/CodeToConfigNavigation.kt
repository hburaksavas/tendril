package dev.tendril.config

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.uast.injectionHostUExpression
import com.intellij.psi.*
import dev.tendril.TendrilIcons
import dev.tendril.javaViewOf
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.expressions.UInjectionHost
import org.jetbrains.uast.UNamedExpression
import org.jetbrains.uast.getParentOfType

/**
 * `@Value("${jwt.secret}")`, `@FeignClient(url = "${...}")` and `@FeignClient("credit-service")`: Ctrl+Click jumps to
 * the key (or to the address of the service) in application*.yml / .properties. Registered on UAST, so it works in
 * Java and Kotlin sources alike.
 */
class ValuePlaceholderReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerUastReferenceProvider(
            injectionHostUExpression(),
            uastInjectionHostReferenceProvider { expression, host -> referencesOf(expression, host) },
            PsiReferenceRegistrar.DEFAULT_PRIORITY,
        )
    }

    private fun referencesOf(expression: UExpression, host: PsiLanguageInjectionHost): Array<PsiReference> {
        if (expression !is UInjectionHost || !expression.isString) return PsiReference.EMPTY_ARRAY
        val annotation = expression.getParentOfType<UAnnotation>() ?: return PsiReference.EMPTY_ARRAY
        val annotationName = annotation.qualifiedName
        if (annotationName !in ProjectConfigProperties.PLACEHOLDER_ANNOTATIONS) return PsiReference.EMPTY_ARRAY

        val text = host.text
        val placeholders = ProjectConfigProperties.PLACEHOLDER.findAll(text).mapNotNull { m ->
            m.groups[1]?.let { g -> ConfigKeyReference(host, TextRange(g.range.first, g.range.last + 1)) }
        }.toList()
        if (placeholders.isNotEmpty()) return placeholders.toTypedArray()

        // @FeignClient("credit-service"): Ctrl+Click jumps to the config that holds its address
        if (annotationName in ProjectConfigProperties.FEIGN_CLIENT) {
            val attribute = (expression.uastParent as? UNamedExpression)?.name ?: "value"
            if (attribute in setOf("name", "value", "serviceId") && text.length > 2) {
                return arrayOf(FeignServiceNameReference(host, TextRange(1, text.length - 1)))
            }
        }
        return PsiReference.EMPTY_ARRAY
    }
}

private class ConfigKeyReference(element: PsiElement, range: TextRange) :
    PsiPolyVariantReferenceBase<PsiElement>(element, range, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val key = rangeInElement.substring(element.text)
        return ConfigNavigation.configElements(element.project, key, element)
            .map { PsiElementResolveResult(it) }
            .toTypedArray()
    }
}

/** The service name of a `@FeignClient`: resolves to the config entries that give its address. */
private class FeignServiceNameReference(element: PsiElement, range: TextRange) :
    PsiPolyVariantReferenceBase<PsiElement>(element, range, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        dev.tendril.feign.FeignResolver.addressElements(element.project, rangeInElement.substring(element.text))
            .map { PsiElementResolveResult(it) }
            .toTypedArray()
}

/**
 * Code -> config: gutter icon on fields / parameters / classes that consume a configuration key (`@Value`,
 * `@ConfigurationProperties`, `@FeignClient`), navigating to the key in the yml / properties files. Java and Kotlin.
 */
class CodeToConfigLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun getName() = "Tendril: Java code to config key"

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        val declaration = element.parent as? PsiNameIdentifierOwner ?: return
        if (declaration.nameIdentifier != element) return
        val owner = javaViewOf(declaration) ?: return

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
