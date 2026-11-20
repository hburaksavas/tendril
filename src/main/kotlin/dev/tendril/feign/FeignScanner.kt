package dev.tendril.feign

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.tendril.endpoints.EndpointScanner

/** One outgoing call: a mapped method of a Feign client interface. [subPath] is relative to the client's `path`. */
class FeignCall(
    val httpMethod: String,
    val subPath: String,
    val methodName: String,
    val method: SmartPsiElementPointer<PsiMethod>,
)

/** A `@FeignClient` interface with its raw (unresolved) annotation attributes. */
class FeignClientDef(
    val className: String,
    val name: String,
    val url: String,
    val path: String,
    val calls: List<FeignCall>,
    val cls: SmartPsiElementPointer<PsiClass>,
)

object FeignScanner {
    private val ANNOTATIONS = listOf(
        "org.springframework.cloud.openfeign.FeignClient",
        "org.springframework.cloud.netflix.feign.FeignClient",
    )

    /** Cached per project, rebuilt when Java code changes. Must be called inside a read action. */
    fun cached(project: Project): List<FeignClientDef> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                scan(project),
                PsiModificationTracker.getInstance(project).forLanguage(JavaLanguage.INSTANCE),
            )
        }

    fun scan(project: Project): List<FeignClientDef> {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        return ANNOTATIONS.mapNotNull { facade.findClass(it, GlobalSearchScope.allScope(project)) }
            .flatMap { AnnotatedElementsSearch.searchPsiClasses(it, scope).findAll() }
            .distinct()
            .mapNotNull { defOf(it) }
            .sortedBy { it.className }
    }

    /** The client definition of [cls], or null when it is not a Feign client. Needs a read action. */
    fun defOf(cls: PsiClass): FeignClientDef? {
        val ann = ANNOTATIONS.firstNotNullOfOrNull { cls.getAnnotation(it) } ?: return null
        fun attr(vararg names: String): String =
            names.firstNotNullOfOrNull { EndpointScanner.attrValues(ann, it).firstOrNull()?.takeIf { s -> s.isNotBlank() } }
                .orEmpty()

        val pointers = SmartPointerManager.getInstance(cls.project)
        val calls = mutableListOf<FeignCall>()
        for (m in cls.allMethods) {
            if (m.containingClass?.qualifiedName == "java.lang.Object") continue
            for ((verbs, subPaths) in EndpointScanner.mappingsOf(m)) {
                for (sub in subPaths.ifEmpty { listOf("") }) for (verb in verbs) {
                    calls += FeignCall(verb, sub, m.name, pointers.createSmartPsiElementPointer(m))
                }
            }
        }
        return FeignClientDef(
            className = cls.name ?: "?",
            name = attr("name", "value", "serviceId", "contextId"),
            url = attr("url"),
            path = attr("path"),
            calls = calls.sortedWith(compareBy({ it.subPath }, { it.httpMethod })),
            cls = pointers.createSmartPsiElementPointer(cls),
        )
    }
}
