package dev.tendril.config

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import com.intellij.lang.properties.psi.Property

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
            // `spring.profiles` is also the parent of spring.profiles.active / .group: only its scalar form names a profile
            if (kv.value is YAMLScalar && MetadataIndex.normalize(ConfigNavigation.yamlPath(kv)) in DOCUMENT_PROFILE_KEYS) {
                return kv.valueText.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /** Text values of a config entry: a scalar split on commas, or the items of a YAML sequence. */
    fun valuesOf(element: PsiElement): List<String> {
        val raw = when (element) {
            is YAMLKeyValue -> (element.value as? YAMLSequence)?.items?.mapNotNull { it.value?.text }
                ?: listOf(element.valueText)
            is Property -> listOf(element.unescapedValue.orEmpty())
            else -> emptyList()
        }
        return raw.flatMap { it.split(',') }.map { unwrapDefault(it.trim().trim('"', '\'')) }.filter { it.isNotEmpty() }
    }

    /** `${SPRING_PROFILES_ACTIVE:dev}` -> `dev`. */
    private fun unwrapDefault(value: String): String =
        Regex("""\$\{[^:}]+:([^}]*)}""").matchEntire(value)?.groupValues?.get(1)?.trim() ?: value

    /** Members of the profile group [profile] (`spring.profiles.group.<profile>`), in declaration order. */
    fun groupMembers(project: Project, profile: String): List<String> =
        ConfigNavigation.allConfigElements(project, "spring.profiles.group.$profile")
            .filter { profileOf(it) == null }
            .flatMap { valuesOf(it) }
            .distinct()

    /** Profiles `spring.profiles.active` names in the default config, or none. */
    fun activeByDefault(project: Project): List<String> =
        ConfigNavigation.allConfigElements(project, "spring.profiles.active")
            .filter { profileOf(it) == null }
            .flatMap { valuesOf(it) }
            .distinct()

    /**
     * The profiles that are in effect when [selected] is chosen, lowest priority first: the profile itself followed by
     * the members of its group (`spring.profiles.group`), recursively. Without a selection the profiles named by
     * `spring.profiles.active` in the default config are used, so no choice is needed when the project already says
     * which profile runs. Needs a read action.
     */
    fun effectiveProfiles(project: Project, selected: String?): List<String> {
        val roots = if (selected != null) listOf(selected) else activeByDefault(project)
        val result = LinkedHashSet<String>()
        fun add(profile: String, depth: Int) {
            if (!result.add(profile) || depth > 5) return
            groupMembers(project, profile).forEach { add(it, depth + 1) }
        }
        roots.forEach { add(it, 0) }
        return result.toList()
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
