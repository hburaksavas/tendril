package dev.tendril.config

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.uast.UastHintedVisitorAdapter
import com.intellij.util.ProcessingContext
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.expressions.UInjectionHost
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * The string literal [host] if it sits in an annotation whose strings may hold `${config.key}` placeholders
 * (`@Value`, `@FeignClient`); works for Java and Kotlin sources.
 */
internal fun placeholderHost(host: PsiElement?): PsiLanguageInjectionHost? {
    // a Java literal is a ULiteralExpression, a Kotlin string template a UPolyadicExpression: both are injection hosts
    val literal = host?.toUElement() as? UInjectionHost ?: return null
    if (!literal.isString) return null
    val annotation = (literal as? UElement)?.getParentOfType<UAnnotation>() ?: return null
    if (annotation.qualifiedName !in ProjectConfigProperties.PLACEHOLDER_ANNOTATIONS) return null
    return literal.sourcePsi as? PsiLanguageInjectionHost
}

/** Key completion inside `@Value("${...}")`, in Java and Kotlin. */
class ValuePlaceholderCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val host = placeholderHost(PsiTreeUtil.getParentOfType(parameters.position, PsiLanguageInjectionHost::class.java))
                        ?: return
                    val module = ModuleUtilCore.findModuleForPsiElement(parameters.originalFile) ?: return

                    // The literal in the completion copy contains the dummy identifier at the caret.
                    val dummy = CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED
                    val text = host.text
                    val before = text.substringBefore(dummy)
                    val open = before.lastIndexOf("\${")
                    if (open < 0) return
                    val typed = before.substring(open + 2)
                    if ('}' in typed || ':' in typed) return // closed placeholder, or typing a default value
                    val closed = text.substringAfter(dummy, "").removePrefix(" ").startsWith("}")

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
 * .properties, and the placeholder has no `:default`. Works for Java and Kotlin.
 */
class UnresolvedValueKeyInspection : AbstractBaseUastLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitLiteralExpression(node: ULiteralExpression): Boolean {
                    check(node)
                    return true
                }

                override fun visitPolyadicExpression(node: UPolyadicExpression): Boolean {
                    check(node)
                    return true
                }

                private fun check(node: UExpression) {
                    val host = placeholderHost(node.sourcePsi) ?: return
                    val module = ModuleUtilCore.findModuleForPsiElement(host) ?: return
                    val project = module.project
                    val settings = TendrilSettings.getInstance(project)
                    val library = ConfigMetadata.libraryIndex(module)

                    for (m in PLACEHOLDER.findAll(host.text)) {
                        if (m.groupValues[2].isNotEmpty()) continue // has a default value
                        val key = m.groupValues[1]
                        if ('.' !in key || SYSTEM_PREFIXES.any { key.startsWith(it) }) continue // env var / system property
                        if (settings.isIgnored(key) || library.isKnown(key)) continue
                        if (ConfigNavigation.allConfigElements(project, key).isNotEmpty()) continue

                        val range = m.groups[1]?.range ?: continue
                        holder.registerProblem(
                            host,
                            "Configuration key '$key' is not defined in any application config file and has no default",
                            ProblemHighlightType.WEAK_WARNING,
                            TextRange(range.first, range.last + 1),
                            *ignoreFixes(key),
                        )
                    }
                }
            },
            arrayOf(ULiteralExpression::class.java, UPolyadicExpression::class.java),
        )

    private companion object {
        val PLACEHOLDER = Regex("""\$\{\s*([^}:\s]+)\s*(:)?""")
        val SYSTEM_PREFIXES = listOf("user.", "java.", "os.", "file.", "line.", "path.", "env.", "systemProperties.")
    }
}
