package dev.tendril.config

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

private fun valueAnnotationOf(literal: PsiLiteralExpression): PsiAnnotation? =
    PsiTreeUtil.getParentOfType(literal, PsiAnnotation::class.java)
        ?.takeIf { it.qualifiedName == ProjectConfigProperties.VALUE }

/** Key completion inside `@Value("${...}")`. */
class ValuePlaceholderCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inside(PsiLiteralExpression::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val literal = PsiTreeUtil.getParentOfType(parameters.position, PsiLiteralExpression::class.java)
                        ?: return
                    valueAnnotationOf(literal) ?: return
                    val module = ModuleUtilCore.findModuleForPsiElement(parameters.originalFile) ?: return

                    // The literal in the completion copy contains the dummy identifier at the caret.
                    val dummy = CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED
                    val before = literal.text.substringBefore(dummy)
                    val open = before.lastIndexOf("\${")
                    if (open < 0) return
                    val typed = before.substring(open + 2)
                    if ('}' in typed || ':' in typed) return // closed placeholder, or typing a default value
                    val closed = literal.text.substringAfter(dummy, "").removePrefix(" ").startsWith("}")

                    val matcher = result.withPrefixMatcher(typed.trim())
                    for (p in ConfigMetadata.properties(module)) {
                        var item = LookupElementBuilder.create(p.name)
                            .withTypeText(p.type?.substringAfterLast('.'), true)
                            .withInsertHandler { ctx, _ ->
                                if (!closed) ctx.document.insertString(ctx.tailOffset, "}")
                            }
                        p.description?.let { item = item.withTailText("  " + it.take(80), true) }
                        matcher.addElement(item)
                    }
                }
            },
        )
    }
}

/**
 * `@Value("${jwt.secret}")` where `jwt.secret` is defined neither by a library nor in any application*.yml /
 * .properties, and the placeholder has no `:default`.
 */
class UnresolvedValueKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitLiteralExpression(expression: PsiLiteralExpression) {
                if (expression.value !is String) return
                valueAnnotationOf(expression) ?: return
                val module = ModuleUtilCore.findModuleForPsiElement(expression) ?: return
                val project = module.project
                val settings = TendrilSettings.getInstance(project)
                val library = ConfigMetadata.libraryIndex(module)

                for (m in PLACEHOLDER.findAll(expression.text)) {
                    if (m.groupValues[2].isNotEmpty()) continue // has a default value
                    val key = m.groupValues[1]
                    if ('.' !in key || SYSTEM_PREFIXES.any { key.startsWith(it) }) continue // env var / system property
                    if (settings.isIgnored(key) || library.isKnown(key)) continue
                    if (ConfigNavigation.allConfigElements(project, key).isNotEmpty()) continue

                    val range = m.groups[1]?.range ?: continue
                    holder.registerProblem(
                        expression,
                        "Configuration key '$key' is not defined in any application config file and has no default",
                        ProblemHighlightType.WEAK_WARNING,
                        TextRange(range.first, range.last + 1),
                        *ignoreFixes(key),
                    )
                }
            }
        }

    private companion object {
        val PLACEHOLDER = Regex("""\$\{\s*([^}:\s]+)\s*(:)?""")
        val SYSTEM_PREFIXES = listOf("user.", "java.", "os.", "file.", "line.", "path.", "env.", "systemProperties.")
    }
}
