package dev.tendril.feign

import com.intellij.ide.util.PropertiesComponent
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import dev.tendril.config.ConfigNavigation
import dev.tendril.config.ConfigProfiles
import dev.tendril.config.TendrilSettings
import org.jetbrains.yaml.psi.YAMLKeyValue

/** Where a Feign client's base address came from. [url] is null when nothing resolves. */
class FeignResolution(val url: String?, val source: String, val unresolved: List<String> = emptyList())

/** A client definition with everything resolved for one profile. */
class FeignClientInfo(
    val def: FeignClientDef,
    val name: String,
    val path: String,
    val resolution: FeignResolution,
)

/**
 * Resolves the address a `@FeignClient` talks to, without a running discovery server:
 *  1. the `url` attribute, with `${placeholders}` resolved from the config files of the chosen profile
 *  2. if `url` is absent or empty: the service `name`, looked up
 *     - in Tendril's own service-address table (Settings),
 *     - under well-known config keys (see [NAME_KEYS]),
 *     - by guessing: any config key that contains the service name and holds an http(s) URL.
 * Needs a read action.
 */
object FeignResolver {
    private val PLACEHOLDER = Regex("""\$\{([^}:]+)(?::([^}]*))?}""")
    private const val PROFILE_KEY = "tendril.feign.profile"

    /** Config keys tried for a name-only client; `{name}` is replaced by the service name. */
    val NAME_KEYS = listOf(
        "spring.cloud.openfeign.client.config.{name}.url",
        "$SIMPLE_INSTANCES.{name}[0].uri",
        "{name}.ribbon.listOfServers",
        "{name}.url",
        "{name}.base-url",
        "{name}.uri",
        "services.{name}.url",
        "clients.{name}.url",
        "feign.{name}.url",
        "feign.client.config.{name}.url",
    )

    /** The keys the user added in the settings (tried first), then the built-in [NAME_KEYS]. */
    fun nameKeys(project: Project): List<String> =
        TendrilSettings.getInstance(project).state.nameKeyTemplates.map { it.trim() }.filter { "{name}" in it } + NAME_KEYS

    fun selectedProfile(project: Project): String? =
        PropertiesComponent.getInstance(project).getValue(PROFILE_KEY)?.takeIf { it.isNotBlank() }

    fun selectProfile(project: Project, profile: String?) =
        PropertiesComponent.getInstance(project).setValue(PROFILE_KEY, profile.orEmpty())

    /**
     * Resolves [def] for the [selected] profile (null: the default config with its `spring.profiles.active`). Config is
     * read from the application modules that the client's module belongs to. When no profile is in effect at all and
     * the default config gives no address, the address of the first profile (by name) that yields one is used, and
     * the source says so: values are never mixed from several profiles.
     */
    fun resolve(project: Project, def: FeignClientDef, selected: String?): FeignClientInfo {
        val from = def.cls.element
        val profiles = ConfigProfiles.effectiveProfiles(project, selected, from)
        val info = resolveWith(def, Lookup(project, from, profiles))
        if (profiles.isNotEmpty() || info.resolution.url != null) return info
        for (profile in ConfigProfiles.knownProfiles(project)) {
            val candidate = resolveWith(def, Lookup(project, from, ConfigProfiles.effectiveProfiles(project, profile, from)))
            val r = candidate.resolution
            if (r.url != null) {
                return FeignClientInfo(def, candidate.name, candidate.path, FeignResolution(r.url, "${r.source}, only in profile $profile"))
            }
        }
        return info
    }

    /**
     * The address of [def] when exactly the config of [selected] (null: the default config) is read, without falling
     * back to other profiles. Used to tell which profiles change an address.
     */
    internal fun strictAddress(project: Project, def: FeignClientDef, selected: String?): String? {
        val from = def.cls.element
        return resolveWith(def, Lookup(project, from, ConfigProfiles.effectiveProfiles(project, selected, from))).resolution.url
    }

    /**
     * The profiles worth choosing: those under which at least one client resolves to another address than under the
     * default config, or for which [extra] says so (the application's own address). Sorted by name. Needs a read action.
     */
    fun addressProfiles(project: Project, defs: List<FeignClientDef>, extra: (String) -> Boolean = { false }): List<String> {
        val defaults = defs.map { strictAddress(project, it, null) }
        return ConfigProfiles.knownProfiles(project).filter { profile ->
            extra(profile) || defs.indices.any { i -> strictAddress(project, defs[i], profile) != defaults[i] }
        }
    }

    /**
     * The value of [key] for the application [from] belongs to, in its default config (with `spring.profiles.active`),
     * else from the first profile file that defines it; a `${VAR:default}` value gives its default. Needs a read action.
     */
    fun appValue(project: Project, key: String, from: PsiElement?): String? {
        val elements = ConfigNavigation.scopeOf(from)(ConfigNavigation.allConfigElements(project, key))
        val value = valueFor(elements, ConfigProfiles.effectiveProfiles(project, null, from)) ?: return null
        return PLACEHOLDER.matchEntire(value.trim())?.let { it.groups[2]?.value } ?: value
    }

    /** Config lookups for one client: the profiles in effect and the modules whose config applies. */
    private class Lookup(val project: Project, from: PsiElement?, val profiles: List<String>) {
        private val scope = ConfigNavigation.scopeOf(from)
        fun elements(key: String): List<PsiElement> = scope(ConfigNavigation.allConfigElements(project, key))
        fun entries(): List<Pair<String, List<PsiElement>>> =
            ConfigNavigation.allEntries(project).map { (key, elements) -> key to scope(elements) }
        fun value(key: String): String? = value(elements(key))
        fun value(elements: List<PsiElement>): String? = valueFor(elements, profiles, fallback = false)
    }

    private fun resolveWith(def: FeignClientDef, lookup: Lookup): FeignClientInfo {
        val (name, _) = substituteIn(def.name, lookup)
        val (path, _) = substituteIn(def.path, lookup)
        val (url, urlUnresolved) = substituteIn(def.url, lookup)

        val resolution = if (def.url.isNotBlank() && url.isNotBlank() && urlUnresolved.isEmpty()) {
            FeignResolution(withScheme(url), if (def.url == url) "url attribute" else "url attribute ${def.url}")
        } else {
            byName(name, lookup) ?: FeignResolution(
                null,
                if (def.url.isBlank()) "no url, resolved by discovery at runtime" else "url cannot be resolved",
                urlUnresolved,
            )
        }
        return FeignClientInfo(def, name, path, resolution)
    }

    private fun byName(name: String, lookup: Lookup): FeignResolution? {
        if (name.isBlank()) return null
        val project = lookup.project

        TendrilSettings.getInstance(project).state.serviceUrls[name]?.takeIf { it.isNotBlank() }?.let {
            return FeignResolution(withScheme(it), "Tendril address table for '$name'")
        }

        for (template in nameKeys(project)) {
            val key = template.replace("{name}", name)
            val raw = lookup.value(key) ?: continue
            val (resolved, unresolved) = substituteIn(raw, lookup)
            val first = resolved.split(',').first().trim()
            if (unresolved.isEmpty() && first.isNotEmpty()) return FeignResolution(withScheme(first), "config key $key")
        }

        // SimpleDiscoveryClient's host/port/secure form (instead of a single uri)
        val instance = "$SIMPLE_INSTANCES.$name[0]"
        fun part(suffix: String): String? =
            lookup.value("$instance.$suffix")?.let { substituteIn(it, lookup) }?.takeIf { it.second.isEmpty() }?.first
        part("host")?.let { host ->
            val secure = part("secure")?.toBooleanStrictOrNull() == true
            val port = part("port")?.takeIf { p -> p.all(Char::isDigit) }
            return FeignResolution(
                "${if (secure) "https" else "http"}://$host" + (port?.let { ":$it" } ?: ""),
                "config key $instance.host/port",
            )
        }

        // Last resort: any key that mentions the service and holds an http(s) address.
        val needle = name.lowercase().filter { it.isLetterOrDigit() }
        if (needle.length >= 3) {
            val candidates = lookup.entries()
                .filter { it.first.replace(".", "").contains(needle) && it.second.isNotEmpty() }
                .sortedBy { it.first.length }
            for ((_, elements) in candidates) {
                val raw = lookup.value(elements) ?: continue
                val (resolved, unresolved) = substituteIn(raw, lookup)
                if (unresolved.isEmpty() && (resolved.startsWith("http://") || resolved.startsWith("https://"))) {
                    return FeignResolution(resolved.trimEnd('/'), "guessed from config key ${keyText(elements.first())}")
                }
            }
        }
        return null
    }

    /** Replaces `${key}` / `${key:default}` using the config files; unresolved keys are returned and left in place. */
    fun substitute(project: Project, text: String, selected: String?): Pair<String, List<String>> {
        val profiles = ConfigProfiles.effectiveProfiles(project, selected)
        return substituteWith(text) { key -> valueFor(ConfigNavigation.allConfigElements(project, key), profiles) }
    }

    private fun substituteIn(text: String, lookup: Lookup): Pair<String, List<String>> = substituteWith(text, lookup::value)

    private fun substituteWith(text: String, value: (String) -> String?): Pair<String, List<String>> {
        if ('$' !in text) return text to emptyList()
        val unresolved = LinkedHashSet<String>()
        var current = text
        repeat(5) {
            val next = PLACEHOLDER.replace(current) { m ->
                val key = m.groupValues[1].trim()
                val default = m.groups[2]?.value
                value(key) ?: default ?: m.value.also { unresolved += key }
            }
            if (next == current) return current to unresolved.toList()
            current = next
        }
        return current to unresolved.toList()
    }

    /** Config keys through which the address of service [name] can be given (well-known names and discovery instances). */
    fun addressKeys(project: Project, name: String): List<String> =
        nameKeys(project).map { it.replace("{name}", name) } +
            listOf("uri", "host", "port").map { "$SIMPLE_INSTANCES.$name[0].$it" }

    /**
     * Every config entry (all profiles) that holds the address of service [name]: the well-known keys, discovery
     * instances and, as a fallback, any key mentioning the service whose value is an http(s) URL. Needs a read action.
     */
    fun addressElements(project: Project, name: String): List<PsiElement> {
        val out = LinkedHashSet<PsiElement>()
        for (key in addressKeys(project, name)) out += ConfigNavigation.allConfigElements(project, key)
        if (out.isEmpty()) {
            val needle = name.lowercase().filter { it.isLetterOrDigit() }
            if (needle.length >= 3) {
                for ((key, elements) in ConfigNavigation.allEntries(project)) {
                    if (!key.replace(".", "").contains(needle)) continue
                    elements.filterTo(out) { valueOf(it)?.let { v -> v.startsWith("http://") || v.startsWith("https://") } == true }
                }
            }
        }
        return out.toList()
    }

    /**
     * The profile-specific value if the profile defines one, else the default (profile-less) value. With [fallback],
     * when no profile is in effect and the default config says nothing, the value of the first profile by name (then by
     * file path) is used: a value that only a profile file defines beats none, and the pick is the same every time.
     */
    internal fun valueFor(elements: List<PsiElement>, profiles: List<String>, fallback: Boolean = true): String? {
        // later profiles win; the default (profile-less) value is the last resort
        val ordered = profiles.asReversed().flatMap { p -> elements.filter { ConfigProfiles.profileOf(it) == p } } +
            elements.filter { ConfigProfiles.profileOf(it) == null }
        ordered.firstNotNullOfOrNull { valueOf(it) }?.let { return it }
        if (!fallback || profiles.isNotEmpty()) return null
        return elements.mapNotNull { e -> ConfigProfiles.profileOf(e)?.let { it to e } }
            .sortedWith(compareBy({ it.first }, { it.second.containingFile?.virtualFile?.path.orEmpty() }))
            .firstNotNullOfOrNull { valueOf(it.second) }
    }

    private fun valueOf(e: PsiElement): String? = when (e) {
        is YAMLKeyValue -> e.valueText.takeIf { v -> v.isNotBlank() }
        is Property -> e.unescapedValue?.takeIf { v -> v.isNotBlank() }
        else -> null
    }

    private const val SIMPLE_INSTANCES = "spring.cloud.discovery.client.simple.instances"

    private fun keyText(e: PsiElement): String = when (e) {
        is YAMLKeyValue -> ConfigNavigation.yamlPath(e)
        is Property -> e.unescapedKey.orEmpty()
        else -> e.text
    }

    private fun withScheme(url: String): String {
        val u = url.trim().trimEnd('/')
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "http://$u"
    }
}
