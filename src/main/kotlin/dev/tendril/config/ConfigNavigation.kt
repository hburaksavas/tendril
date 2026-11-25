package dev.tendril.config

import com.intellij.lang.properties.parsing.PropertiesTokenTypes
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

object ModuleContext {
    /** [module] plus everything it depends on, transitively (what is on its classpath). */
    fun withDependencies(module: Module): Set<Module> {
        val seen = LinkedHashSet<Module>()
        val queue = ArrayDeque(listOf(module))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (seen.add(current)) queue.addAll(ModuleRootManager.getInstance(current).dependencies)
        }
        return seen
    }

    /** [module] plus every module that depends on it, transitively (the applications that include it). */
    fun withDependents(module: Module): Set<Module> =
        LinkedHashSet<Module>().apply {
            add(module)
            addAll(ModuleUtilCore.getAllDependentModules(module))
        }
}

/** Helpers shared by the yml/properties <-> code navigation features. */
object ConfigNavigation {

    /** Full dotted key if [element] is the key token of a property in application*.properties / .yml, else null. */
    fun keyOf(element: PsiElement): String? {
        val parent = element.parent ?: return null
        return when {
            parent is YAMLKeyValue && element === parent.key ->
                if (SpringFiles.isAppConfig(element.containingFile.name)) yamlPath(parent) else null
            parent is Property && element.node?.elementType == PropertiesTokenTypes.KEY_CHARACTERS ->
                if (SpringFiles.isAppConfig(element.containingFile.name)) parent.unescapedKey else null
            else -> null
        }
    }

    fun yamlPath(kv: YAMLKeyValue): String {
        val segments = ArrayDeque<String>()
        var current: YAMLKeyValue? = kv
        while (current != null) {
            segments.addFirst(current.keyText)
            current = PsiTreeUtil.getParentOfType(current, YAMLKeyValue::class.java, true)
        }
        return segments.joinToString(".")
    }

    /**
     * Java elements (fields, parameters, classes) that consume [key], as seen from the config file element [from].
     * A yml belongs to the application module that puts its dependencies on the classpath, so only code in that
     * module and its (transitive) dependencies is relevant; the same module is listed first.
     */
    fun codeTargets(project: Project, key: String, from: PsiElement): List<PsiElement> {
        val all = ConfigMetadata.ownScan(project).byKey[MetadataIndex.normalize(key)].orEmpty().distinct()
        val origin = ModuleUtilCore.findModuleForPsiElement(from) ?: return all
        return prioritize(all, origin, ModuleContext.withDependencies(origin))
    }

    /**
     * Key elements ([YAMLKeyValue] / [Property]) for [key], as seen from the Java element [from]. Code in a module is
     * configured by the yml of that module or of any application module that depends on it.
     */
    fun configElements(project: Project, key: String, from: PsiElement): List<PsiElement> =
        scopeOf(from)(keyIndex(project)[MetadataIndex.normalize(key)].orEmpty())

    /**
     * Narrows config elements to those that configure [from]'s module, as [configElements] does; keeps everything when
     * [from] is null, has no module, or no element is in a related module. Compute once and apply to many keys.
     */
    fun scopeOf(from: PsiElement?): (List<PsiElement>) -> List<PsiElement> {
        val origin = from?.let { ModuleUtilCore.findModuleForPsiElement(it) } ?: return { it }
        val allowed = ModuleContext.withDependents(origin)
        return { prioritize(it, origin, allowed) }
    }

    /** All config-file elements for [key] regardless of module. */
    fun allConfigElements(project: Project, key: String): List<PsiElement> =
        keyIndex(project)[MetadataIndex.normalize(key)].orEmpty()

    /** Every config-file key (relaxed-normalized) with its elements, across all modules. */
    fun allEntries(project: Project): Map<String, List<PsiElement>> = keyIndex(project)

    /** Keeps targets inside [allowed] modules, same module first; falls back to everything if none qualify. */
    private fun prioritize(all: List<PsiElement>, origin: Module, allowed: Set<Module>): List<PsiElement> {
        val scoped = all.filter { ModuleUtilCore.findModuleForPsiElement(it)?.let { m -> m in allowed } == true }
        if (scoped.isEmpty()) return all
        return scoped.sortedBy { if (ModuleUtilCore.findModuleForPsiElement(it) == origin) 0 else 1 }
    }

    private fun keyIndex(project: Project): Map<String, List<PsiElement>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            // The index only reads yml / properties files, so Java edits must not rebuild it.
            val tracker = PsiModificationTracker.getInstance(project)
            CachedValueProvider.Result.create(
                buildKeyIndex(project),
                tracker.forLanguage(org.jetbrains.yaml.YAMLLanguage.INSTANCE),
                tracker.forLanguage(com.intellij.lang.properties.PropertiesLanguage.INSTANCE),
                ProjectRootModificationTracker.getInstance(project),
            )
        }

    private fun buildKeyIndex(project: Project): Map<String, List<PsiElement>> {
        val psiManager = PsiManager.getInstance(project)
        val index = LinkedHashMap<String, MutableList<PsiElement>>()

        for (vf in SpringFiles.appConfigFiles(project)) {
            when (val psi = psiManager.findFile(vf)) {
                is YAMLFile -> PsiTreeUtil.findChildrenOfType(psi, YAMLKeyValue::class.java).forEach {
                    index.getOrPut(MetadataIndex.normalize(yamlPath(it))) { mutableListOf() }.add(it)
                }
                is PropertiesFile -> psi.properties.forEach { p ->
                    val key = p.unescapedKey ?: return@forEach
                    index.getOrPut(MetadataIndex.normalize(key)) { mutableListOf() }.add(p.psiElement)
                }
            }
        }
        return index
    }
}
