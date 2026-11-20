package dev.tendril.config

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue

private fun report(holder: ProblemsHolder, at: PsiElement, key: String) {
    val module = ModuleUtilCore.findModuleForPsiElement(at) ?: return
    if (TendrilSettings.getInstance(module.project).isIgnored(key)) return
    if (ConfigMetadata.index(module).isKnown(key)) return
    // keys the project's own code reads (e.g. the address of a Feign client by service name)
    val norm = MetadataIndex.normalize(key)
    if (ConfigMetadata.ownScan(module.project).byKey.keys.any { it == norm || it.startsWith("$norm.") }) return

    holder.registerProblem(
        at,
        "Unknown Spring Boot property '$key' (a custom property? Use Alt+Enter to ignore it)",
        ProblemHighlightType.WEAK_WARNING,
        *ignoreFixes(key),
    )
}

/** Offers the key itself plus its parents, so a whole custom namespace ("jwt") can be silenced at once. */
internal fun ignoreFixes(key: String): Array<LocalQuickFix> {
    val candidates = linkedSetOf(key)
    var parent = key
    while ('.' in parent) {
        parent = parent.substringBeforeLast('.')
        candidates += parent
    }
    return candidates.map { IgnoreKeyFix(it, it == key) }.toTypedArray()
}

private class IgnoreKeyFix(private val key: String, private val exact: Boolean) : LocalQuickFix {
    override fun getFamilyName() = "Tendril: ignore unknown property"

    override fun getName() =
        if (exact) "Ignore '$key' (custom property)" else "Ignore everything under '$key'"

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor) = IntentionPreviewInfo.EMPTY

    override fun startInWriteAction() = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        TendrilSettings.getInstance(project).ignore(key)
        DaemonCodeAnalyzer.getInstance(project).restart(this)
    }
}

/** Flags keys in application*.properties that no Spring Boot metadata on the classpath describes. */
class UnknownPropertiesKeyInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is Property) return
                if (!SpringFiles.isAppConfig(element.containingFile.name)) return
                val key = element.unescapedKey ?: return
                val keyElement = element.firstChild ?: return
                report(holder, keyElement, key)
            }
        }
}

/** Flags keys in application*.yml whose dotted path no Spring Boot metadata describes. */
class UnknownYamlKeyInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is YAMLKeyValue) return
                if (!SpringFiles.isAppConfig(element.containingFile.name)) return
                val keyElement = element.key ?: return

                val segments = ArrayDeque<String>()
                var kv: YAMLKeyValue? = element
                while (kv != null) {
                    segments.addFirst(kv.keyText)
                    kv = PsiTreeUtil.getParentOfType(kv, YAMLKeyValue::class.java, true)
                }
                report(holder, keyElement, segments.joinToString("."))
            }
        }
}
