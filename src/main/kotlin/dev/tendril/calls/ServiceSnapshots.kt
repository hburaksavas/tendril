package dev.tendril.calls

import com.intellij.openapi.editor.Document
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.tendril.CodeStructureTracker
import dev.tendril.endpoints.EndpointScanner
import dev.tendril.feign.FeignResolver
import dev.tendril.feign.FeignScanner
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UCallableReferenceExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/** Builds the [ProjectSnapshot] of an open project from its PSI. */
object ServiceSnapshots {
    /** How many calls deep the search from a handler to the Feign calls goes. */
    private const val MAX_DEPTH = 8

    /** At most this many method bodies are read for one snapshot, so a huge project stays bounded. */
    private const val MAX_METHODS = 20_000

    /**
     * The snapshot of [project], cached until any of its code or config changes (the Feign calls a handler makes
     * depend on method bodies). Needs a read action; may take a while the first time, so call it off the EDT.
     */
    fun live(project: Project): ProjectSnapshot =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                build(project), PsiModificationTracker.getInstance(project), CodeStructureTracker.getInstance(project),
            )
        }

    fun build(project: Project): ProjectSnapshot {
        val apps = mutableListOf<AppInfo>()
        val appByModule = HashMap<String, Int>()
        fun appOf(element: PsiElement?): Int {
            val module = element?.let { ModuleUtilCore.findModuleForPsiElement(it) }
            val key = module?.name ?: ""
            return appByModule.getOrPut(key) {
                fun value(k: String) = FeignResolver.appValue(project, k, element)?.trim()?.takeIf { it.isNotEmpty() }
                apps += AppInfo(
                    name = value("spring.application.name") ?: module?.name ?: project.name,
                    port = value("server.port")?.takeIf { it.all(Char::isDigit) } ?: "8080",
                    contextPath = value("server.servlet.context-path") ?: value("spring.webflux.base-path") ?: "",
                )
                apps.size - 1
            }
        }

        val feign = mutableListOf<FeignNode>()
        val feignByKey = HashMap<String, MutableList<Int>>()
        val profile = FeignResolver.selectedProfile(project)
        for (def in FeignScanner.cached(project)) {
            ProgressManager.checkCanceled()
            val cls = def.cls.element ?: continue
            val info = FeignResolver.resolve(project, def, profile)
            val app = appOf(cls)
            for (call in def.calls) {
                val method = call.method.element ?: continue
                feignByKey.getOrPut(keyOf(method)) { mutableListOf() } += feign.size
                feign += FeignNode(
                    app, def.className, info.name.ifBlank { def.name }, info.resolution.url, dev.tendril.endpoints.EndpointRequests.verbOf(call.httpMethod),
                    CallMatching.join(info.path, call.subPath), "${def.className}.${call.methodName}", locationOf(project, method),
                )
            }
        }

        // without Feign clients there is nothing to find in the method bodies: skip reading them
        val reach = Reach(project, feignByKey).takeIf { feignByKey.isNotEmpty() }
        val endpoints = EndpointScanner.cached(project).mapNotNull { e ->
            ProgressManager.checkCanceled()
            val method = e.method.element ?: return@mapNotNull null
            EndpointNode(
                appOf(method), e.httpMethod, e.path, "${e.className}.${e.methodName}", locationOf(project, method),
                reach?.of(method)?.sorted().orEmpty(),
            )
        }
        return ProjectSnapshot(
            ProjectSnapshot.VERSION, ServiceMapStore.normalise(project.basePath.orEmpty()), project.name,
            System.currentTimeMillis(), apps, endpoints, feign,
        )
    }

    /** The same method seen through Java PSI or a Kotlin light class. */
    internal fun keyOf(m: PsiMethod): String =
        (m.containingClass?.qualifiedName ?: "?") + "#" + m.name + "/" + m.parameterList.parametersCount

    fun locationOf(project: Project, element: PsiElement): Location? {
        val nav = element.navigationElement ?: element
        val file = nav.containingFile ?: return null
        val vf = file.virtualFile ?: return null
        val offset = (nav as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: nav.textOffset
        val document: Document? = PsiDocumentManager.getInstance(project).getDocument(file)
        val line = document?.takeIf { offset <= it.textLength }?.getLineNumber(offset)
            ?: StringUtil.offsetToLineNumber(file.text, offset)
        return Location(vf.path, line)
    }

    /**
     * Which Feign calls a method makes, directly or through the project's own methods it calls (and the
     * implementations of interface methods it calls). Memoised across the handlers of one snapshot.
     */
    private class Reach(val project: Project, val feign: Map<String, List<Int>>) {
        private val memo = HashMap<String, Set<Int>>()
        private val fileIndex = ProjectFileIndex.getInstance(project)
        private val scope = GlobalSearchScope.projectScope(project)
        private var budget = MAX_METHODS

        fun of(method: PsiMethod): Set<Int> = visit(method, 0, HashSet())

        private fun visit(method: PsiMethod, depth: Int, stack: MutableSet<String>): Set<Int> {
            val key = keyOf(method)
            memo[key]?.let { return it }
            if (!stack.add(key) || budget-- <= 0) return emptySet()
            ProgressManager.checkCanceled()
            val found = HashSet<Int>()
            val body = (method.navigationElement ?: method).toUElementOfType<UMethod>() ?: method.toUElementOfType<UMethod>()
            body?.accept(object : AbstractUastVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    (node.resolve())?.let { found += target(it, depth, stack) }
                    return false
                }

                override fun visitCallableReferenceExpression(node: UCallableReferenceExpression): Boolean {
                    (node.resolve() as? PsiMethod)?.let { found += target(it, depth, stack) }
                    return false
                }
            })
            stack.remove(key)
            memo[key] = found
            return found
        }

        private fun target(m: PsiMethod, depth: Int, stack: MutableSet<String>): Set<Int> {
            feign[keyOf(m)]?.let { return it.toSet() }
            if (depth >= MAX_DEPTH || !isOwnCode(m)) return emptySet()
            val cls = m.containingClass ?: return emptySet()
            if (FeignScanner.isClient(cls)) return emptySet()
            val out = HashSet<Int>()
            if (!m.hasModifierProperty(PsiModifier.ABSTRACT) && !cls.isInterface) out += visit(m, depth + 1, stack)
            else {
                if (m.body != null || (m.navigationElement as? PsiMethod)?.body != null) out += visit(m, depth + 1, stack)
                // a service interface: follow its implementations in the project
                OverridingMethodsSearch.search(m, scope, true).findAll().take(MAX_IMPLEMENTATIONS)
                    .forEach { out += visit(it, depth + 1, stack) }
            }
            return out
        }

        private fun isOwnCode(m: PsiMethod): Boolean {
            val vf = (m.navigationElement ?: m).containingFile?.virtualFile ?: return false
            return fileIndex.isInSourceContent(vf) && !fileIndex.isInTestSourceContent(vf)
        }
    }

    private const val MAX_IMPLEMENTATIONS = 5
}
