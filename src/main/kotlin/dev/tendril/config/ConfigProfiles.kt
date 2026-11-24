package dev.tendril.config

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
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

    /**
     * Members of the profile group [profile] (`spring.profiles.group.<profile>`), in declaration order. With [from], only
     * the config of the application modules that [from]'s module belongs to is read.
     */
    fun groupMembers(project: Project, profile: String, from: PsiElement? = null): List<String> =
        defaultValues(project, "spring.profiles.group.$profile", ConfigNavigation.scopeOf(from))

    /** Profiles `spring.profiles.active` names in the default config, or none. See [groupMembers] for [from]. */
    fun activeByDefault(project: Project, from: PsiElement? = null): List<String> =
        defaultValues(project, "spring.profiles.active", ConfigNavigation.scopeOf(from))

    private fun defaultValues(project: Project, key: String, scope: (List<PsiElement>) -> List<PsiElement>): List<String> =
        scope(ConfigNavigation.allConfigElements(project, key))
            .filter { profileOf(it) == null }
            .flatMap { valuesOf(it) }
            .distinct()

    /**
     * The profiles that are in effect when [selected] is chosen, lowest priority first: the profile itself followed by
     * the members of its group (`spring.profiles.group`), recursively. Without a selection the profiles named by
     * `spring.profiles.active` in the default config are used, so no choice is needed when the project already says
     * which profile runs. With [from], the active profile and groups come from the config of [from]'s application
     * module rather than from any module of the project. Needs a read action.
     */
    fun effectiveProfiles(project: Project, selected: String?, from: PsiElement? = null): List<String> {
        val scope = ConfigNavigation.scopeOf(from)
        val roots = if (selected != null) listOf(selected) else defaultValues(project, "spring.profiles.active", scope)
        val result = LinkedHashSet<String>()
        fun add(profile: String, depth: Int) {
            if (!result.add(profile) || depth > 5) return
            defaultValues(project, "spring.profiles.group.$profile", scope).forEach { add(it, depth + 1) }
        }
        roots.forEach { add(it, 0) }
        return result.toList()
    }

    /**
     * Profile names known from application-<profile> file names and `on-profile` sections, sorted. Cached until a file
     * or the project structure changes. Needs a read action.
     */
    fun knownProfiles(project: Project): Set<String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                scanProfiles(project),
                PsiModificationTracker.getInstance(project),
                ProjectRootModificationTracker.getInstance(project),
            )
        }

    private fun scanProfiles(project: Project): Set<String> {
        val result = sortedSetOf<String>()
        // The file name index is shared by every open project: keep only names that have a file in this one.
        val scope = GlobalSearchScope.projectScope(project)
        for (name in FilenameIndex.getAllFilenames(project)) {
            val match = PROFILE_FILE.matchEntire(name) ?: continue
            if (FilenameIndex.getVirtualFilesByName(name, scope).isNotEmpty()) result += match.groupValues[1]
        }
        for (key in DOCUMENT_PROFILE_KEYS) {
            for (e in ConfigNavigation.allConfigElements(project, key)) {
                val value = (e as? YAMLKeyValue)?.valueText ?: continue
                value.split(',', '|', '&', '(', ')', ' ').map { it.trim().removePrefix("!") }
                    .filterTo(result) { it.isNotEmpty() }
            }
        }
        return java.util.Collections.unmodifiableSortedSet(result)
    }
}
