package dev.tendril.config

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiClass

/**
 * A field of a `@Validated` `@ConfigurationProperties` class that is marked `@NotNull`/`@NotBlank`/`@NotEmpty`
 * and has no initializer, while no application config file defines its key: the application fails to start.
 * (`@Value` placeholders without a default are covered by [UnresolvedValueKeyInspection].)
 */
class MissingRequiredPropertyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitField(field: PsiField) {
                if (field.initializer != null || field.hasModifierProperty(PsiModifier.STATIC)) return
                if (!AnnotationUtil.isAnnotated(field, REQUIRED, 0)) return
                if (!isValidated(field)) return

                val project = field.project
                val scan = ConfigMetadata.ownScan(project)
                val key = scan.keysByElement[field]?.firstOrNull() ?: return
                if (scan.sources.any { it.key.startsWith("$key.") }) return // groups nested properties
                if (TendrilSettings.getInstance(project).isIgnored(key)) return
                if (ConfigNavigation.allConfigElements(project, key).isNotEmpty()) return

                val name = field.nameIdentifier
                holder.registerProblem(
                    name,
                    "Required property '$key' is not defined in any application config file; the application will fail to start",
                    ProblemHighlightType.WARNING,
                    *ignoreFixes(key),
                )
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
