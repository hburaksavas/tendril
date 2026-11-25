package dev.tendril.config

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch

/** A place in Java code that consumes configuration key [key]: a field, parameter, method or class. */
class ConfigSource(val key: String, val element: PsiElement)

class ProjectConfigScan(val properties: List<SpringProperty>, val sources: List<ConfigSource>) {
    /** Relaxed-normalized key -> code elements that consume it. */
    val byKey: Map<String, List<PsiElement>> =
        sources.groupBy({ MetadataIndex.normalize(it.key) }, { it.element })

    /** Code element -> keys it consumes. */
    val keysByElement: Map<PsiElement, List<String>> = sources.groupBy({ it.element }, { it.key })
}

/**
 * Properties defined by the project's own code, for projects without the configuration annotation processor:
 *  - fields of `@ConfigurationProperties` classes (nested project types are followed)
 *  - keys referenced from `@Value("${...}")`
 * Must be called inside a read action.
 */
object ProjectConfigProperties {
    const val VALUE = "org.springframework.beans.factory.annotation.Value"
    private const val CONFIGURATION_PROPERTIES = "org.springframework.boot.context.properties.ConfigurationProperties"
    val PLACEHOLDER = Regex("""\$\{\s*([^}:\s]+)""")

    val FEIGN_CLIENT = listOf(
        "org.springframework.cloud.openfeign.FeignClient",
        "org.springframework.cloud.netflix.feign.FeignClient",
    )

    /** Annotations whose string attributes may contain `${config.key}` placeholders. */
    val PLACEHOLDER_ANNOTATIONS: Set<String> = setOf(VALUE) + FEIGN_CLIENT
    private const val MAX_DEPTH = 4

    fun scan(project: Project): ProjectConfigScan {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val all = GlobalSearchScope.allScope(project)
        val props = LinkedHashMap<String, SpringProperty>()
        val sources = mutableListOf<ConfigSource>()

        facade.findClass(CONFIGURATION_PROPERTIES, all)?.let { ann ->
            for (cls in AnnotatedElementsSearch.searchPsiClasses(ann, scope).findAll()) {
                val prefix = prefixOf(cls.getAnnotation(CONFIGURATION_PROPERTIES))
                if (prefix.isNotEmpty()) sources += ConfigSource(prefix, cls)
                collect(cls, prefix, props, sources, 0, HashSet())
            }
            for (method in AnnotatedElementsSearch.searchPsiMethods(ann, scope).findAll()) {
                val cls = (method.returnType as? PsiClassType)?.resolve() ?: continue
                val prefix = prefixOf(method.getAnnotation(CONFIGURATION_PROPERTIES))
                if (prefix.isNotEmpty()) sources += ConfigSource(prefix, method)
                collect(cls, prefix, props, sources, 0, HashSet())
            }
        }

        facade.findClass(VALUE, all)?.let { ann ->
            val elements = AnnotatedElementsSearch.searchElements(
                ann, scope, PsiField::class.java, PsiParameter::class.java, PsiMethod::class.java,
            ).findAll()
            for (element in elements) {
                val text = AnnotationUtil.getStringAttributeValue(element.getAnnotation(VALUE) ?: continue, "value")
                    ?: continue
                val type = (element as? PsiVariable)?.type?.canonicalText
                for (m in PLACEHOLDER.findAll(text)) {
                    val key = m.groupValues[1]
                    props.putIfAbsent(key, SpringProperty(key, type, "Referenced via @Value", null))
                    sources += ConfigSource(key, element)
                }
            }
        }
        scanFeign(project, props, sources)
        return ProjectConfigScan(props.values.toList(), sources)
    }

    /**
     * `@FeignClient`: placeholders in url / name / path are consumed config keys; a literal service name is
     * consumed through the well-known address keys ([dev.tendril.feign.FeignResolver.NAME_KEYS]).
     */
    private fun scanFeign(project: Project, props: MutableMap<String, SpringProperty>, sources: MutableList<ConfigSource>) {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        for (fqn in FEIGN_CLIENT) {
            val ann = facade.findClass(fqn, GlobalSearchScope.allScope(project)) ?: continue
            for (cls in AnnotatedElementsSearch.searchPsiClasses(ann, scope).findAll()) {
                val a = cls.getAnnotation(fqn) ?: continue
                for (attr in listOf("url", "name", "value", "serviceId", "path")) {
                    for (text in dev.tendril.endpoints.EndpointScanner.attrValues(a, attr)) {
                        for (m in PLACEHOLDER.findAll(text)) {
                            val key = m.groupValues[1]
                            props.putIfAbsent(key, SpringProperty(key, "java.lang.String", "Referenced via @FeignClient", null))
                            sources += ConfigSource(key, cls)
                        }
                    }
                }
                val name = listOf("name", "value", "serviceId").firstNotNullOfOrNull { attr ->
                    dev.tendril.endpoints.EndpointScanner.attrValues(a, attr).firstOrNull()
                        ?.takeIf { it.isNotBlank() && '$' !in it }
                } ?: continue
                for (key in dev.tendril.feign.FeignResolver.addressKeys(project, name)) sources += ConfigSource(key, cls)
            }
        }
    }

    private fun prefixOf(annotation: PsiAnnotation?): String =
        annotation?.firstNonBlank("prefix", "value").orEmpty()

    private fun collect(
        cls: PsiClass,
        prefix: String,
        props: MutableMap<String, SpringProperty>,
        sources: MutableList<ConfigSource>,
        depth: Int,
        visiting: MutableSet<String>,
    ) {
        val qName = cls.qualifiedName ?: return
        if (depth > MAX_DEPTH || !visiting.add(qName)) return
        for (field in cls.allFields) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue
            if (field.containingClass?.qualifiedName?.startsWith("java.") == true) continue
            val name = listOf(prefix, kebab(field.name)).filter { it.isNotEmpty() }.joinToString(".")
            sources += ConfigSource(name, field)
            val target = (field.type as? PsiClassType)?.resolve()
            if (target != null && isNestedConfig(target)) {
                collect(target, name, props, sources, depth + 1, visiting)
            } else {
                props.putIfAbsent(name, SpringProperty(name, field.type.canonicalText, "From ${cls.name}", null))
            }
        }
        visiting.remove(qName)
    }

    /** A class from the project's own sources that groups further properties (not an enum or interface). */
    private fun isNestedConfig(cls: PsiClass): Boolean {
        if (cls.isEnum || cls.isInterface || cls.isAnnotationType) return false
        val file = cls.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(cls.project).isInSourceContent(file)
    }

    private val CAMEL_HUMP = Regex("([a-z0-9])([A-Z])")

    private fun kebab(camel: String) = camel.replace(CAMEL_HUMP, "$1-$2").lowercase()
}
