package dev.tendril

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.impl.PsiTreeChangeEventImpl

/**
 * Counts changes to the *declarations* in Java and Kotlin code (classes, members, annotations, signatures), and
 * ignores edits inside method, constructor, initializer and accessor bodies. Typing in a method body is most of what
 * a developer does, and no project-wide model of this plugin (beans, config consumers, endpoints, Feign clients, JPA
 * entities) reads code inside a body. Caching such a model on this tracker instead of on every PSI change keeps it from
 * being rebuilt, with a scan over the whole project, on every keystroke.
 *
 * Also counts project root changes (a library added or removed) and the end of indexing.
 */
@Service(Service.Level.PROJECT)
class CodeStructureTracker(private val project: Project) : ModificationTracker, Disposable {
    private val own = SimpleModificationTracker()

    init {
        PsiManager.getInstance(project).addPsiTreeChangeListener(Listener(), this)
    }

    override fun getModificationCount(): Long =
        own.modificationCount +
            ProjectRootModificationTracker.getInstance(project).modificationCount +
            DumbService.getInstance(project).modificationTracker.modificationCount

    override fun dispose() {}

    private inner class Listener : PsiTreeChangeAdapter() {
        override fun childAdded(event: PsiTreeChangeEvent) = changed(event, event.child)
        override fun childRemoved(event: PsiTreeChangeEvent) = changed(event, event.parent) // the child is detached
        override fun childReplaced(event: PsiTreeChangeEvent) = changed(event, event.newChild)
        override fun childrenChanged(event: PsiTreeChangeEvent) {
            // Every change ends with a generic "the file changed" event. It says nothing about where: when specific
            // events came first they have decided already; only a change reported generically alone counts.
            if ((event as? PsiTreeChangeEventImpl)?.isGenericChange == true) {
                if (!sawSpecific) own.incModificationCount()
                sawSpecific = false
                return
            }
            changed(event, event.parent)
        }
        override fun childMoved(event: PsiTreeChangeEvent) {
            sawSpecific = true
            if (!isCode(event) || !insideBody(event.oldParent) || !insideBody(event.newParent)) own.incModificationCount()
        }
        override fun propertyChanged(event: PsiTreeChangeEvent) = own.incModificationCount()

        /** Events arrive on the EDT inside the write action, one change after the other. */
        private var sawSpecific = false

        private fun changed(event: PsiTreeChangeEvent, at: PsiElement?) {
            sawSpecific = true
            if (!isCode(event) || !insideBody(at)) own.incModificationCount()
        }
    }

    /** A change in a single code file; directory-level and file-level events (no file) count as a change. */
    private fun isCode(event: PsiTreeChangeEvent): Boolean = event.file is PsiClassOwner

    /** True when [element] is, or lies inside, the body of a method, constructor, initializer or accessor. */
    private fun insideBody(element: PsiElement?): Boolean {
        var current = element
        while (current != null && current !is PsiFile) {
            if (isBody(current)) return true
            current = current.parent
        }
        return false
    }

    private fun isBody(e: PsiElement): Boolean {
        if (e is PsiCodeBlock) return true
        // Kotlin, without a compile-time dependency on its PSI: a block that is the body of a function, constructor,
        // init block or accessor. Not a lambda: `@Bean fun f() = run { Foo() }` takes its type from the lambda.
        if (e.node?.elementType?.toString() != "BLOCK") return false
        return e.parent?.node?.elementType?.toString() in KOTLIN_BODY_OWNERS
    }

    companion object {
        private val KOTLIN_BODY_OWNERS = setOf("FUN", "SECONDARY_CONSTRUCTOR", "PROPERTY_ACCESSOR", "CLASS_INITIALIZER")

        fun getInstance(project: Project): CodeStructureTracker = project.service()
    }
}
