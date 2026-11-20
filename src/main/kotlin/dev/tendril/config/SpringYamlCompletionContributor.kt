package dev.tendril.config

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

/**
 * Completes Spring Boot property keys in application*.yml / bootstrap*.yml.
 *
 * Works on document text rather than PSI: while a new key is typed the YAML parser sees it as a
 * scalar value of the parent key, so the parent path is rebuilt from indentation instead.
 */
class SpringYamlCompletionContributor : CompletionContributor() {
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
                    val file = parameters.originalFile
                    if (!SpringFiles.isAppConfig(file.name) || file.name.endsWith(".properties")) return
                    val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return

                    val doc = parameters.editor.document
                    val offset = parameters.offset
                    val line = doc.getLineNumber(offset)
                    val before = doc.getText(TextRange(doc.getLineStartOffset(line), offset))
                    val typed = before.trimStart()
                    val indent = before.length - typed.length
                    if (typed.startsWith("#") || typed.startsWith("-")) return

                    val parentPath = parentPath(doc, line, indent)

                    // "key: va|" -> value suggestions (metadata hints, booleans, enum constants)
                    VALUE_LINE.matchEntire(typed)?.let { m ->
                        val key = listOf(parentPath, m.groupValues[1]).filter { it.isNotEmpty() }.joinToString(".")
                        // comma-separated values (spring.profiles.active: dev,pr|) complete the last item
                        val values = result.withPrefixMatcher(m.groupValues[2].substringAfterLast(','))
                        for (h in ConfigMetadata.valueSuggestions(module, key)) {
                            var item = LookupElementBuilder.create(h.value)
                            h.description?.let { item = item.withTailText("  " + it.take(80), true) }
                            values.addElement(item)
                        }
                        return
                    }
                    if (typed.contains(':')) return

                    val matcher = result.withPrefixMatcher(typed)

                    for (p in ConfigMetadata.properties(module)) {
                        val remainder = when {
                            parentPath.isEmpty() -> p.name
                            p.name.startsWith("$parentPath.") -> p.name.removePrefix("$parentPath.")
                            else -> continue
                        }
                        var item = LookupElementBuilder.create(remainder)
                            .withTypeText(p.type?.substringAfterLast('.'), true)
                            .withInsertHandler { ctx, _ ->
                                val text = nested(remainder, indent)
                                ctx.document.replaceString(ctx.startOffset, ctx.tailOffset, text)
                                ctx.editor.caretModel.moveToOffset(ctx.startOffset + text.length)
                            }
                        p.description?.let { item = item.withTailText("  " + it.take(80), true) }
                        matcher.addElement(item)
                    }
                }
            },
        )
    }

    /** "jpa.hibernate.ddl-auto" at indent 2 -> "jpa:\n    hibernate:\n      ddl-auto: " */
    private fun nested(dotted: String, indent: Int): String {
        val segments = dotted.split('.')
        return buildString {
            segments.forEachIndexed { i, s ->
                if (i > 0) append('\n').append(" ".repeat(indent + 2 * i))
                append(s).append(':')
            }
            append(' ')
        }
    }

    /** Dotted path of the enclosing keys, derived from less-indented lines above the caret. */
    private fun parentPath(doc: com.intellij.openapi.editor.Document, caretLine: Int, caretIndent: Int): String {
        val parents = ArrayDeque<String>()
        var current = caretIndent
        var l = caretLine - 1
        while (l >= 0 && current > 0) {
            val text = doc.getText(TextRange(doc.getLineStartOffset(l), doc.getLineEndOffset(l)))
            val trimmed = text.trim()
            // "---" starts a new YAML document (profile section): parents never cross it.
            if (trimmed == "---" || trimmed.startsWith("--- ")) break
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val ind = text.length - text.trimStart().length
                if (ind < current) {
                    KEY.matchEntire(trimmed)?.let {
                        parents.addFirst(it.groupValues[1])
                        current = ind
                    }
                }
            }
            l--
        }
        return parents.joinToString(".")
    }

    private companion object {
        val VALUE_LINE = Regex("""^([A-Za-z0-9_.\-\[\]"']+):\s+(\S*)$""")
        val KEY = Regex("""^([A-Za-z0-9_.\-\[\]"']+):(\s.*)?$""")
    }
}
