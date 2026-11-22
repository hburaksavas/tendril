package dev.tendril.hints

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.tendril.beans.SpringNames
import dev.tendril.feign.FeignResolver
import dev.tendril.feign.FeignScanner
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.toUElementOfType

/**
 * Inline text after a Spring annotation, in Java and Kotlin. One subclass per kind of hint, so each can be switched
 * off on its own in Settings | Editor | Inlay Hints.
 */
abstract class SpringAnnotationHints : InlayHintsProvider {

    /** The qualified names of the annotations this provider adds text to. */
    protected abstract val annotations: Set<String>

    /** The text for [annotation], or null for none. Runs in a read action, on UAST so that Java and Kotlin share it. */
    abstract fun hint(annotation: UAnnotation): String?

    final override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector =
        object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                if (!isAnnotation(element)) return
                val annotation = element.toUElementOfType<UAnnotation>() ?: return
                if (annotation.qualifiedName !in annotations) return
                val text = hint(annotation) ?: return
                sink.addPresentation(InlineInlayPosition(element.textRange.endOffset, true), hintFormat = HintFormat.default) {
                    text(text)
                }
            }
        }

    private fun isAnnotation(element: PsiElement) =
        element is PsiAnnotation || element.node?.elementType?.toString() == "ANNOTATION_ENTRY"
}

/** `@Value("${a.b}")` -> the value `a.b` has in the profile chosen in the Feign window. */
class ValueHints : SpringAnnotationHints() {
    override val annotations = setOf("org.springframework.beans.factory.annotation.Value")

    override fun hint(annotation: UAnnotation): String? {
        val raw = annotation.findAttributeValue("value")?.evaluateString() ?: return null
        val project = annotation.sourcePsi?.project ?: return null
        return resolvedValue(raw) { FeignResolver.substitute(project, it, FeignResolver.selectedProfile(project)) }
    }

    companion object {
        private val SECRET = Regex("(secret|password|passwd|token|credential|private[-._]?key|api[-._]?key)", RegexOption.IGNORE_CASE)

        /**
         * The text to show for the placeholder [raw], or null when there is nothing to add: no placeholder, an
         * expression (`#{...}`), something unresolved, or no difference. Values of keys that look like secrets are
         * not shown.
         */
        fun resolvedValue(raw: String, substitute: (String) -> Pair<String, List<String>>): String? {
            if ("\${" !in raw || "#{" in raw) return null
            val (resolved, unresolved) = substitute(raw)
            if (unresolved.isNotEmpty() || resolved == raw || resolved.isBlank()) return null
            val shown = if (SECRET.containsMatchIn(raw)) "••••••" else resolved
            return "→ " + if (shown.length > 60) shown.take(57) + "…" else shown
        }
    }
}

/** `@FeignClient(...)` -> the address it resolves to in the chosen profile. */
class FeignHints : SpringAnnotationHints() {
    override val annotations = setOf(
        SpringNames.FEIGN_CLIENT, "org.springframework.cloud.netflix.feign.FeignClient",
    )

    override fun hint(annotation: UAnnotation): String? {
        val cls = annotation.getContainingUClass()?.javaPsi ?: return null
        val def = FeignScanner.defOf(cls) ?: return null
        val project = cls.project
        val info = FeignResolver.resolve(project, def, FeignResolver.selectedProfile(project))
        val url = info.resolution.url ?: return null
        return "→ " + url + info.path.takeIf { it.isNotBlank() }?.let { "/" + it.trim('/') }.orEmpty()
    }
}

/** `@Scheduled(...)` -> when it runs next, or how often. */
class ScheduledHints : SpringAnnotationHints() {
    override val annotations = setOf("org.springframework.scheduling.annotation.Scheduled")

    override fun hint(annotation: UAnnotation): String? {
        val project = annotation.sourcePsi?.project ?: return null
        fun text(name: String): String? = annotation.findAttributeValue(name)?.evaluateString()?.takeIf { it.isNotBlank() }
        fun resolved(value: String) = FeignResolver.substitute(project, value, FeignResolver.selectedProfile(project))
            .takeIf { it.second.isEmpty() }?.first

        text("cron")?.let { cron ->
            val value = resolved(cron) ?: return null
            if (value == "-") return "disabled"
            val zone = text("zone")?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneId.systemDefault()
            return CronSchedule.nextRun(value, zone)?.let { "next: $it" }
        }
        for ((number, string, label) in listOf(
            Triple("fixedRate", "fixedRateString", "every"), Triple("fixedDelay", "fixedDelayString", "every"),
        )) {
            val millis = (annotation.findAttributeValue(number)?.evaluate() as? Number)?.toLong()?.takeIf { it > 0 }
                ?: text(string)?.let { resolved(it) }?.let(::parseDuration)
                ?: continue
            return label + " " + formatMillis(millis) + if (number == "fixedDelay") " after each run" else ""
        }
        return null
    }

    companion object {
        /** Spring accepts plain milliseconds or an ISO-8601 duration (`PT30S`) in the `...String` attributes. */
        fun parseDuration(text: String): Long? =
            text.trim().toLongOrNull() ?: runCatching { java.time.Duration.parse(text.trim()).toMillis() }.getOrNull()

        fun formatMillis(millis: Long): String = when {
            millis % 3_600_000 == 0L -> "${millis / 3_600_000} h"
            millis % 60_000 == 0L -> "${millis / 60_000} min"
            millis % 1000 == 0L -> "${millis / 1000} s"
            else -> "$millis ms"
        }
    }
}

/** `@Transactional` -> propagation, access mode and the other attributes that are not the default. */
class TransactionalHints : SpringAnnotationHints() {
    override val annotations = setOf(
        "org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional",
        "javax.transaction.Transactional",
    )

    override fun hint(annotation: UAnnotation): String? {
        fun constant(name: String) = annotation.findAttributeValue(name)?.sourcePsi?.text?.substringAfterLast('.')?.trim()
        val jakarta = annotation.qualifiedName != "org.springframework.transaction.annotation.Transactional"
        val classes = annotation.findAttributeValue("rollbackFor")?.sourcePsi?.text
            ?.let { Regex("([A-Za-z_][A-Za-z0-9_]*)(?:::class|\\.class)").findAll(it).map { m -> m.groupValues[1] }.toList() }
            .orEmpty()
        return summary(
            propagation = if (jakarta) constant("value") else constant("propagation"),
            isolation = constant("isolation"),
            readOnly = annotation.findAttributeValue("readOnly")?.evaluate() as? Boolean ?: false,
            timeout = (annotation.findAttributeValue("timeout")?.evaluate() as? Number)?.toInt()?.takeIf { it >= 0 },
            rollbackFor = classes,
        )
    }

    companion object {
        /** `REQUIRED · read-only`, with isolation, timeout and `rollbackFor` appended when they are set. */
        fun summary(propagation: String?, isolation: String?, readOnly: Boolean, timeout: Int?, rollbackFor: List<String>): String =
            buildList {
                add(propagation?.takeIf { it.isNotBlank() } ?: "REQUIRED")
                add(if (readOnly) "read-only" else "read-write")
                isolation?.takeIf { it.isNotBlank() && it != "DEFAULT" }?.let { add(it) }
                timeout?.let { add("${it}s timeout") }
                if (rollbackFor.isNotEmpty()) add("rollback on ${rollbackFor.joinToString()}")
            }.joinToString(" · ")
    }
}
