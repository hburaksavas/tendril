package dev.tendril.config

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Spring profiles as they appear in config files:
 *  - file name: application-dev.yml / application-dev.properties
 *  - YAML multi-document files: a document whose `spring.config.activate.on-profile` (or legacy `spring.profiles`) is set
 */
object ConfigProfiles {
    private val PROFILE_FILE = Regex("(?:application|bootstrap)-(.+)\\.(?:properties|ya?ml)")

    /** Keys whose values are profile names, in relaxed-normalized form (see [MetadataIndex.normalize]: dots stay). */
    val PROFILE_KEYS = setOf(
        "spring.profiles.active", "spring.profiles.include", "spring.profiles.default", "spring.config.activate.onprofile",
    )
    private val DOCUMENT_PROFILE_KEYS = setOf("spring.config.activate.onprofile", "spring.profiles")

    /** Profile that [element]'s config section is limited to, or null for the default (always active) section. */
    fun profileOf(element: PsiElement): String? {
        val fileName = element.containingFile?.name ?: return null
        PROFILE_FILE.matchEntire(fileName)?.let { return it.groupValues[1] }

        val document = PsiTreeUtil.getParentOfType(element, YAMLDocument::class.java, false) ?: return null
        for (kv in PsiTreeUtil.findChildrenOfType(document, YAMLKeyValue::class.java)) {
            if (MetadataIndex.normalize(ConfigNavigation.yamlPath(kv)) in DOCUMENT_PROFILE_KEYS) {
                return kv.valueText.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /** Profile names known from application-<profile> file names and `on-profile` sections. Needs a read action. */
    fun knownProfiles(project: Project): Set<String> {
        val result = sortedSetOf<String>()
        for (name in FilenameIndex.getAllFilenames(project)) {
            PROFILE_FILE.matchEntire(name)?.let { result += it.groupValues[1] }
        }
        for (key in DOCUMENT_PROFILE_KEYS) {
            for (e in ConfigNavigation.allConfigElements(project, key)) {
                val value = (e as? YAMLKeyValue)?.valueText ?: continue
                value.split(',', '|', '&', '(', ')', ' ').map { it.trim().removePrefix("!") }
                    .filterTo(result) { it.isNotEmpty() }
            }
        }
        return result
    }
}
