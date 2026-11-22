package dev.tendril.transactions

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

internal const val SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional"

/**
 * `@Transactional` that Spring silently ignores, because the transaction is applied by a proxy around the bean:
 *  - on a `private` or `static` method, and (Java) on a `final` method or in a `final` class, which the proxy cannot override
 *  - a call to a `@Transactional` method of the same class (`this.save()`), which does not go through the proxy
 *  - a checked exception leaving the method, which does not roll the transaction back unless `rollbackFor` says so
 * Works on UAST, so it serves Java and Kotlin. Kotlin classes and methods are final by default (the `kotlin-spring`
 * plugin opens them), so the `final` checks only apply to Java.
 */
class TransactionalPitfallsInspection : AbstractBaseUastLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitClass(node: UClass): Boolean {
                    checkClass(node)
                    return false
                }

                override fun visitMethod(node: UMethod): Boolean {
                    checkMethod(node)
                    return false
                }

                override fun visitCallExpression(node: UCallExpression): Boolean {
                    checkSelfInvocation(node)
                    return false
                }

                override fun visitThrowExpression(node: UThrowExpression): Boolean {
                    checkThrow(node)
                    return false
                }

                private fun isJava(element: PsiElement?) = element?.language == JavaLanguage.INSTANCE

                private fun checkClass(node: UClass) {
                    val cls = node.javaPsi
                    if (!isJava(node.sourcePsi) || !cls.hasModifierProperty(PsiModifier.FINAL)) return
                    val transactional = cls.getAnnotation(SPRING_TRANSACTIONAL) != null ||
                        cls.methods.any { it.getAnnotation(SPRING_TRANSACTIONAL) != null }
                    if (!transactional) return
                    val anchor = node.uastAnchor?.sourcePsi ?: return
                    holder.registerProblem(
                        anchor,
                        "Final class: Spring cannot create the proxy that applies @Transactional",
                        ProblemHighlightType.WARNING,
                    )
                }

                private fun checkMethod(node: UMethod) {
                    val method = node.javaPsi
                    val annotation = method.getAnnotation(SPRING_TRANSACTIONAL) ?: return
                    val anchor = node.uastAnchor?.sourcePsi ?: return

                    val reason = when {
                        method.hasModifierProperty(PsiModifier.PRIVATE) -> "private"
                        method.hasModifierProperty(PsiModifier.STATIC) -> "static"
                        isJava(node.sourcePsi) && method.hasModifierProperty(PsiModifier.FINAL) -> "final"
                        else -> null
                    }
                    if (reason != null) {
                        holder.registerProblem(
                            anchor,
                            "@Transactional has no effect on a $reason method: the Spring proxy cannot intercept it",
                            ProblemHighlightType.WARNING,
                        )
                    }

                    // checked exceptions declared with `throws`
                    if (isJava(node.sourcePsi) && !rollsBackOnChecked(annotation)) {
                        for (type in method.throwsList.referencedTypes) {
                            val thrown = type.resolve() ?: continue
                            if (!isChecked(thrown)) continue
                            holder.registerProblem(
                                method.throwsList.referenceElements.firstOrNull { it.referenceName == thrown.name } ?: anchor,
                                "Checked exception '${thrown.name}' does not roll back the transaction; " +
                                    "add rollbackFor = ${thrown.name}.class",
                                ProblemHighlightType.WARNING,
                                *fixFor(annotation, thrown.name ?: "Exception"),
                            )
                        }
                    }
                }

                /** `throw new IOException()` in a transactional method that does not catch it. */
                private fun checkThrow(node: UThrowExpression) {
                    if (!isJava(node.sourcePsi)) return
                    val method = node.getParentOfType<UMethod>()?.javaPsi ?: return
                    val annotation = method.getAnnotation(SPRING_TRANSACTIONAL) ?: return
                    if (rollsBackOnChecked(annotation)) return
                    if (node.getParentOfType<UTryExpression>(strict = true)?.takeIf { it.tryClause.isAncestor(node) } != null) return
                    val thrown = (node.thrownExpression.getExpressionType() as? PsiClassType)?.resolve() ?: return
                    if (!isChecked(thrown)) return
                    holder.registerProblem(
                        node.sourcePsi ?: return,
                        "Checked exception '${thrown.name}' does not roll back the transaction; " +
                            "add rollbackFor = ${thrown.name}.class",
                        ProblemHighlightType.WARNING,
                        *fixFor(annotation, thrown.name ?: "Exception"),
                    )
                }

                private fun checkSelfInvocation(node: UCallExpression) {
                    val receiver = node.receiver
                    if (receiver != null && receiver !is UThisExpression) return
                    val callee = node.resolve() ?: return
                    val callerMethod = node.getParentOfType<UMethod>()?.javaPsi ?: return
                    if (callerMethod.isConstructor) return
                    val owner = callerMethod.containingClass ?: return
                    if (callee.containingClass != owner || callee == callerMethod) return
                    if (callee.hasModifierProperty(PsiModifier.PRIVATE)) return // reported on the method itself

                    val calleeTx = callee.getAnnotation(SPRING_TRANSACTIONAL) ?: owner.getAnnotation(SPRING_TRANSACTIONAL) ?: return
                    val callerTx = callerMethod.getAnnotation(SPRING_TRANSACTIONAL) ?: owner.getAnnotation(SPRING_TRANSACTIONAL)
                    // a transactional caller already has a transaction; the callee only matters if it asks for something else
                    if (callerTx != null && propagationOf(calleeTx) !in NEW_BOUNDARY) return

                    val at = node.methodIdentifier?.sourcePsi ?: node.sourcePsi ?: return
                    val why = if (callerTx == null) "no transaction is started" else "its ${propagationOf(calleeTx)} is not applied"
                    holder.registerProblem(
                        at,
                        "Call to @Transactional '${callee.name}' from the same class bypasses the Spring proxy: $why. " +
                            "Move it to another bean or inject the bean itself",
                        ProblemHighlightType.WARNING,
                    )
                }
            },
            arrayOf(UClass::class.java, UMethod::class.java, UCallExpression::class.java, UThrowExpression::class.java),
        )

    private fun UElement?.isAncestor(node: UElement): Boolean {
        var current: UElement? = node
        while (current != null) {
            if (current == this) return true
            current = current.uastParent
        }
        return false
    }

    private fun isChecked(cls: PsiClass) =
        InheritanceUtil.isInheritor(cls, "java.lang.Throwable") &&
            !InheritanceUtil.isInheritor(cls, "java.lang.RuntimeException") &&
            !InheritanceUtil.isInheritor(cls, "java.lang.Error")

    private fun fixFor(annotation: PsiAnnotation, exception: String): Array<LocalQuickFix> =
        if (annotation.language == JavaLanguage.INSTANCE) arrayOf(AddRollbackForFix(exception)) else emptyArray()

    companion object {
        /** Propagation modes whose point is to start or suspend a transaction at the call. */
        val NEW_BOUNDARY = setOf("REQUIRES_NEW", "NOT_SUPPORTED", "NEVER", "NESTED")

        fun propagationOf(annotation: PsiAnnotation): String =
            annotation.findAttributeValue("propagation")?.text?.substringAfterLast('.')?.trim() ?: "REQUIRED"

        /** `rollbackFor` / `rollbackForClassName` is set, so the author has thought about which exceptions roll back. */
        fun rollsBackOnChecked(annotation: PsiAnnotation): Boolean =
            annotation.findDeclaredAttributeValue("rollbackFor") != null || annotation.findDeclaredAttributeValue("rollbackForClassName") != null
    }
}

/** `@Transactional` -> `@Transactional(rollbackFor = X.class)`. Runs in the framework's write action, so it also gets a preview. */
private class AddRollbackForFix(private val exception: String) : LocalQuickFix {

    override fun getFamilyName() = "Tendril: transaction rollback"
    override fun getName() = "Add rollbackFor = $exception.class"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val method = com.intellij.psi.util.PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiMethod::class.java) ?: return
        val annotation = method.getAnnotation(SPRING_TRANSACTIONAL) ?: return
        val value = JavaPsiFacade.getElementFactory(project).createExpressionFromText("$exception.class", annotation)
        annotation.setDeclaredAttributeValue("rollbackFor", value as PsiAnnotationMemberValue)
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(annotation)
    }
}
