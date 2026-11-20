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

    fun selectedProfile(project: Project): String? =
        PropertiesComponent.getInstance(project).getValue(PROFILE_KEY)?.takeIf { it.isNotBlank() }

    fun selectProfile(project: Project, profile: String?) =
        PropertiesComponent.getInstance(project).setValue(PROFILE_KEY, profile.orEmpty())

    fun resolve(project: Project, def: FeignClientDef, profile: String?): FeignClientInfo {
        val (name, _) = substitute(project, def.name, profile)
        val (path, _) = substitute(project, def.path, profile)
        val (url, urlUnresolved) = substitute(project, def.url, profile)

        val resolution = if (def.url.isNotBlank() && url.isNotBlank() && urlUnresolved.isEmpty()) {
            FeignResolution(withScheme(url), if (def.url == url) "url attribute" else "url attribute ${def.url}")
        } else {
            byName(project, name, profile) ?: FeignResolution(
                null,
                if (def.url.isBlank()) "no url, resolved by discovery at runtime" else "url cannot be resolved",
                urlUnresolved,
            )
        }
        return FeignClientInfo(def, name, path, resolution)
    }

    private fun byName(project: Project, name: String, profile: String?): FeignResolution? {
        if (name.isBlank()) return null

        TendrilSettings.getInstance(project).state.serviceUrls[name]?.takeIf { it.isNotBlank() }?.let {
            return FeignResolution(withScheme(it), "Tendril address table for '$name'")
        }

        for (template in NAME_KEYS) {
            val key = template.replace("{name}", name)
            val raw = valueFor(ConfigNavigation.allConfigElements(project, key), profile) ?: continue
            val (resolved, unresolved) = substitute(project, raw, profile)
            val first = resolved.split(',').first().trim()
            if (unresolved.isEmpty() && first.isNotEmpty()) return FeignResolution(withScheme(first), "config key $key")
        }

        // SimpleDiscoveryClient's host/port/secure form (instead of a single uri)
        val instance = "$SIMPLE_INSTANCES.$name[0]"
        fun part(suffix: String): String? =
            valueFor(ConfigNavigation.allConfigElements(project, "$instance.$suffix"), profile)
                ?.let { substitute(project, it, profile) }?.takeIf { it.second.isEmpty() }?.first
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
            val candidates = ConfigNavigation.allEntries(project).entries
                .filter { it.key.replace(".", "").contains(needle) }
                .sortedBy { it.key.length }
            for ((_, elements) in candidates) {
                val raw = valueFor(elements, profile) ?: continue
                val (resolved, unresolved) = substitute(project, raw, profile)
                if (unresolved.isEmpty() && (resolved.startsWith("http://") || resolved.startsWith("https://"))) {
                    return FeignResolution(resolved.trimEnd('/'), "guessed from config key ${keyText(elements.first())}")
                }
            }
        }
        return null
    }

    /** Replaces `${key}` / `${key:default}` using the config files; unresolved keys are returned and left in place. */
    fun substitute(project: Project, text: String, profile: String?): Pair<String, List<String>> {
        if ('$' !in text) return text to emptyList()
        val unresolved = LinkedHashSet<String>()
        var current = text
        repeat(5) {
            val next = PLACEHOLDER.replace(current) { m ->
                val key = m.groupValues[1].trim()
                val default = m.groups[2]?.value
                valueFor(ConfigNavigation.allConfigElements(project, key), profile)
                    ?: default
                    ?: m.value.also { unresolved += key }
            }
            if (next == current) return current to unresolved.toList()
            current = next
        }
        return current to unresolved.toList()
    }

    /** Config keys through which the address of service [name] can be given (well-known names and discovery instances). */
    fun addressKeys(name: String): List<String> =
        NAME_KEYS.map { it.replace("{name}", name) } +
            listOf("uri", "host", "port").map { "$SIMPLE_INSTANCES.$name[0].$it" }

    /**
     * Every config entry (all profiles) that holds the address of service [name]: the well-known keys, discovery
     * instances and, as a fallback, any key mentioning the service whose value is an http(s) URL. Needs a read action.
     */
    fun addressElements(project: Project, name: String): List<PsiElement> {
        val out = LinkedHashSet<PsiElement>()
        for (key in addressKeys(name)) out += ConfigNavigation.allConfigElements(project, key)
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

    /** The profile-specific value if the profile defines one, else the default (profile-less) value. */
    private fun valueFor(elements: List<PsiElement>, profile: String?): String? {
        val ordered = (if (profile != null) elements.filter { ConfigProfiles.profileOf(it) == profile } else emptyList()) +
            elements.filter { ConfigProfiles.profileOf(it) == null }
        return ordered.firstNotNullOfOrNull { valueOf(it) }
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
