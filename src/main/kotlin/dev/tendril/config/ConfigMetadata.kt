package dev.tendril.config

import com.google.gson.JsonParser
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import java.util.TreeSet

data class SpringProperty(
    val name: String,
    val type: String?,
    val description: String?,
    val defaultValue: String?,
)

data class ValueHint(val value: String, val description: String?)

class MetadataIndex(
    val properties: Map<String, SpringProperty>,
    val hints: Map<String, List<ValueHint>>,
    /** False when no Spring Boot metadata is on the classpath; the unknown-key check then stays silent. */
    private val hasLibraryMetadata: Boolean = properties.isNotEmpty(),
) {
    private val byNormalized = properties.values.associateBy { normalize(it.name) }
    private val sorted = TreeSet(byNormalized.keys)

    /** True if [key] is a known property, a parent of one, or a child key of a map/object property. */
    fun isKnown(key: String): Boolean {
        if (!hasLibraryMetadata) return true
        val norm = normalize(key)
        if (norm in sorted) return true
        val dotted = "$norm."
        sorted.ceiling(dotted)?.let { if (it.startsWith(dotted)) return true }
        // key below a property that can hold nested keys (Map, nested object, list of objects)
        var prefix = ""
        for (seg in norm.split('.').dropLast(1)) {
            prefix = if (prefix.isEmpty()) seg else "$prefix.$seg"
            val p = byNormalized[prefix] ?: continue
            if (!isSimple(p.type)) return true
        }
        return false
    }

    fun propertyByKey(key: String): SpringProperty? = properties[key] ?: byNormalized[normalize(key)]

    private fun isSimple(type: String?): Boolean =
        type != null && (type.startsWith("java.lang.") || type in SIMPLE_TYPES)

    companion object {
        private val SIMPLE_TYPES = setOf(
            "int", "long", "boolean", "double", "float", "short", "byte", "char",
            "java.time.Duration", "java.time.Period", "org.springframework.util.unit.DataSize",
            "java.nio.charset.Charset", "java.util.Locale", "java.net.InetAddress", "java.io.File",
        )

        /** Spring relaxed binding: ignore case, '-' and '_', and list indexes. */
        fun normalize(key: String): String =
            key.replace(Regex("\\[[^\\]]*]"), "").replace("-", "").replace("_", "").lowercase()
    }
}

object ConfigMetadata {
    private const val PATH = "META-INF/spring-configuration-metadata.json"

    /** Library metadata plus properties the project defines itself; re-merged when roots or Java code change. */
    fun index(module: Module): MetadataIndex {
        val project = module.project
        return CachedValuesManager.getManager(project).getCachedValue(module) {
            val library = libraryIndex(module)
            val merged = LinkedHashMap(library.properties)
            for (p in ownProperties(project)) merged.putIfAbsent(p.name, p)
            CachedValueProvider.Result.create(
                MetadataIndex(merged, library.hints, library.properties.isNotEmpty()),
                ProjectRootModificationTracker.getInstance(project),
                PsiModificationTracker.getInstance(project).forLanguage(JavaLanguage.INSTANCE),
            )
        }
    }

    /** Only what libraries on the classpath describe (no project-defined keys). */
    fun libraryIndex(module: Module): MetadataIndex =
        CachedValuesManager.getManager(module.project).getCachedValue(module) {
            CachedValueProvider.Result.create(
                load(module),
                ProjectRootModificationTracker.getInstance(module.project),
            )
        }

    private fun ownProperties(project: Project): List<SpringProperty> = ownScan(project).properties

    /** Properties and code consumers found in the project's own sources. Must be called in a read action. */
    fun ownScan(project: Project): ProjectConfigScan =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                ProjectConfigProperties.scan(project),
                PsiModificationTracker.getInstance(project).forLanguage(JavaLanguage.INSTANCE),
                TendrilSettings.getInstance(project).tracker,
            )
        }

    fun properties(module: Module): Collection<SpringProperty> = index(module).properties.values

    /** Value suggestions: explicit metadata hints first, then booleans and enum constants. Needs a read action. */
    fun valueSuggestions(module: Module, key: String): List<ValueHint> {
        if (MetadataIndex.normalize(key) in ConfigProfiles.PROFILE_KEYS) {
            return ConfigProfiles.knownProfiles(module.project).map { ValueHint(it, "profile") }
        }
        val index = index(module)
        val property = index.propertyByKey(key) ?: return emptyList()
        index.hints[property.name]?.let { if (it.isNotEmpty()) return it }

        val type = property.type ?: return emptyList()
        if (type == "java.lang.Boolean" || type == "boolean") {
            return listOf(ValueHint("true", null), ValueHint("false", null))
        }
        if ('<' in type) return emptyList()
        // Metadata writes nested types with '$' (org.springframework.boot.Banner$Mode); PSI wants '.'.
        val cls = JavaPsiFacade.getInstance(module.project)
            .findClass(type.replace('$', '.'), module.getModuleWithLibrariesScope()) ?: return emptyList()
        if (!cls.isEnum) return emptyList()
        return cls.fields.filterIsInstance<PsiEnumConstant>()
            .map { ValueHint(it.name.lowercase().replace('_', '-'), null) }
    }

    private fun load(module: Module): MetadataIndex {
        val props = LinkedHashMap<String, SpringProperty>()
        val hints = HashMap<String, List<ValueHint>>()
        for (root in OrderEnumerator.orderEntries(module).recursively().classesRoots) {
            val file = root.findFileByRelativePath(PATH) ?: continue
            try {
                val json = JsonParser.parseString(String(file.contentsToByteArray(), Charsets.UTF_8)).asJsonObject
                json.getAsJsonArray("properties")?.forEach { el ->
                    val o = el.asJsonObject
                    val name = o.get("name")?.asString ?: return@forEach
                    props.putIfAbsent(
                        name,
                        SpringProperty(
                            name,
                            o.get("type")?.asString,
                            o.get("description")?.asString,
                            o.get("defaultValue")?.let { if (it.isJsonPrimitive) it.asString else it.toString() },
                        ),
                    )
                }
                json.getAsJsonArray("hints")?.forEach { el ->
                    val o = el.asJsonObject
                    val name = o.get("name")?.asString ?: return@forEach
                    if (name.endsWith(".keys") || name.endsWith(".values")) return@forEach
                    val values = o.getAsJsonArray("values")?.mapNotNull { v ->
                        val vo = v.asJsonObject
                        vo.get("value")?.let { ValueHint(it.asString, vo.get("description")?.asString) }
                    }.orEmpty()
                    if (values.isNotEmpty()) hints.putIfAbsent(name, values)
                }
            } catch (_: Exception) {
                // malformed metadata in a dependency must not break completion
            }
        }
        return MetadataIndex(props, hints)
    }
}
