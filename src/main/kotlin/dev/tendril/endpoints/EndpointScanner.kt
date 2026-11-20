package dev.tendril.endpoints

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch

data class Endpoint(
    val httpMethod: String,
    val path: String,
    val className: String,
    val method: SmartPsiElementPointer<PsiMethod>,
)

object EndpointScanner {
    private const val PKG = "org.springframework.web.bind.annotation."
    private val CONTROLLERS = listOf(
        PKG + "RestController",
        "org.springframework.stereotype.Controller",
    )
    private const val REQUEST_MAPPING = PKG + "RequestMapping"
    private val SHORTCUTS = mapOf(
        PKG + "GetMapping" to "GET",
        PKG + "PostMapping" to "POST",
        PKG + "PutMapping" to "PUT",
        PKG + "DeleteMapping" to "DELETE",
        PKG + "PatchMapping" to "PATCH",
    )

    /** Must be called inside a read action. */
    fun scan(project: Project): List<Endpoint> {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val pointers = SmartPointerManager.getInstance(project)
        val out = mutableListOf<Endpoint>()

        val classes = CONTROLLERS.mapNotNull { facade.findClass(it, GlobalSearchScope.allScope(project)) }
            .flatMap { AnnotatedElementsSearch.searchPsiClasses(it, scope).findAll() }
            .distinct()

        for (cls in classes) {
            val basePaths = cls.getAnnotation(REQUEST_MAPPING)?.let { paths(it) }.orEmpty().ifEmpty { listOf("") }
            for (m in cls.methods) {
                for ((verbs, subPaths) in mappingsOf(m)) {
                    for (base in basePaths) for (sub in subPaths.ifEmpty { listOf("") }) for (verb in verbs) {
                        out += Endpoint(
                            verb, join(base, sub), cls.name ?: "?",
                            pointers.createSmartPsiElementPointer(m),
                        )
                    }
                }
            }
        }
        return out.sortedWith(compareBy({ it.path }, { it.httpMethod }))
    }

    private fun mappingsOf(m: PsiMethod): List<Pair<List<String>, List<String>>> {
        val res = mutableListOf<Pair<List<String>, List<String>>>()
        for ((fqn, verb) in SHORTCUTS) {
            m.getAnnotation(fqn)?.let { res += listOf(verb) to paths(it) }
        }
        m.getAnnotation(REQUEST_MAPPING)?.let { ann ->
            val verbs = attrValues(ann, "method").map { it.substringAfterLast('.') }.ifEmpty { listOf("ANY") }
            res += verbs to paths(ann)
        }
        return res
    }

    private fun paths(ann: PsiAnnotation): List<String> =
        attrValues(ann, "value").ifEmpty { attrValues(ann, "path") }

    /** Evaluates constant strings; for non-string values (enums) falls back to source text. */
    private fun attrValues(ann: PsiAnnotation, name: String): List<String> {
        val value = ann.findDeclaredAttributeValue(name) ?: return emptyList()
        val helper = JavaPsiFacade.getInstance(ann.project).constantEvaluationHelper
        val items = if (value is PsiArrayInitializerMemberValue) value.initializers.toList() else listOf(value)
        return items.map { item ->
            (item as? PsiExpression)?.let { helper.computeConstantExpression(it) as? String } ?: item.text
        }
    }

    private fun join(a: String, b: String): String {
        val s = ("/" + a.trim('/') + "/" + b.trim('/')).replace(Regex("/+"), "/")
        return if (s.length > 1) s.trimEnd('/') else s
    }
}
