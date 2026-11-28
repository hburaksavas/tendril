package dev.tendril.batch

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastVisitor

internal object BatchJobs {
    private const val CORE = "org.springframework.batch.core."
    private const val JOB = CORE + "Job"
    private const val STEP = CORE + "Step"
    private val stepCalls = setOf("start", "next", "from", "to")
    private val componentCalls = setOf("reader", "processor", "writer", "tasklet")

    data class Entry(
        val label: String,
        val target: SmartPsiElementPointer<PsiElement>,
        val children: List<Entry> = emptyList(),
        val role: String? = null,
    ) {
        override fun toString() = label
    }

    fun isPresent(project: Project): Boolean {
        val facade = JavaPsiFacade.getInstance(project)
        return ModuleManager.getInstance(project).modules.any { module ->
            val scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false)
            facade.findClass(JOB, scope) != null && facade.findClass(STEP, scope) != null
        }
    }

    fun scan(project: Project): Pair<List<Entry>, List<Entry>> {
        val bean = JavaPsiFacade.getInstance(project)
            .findClass("org.springframework.context.annotation.Bean", GlobalSearchScope.allScope(project))
            ?: return emptyList<Entry>() to emptyList()
        val fileIndex = ProjectFileIndex.getInstance(project)
        val methods = AnnotatedElementsSearch.searchPsiMethods(bean, GlobalSearchScope.projectScope(project))
            .findAll().filter { method ->
                method.containingFile?.virtualFile?.let { !fileIndex.isInTestSourceContent(it) } == true
            }.distinct().sortedBy { it.name }
        val steps = methods.filter { InheritanceUtil.isInheritor(it.returnType, STEP) }
        val jobs = methods.filter { InheritanceUtil.isInheritor(it.returnType, JOB) }
        val stepEntries = steps.associateWith { method ->
            entry(method.name, method, builderArguments(method, componentCalls, CORE + "step.builder.")
                .map { (role, argument) ->
                    val target = resolve(argument, methods)
                    entry(target?.name ?: argument.asSourceString(), target ?: argument.sourcePsi ?: method)
                        .copy(role = role.replaceFirstChar { it.uppercaseChar() })
                }.distinct())
        }
        val jobEntries = jobs.map { method ->
            val children = builderArguments(method, stepCalls, CORE + "job.builder.")
                .mapNotNull { (_, argument) -> stepEntries[resolve(argument, steps)] }.distinct()
            entry(method.name, method, children)
        }
        val linkedSteps = jobEntries.flatMapTo(hashSetOf()) { it.children }
        return jobEntries to stepEntries.values.filterNot { it in linkedSteps }
    }

    private fun builderArguments(method: PsiMethod, names: Set<String>, builderPackage: String):
        List<Pair<String, UExpression>> {
        ProgressManager.checkCanceled()
        val declaration = method.navigationElement.toUElementOfType<UMethod>() ?: method.toUElementOfType<UMethod>()
        val calls = mutableListOf<Pair<String, UExpression>>()
        declaration?.uastBody?.accept(object : AbstractUastVisitor() {
            override fun afterVisitCallExpression(node: UCallExpression) {
                val name = node.methodName ?: return
                if (name !in names) return
                val owner = node.resolve()?.containingClass?.qualifiedName ?: return
                if (owner.startsWith(builderPackage)) {
                    node.valueArguments.firstOrNull()?.let { calls += name to it }
                }
            }
        })
        return calls
    }

    private fun resolve(expression: UExpression, candidates: List<PsiMethod>): PsiNamedElement? {
        val target = (expression as? UResolvable)?.resolve() as? PsiNamedElement
        val type = expression.getExpressionType()
        if (target is PsiMethod) {
            return candidates.firstOrNull { it == target || it.navigationElement == target.navigationElement } ?: target
        }
        if (target is PsiParameter && type != null) {
            val compatible = candidates.filter { it.returnType?.let(type::isAssignableFrom) == true }
            return compatible.singleOrNull { it.name == target.name } ?: compatible.singleOrNull() ?: target
        }
        return target
    }

    private fun entry(label: String, target: PsiElement, children: List<Entry> = emptyList()) =
        Entry(label, SmartPointerManager.createPointer(target.navigationElement), children)
}
