package dev.tendril.config

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * A field of a `@Validated` `@ConfigurationProperties` class that is marked `@NotNull`/`@NotBlank`/`@NotEmpty`
 * and has no default value, while no application config file defines its key: the application fails to start.
 * Works for Java fields and Kotlin properties (in the class body or in the primary constructor).
 * (`@Value` placeholders without a default are covered by [UnresolvedValueKeyInspection].)
 */
class MissingRequiredPropertyInspection : AbstractBaseUastLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitField(node: UField): Boolean {
                    check(node)
                    return true
                }

                private fun check(node: UField) {
                    val field = node.javaPsi as? PsiField ?: return
                    if (field.hasModifierProperty(PsiModifier.STATIC) || hasDefault(node)) return
                    if (!isRequired(field) || !isValidated(field)) return

                    val project = field.project
                    val scan = ConfigMetadata.ownScan(project)
                    val key = scan.keysByElement[field]?.firstOrNull() ?: return
                    if (scan.sources.any { it.key.startsWith("$key.") }) return // groups nested properties
                    if (TendrilSettings.getInstance(project).isIgnored(key)) return
                    if (ConfigNavigation.allConfigElements(project, key).isNotEmpty()) return

                    val anchor = node.uastAnchor?.sourcePsi ?: return
                    holder.registerProblem(
                        anchor,
                        "Required property '$key' is not defined in any application config file; the application will fail to start",
                        ProblemHighlightType.WARNING,
                        *ignoreFixes(key),
                    )
                }
            },
            arrayOf(UField::class.java),
        )

    /** An initializer, or for a Kotlin constructor property a default value of its parameter. */
    private fun hasDefault(node: UField): Boolean {
        if (node.uastInitializer != null) return true
        val cls = node.getContainingUClass() ?: return false
        return constructorParameters(cls).any { it.name == node.name && it.uastInitializer != null }
    }

    private fun constructorParameters(cls: UClass) =
        cls.methods.filter(UMethod::isConstructor).flatMap { it.uastParameters }

    /** The field is annotated, or (Kotlin) the constructor parameter that declares the property is. */
    private fun isRequired(field: PsiField): Boolean {
        if (AnnotationUtil.isAnnotated(field, REQUIRED, 0)) return true
        val cls = field.containingClass ?: return false
        return cls.constructors.any { ctor ->
            ctor.parameterList.parameters.any { p -> p.name == field.name && AnnotationUtil.isAnnotated(p as PsiModifierListOwner, REQUIRED, 0) }
        }
    }

    /** The field's class, or one of its outer classes, is `@Validated`. */
    private fun isValidated(field: PsiField): Boolean {
        var cls: PsiClass? = field.containingClass
        while (cls != null) {
            if (cls.hasAnnotation(VALIDATED)) return true
            cls = PsiTreeUtil.getParentOfType(cls, PsiClass::class.java)
        }
        return false
    }

    private companion object {
        const val VALIDATED = "org.springframework.validation.annotation.Validated"
        val REQUIRED = listOf(
            "jakarta.validation.constraints.NotNull", "jakarta.validation.constraints.NotBlank",
            "jakarta.validation.constraints.NotEmpty", "javax.validation.constraints.NotNull",
            "javax.validation.constraints.NotBlank", "javax.validation.constraints.NotEmpty",
        )
    }
}
