package dev.tendril.config

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.properties.parsing.PropertiesTokenTypes
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

class SpringPropertyCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(PropertiesTokenTypes.KEY_CHARACTERS),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val file = parameters.originalFile
                    if (!SpringFiles.isAppConfig(file.name)) return
                    val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return

                    // Keys contain dots; match against the whole typed key, not a single word.
                    val prefix = result.withPrefixMatcher(typedText(parameters))
                    for (p in ConfigMetadata.properties(module)) {
                        var item = LookupElementBuilder.create(p.name)
                            .withTypeText(p.type?.substringAfterLast('.'), true)
                        p.description?.let { item = item.withTailText("  " + it.take(80), true) }
                        p.defaultValue?.let { item = item.withPresentableText("${p.name} (default: $it)") }
                        prefix.addElement(item)
                    }
                }
            },
        )

        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(PropertiesTokenTypes.VALUE_CHARACTERS),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val file = parameters.originalFile
                    if (!SpringFiles.isAppConfig(file.name)) return
                    val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return
                    val key = PsiTreeUtil.getParentOfType(parameters.position, Property::class.java)?.key ?: return

                    val prefix = result.withPrefixMatcher(typedText(parameters).substringAfterLast(','))
                    for (h in ConfigMetadata.valueSuggestions(module, key)) {
                        var item = LookupElementBuilder.create(h.value)
                        h.description?.let { item = item.withTailText("  " + it.take(80), true) }
                        prefix.addElement(item)
                    }
                }
            },
        )
    }

    private fun typedText(parameters: CompletionParameters): String =
        parameters.position.text.substringBefore(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED).trim()
}

object SpringFiles {
    private val CONFIG = Regex("(application|bootstrap).*\\.(properties|ya?ml)")

    fun isAppConfig(fileName: String) = CONFIG.matches(fileName)
}
