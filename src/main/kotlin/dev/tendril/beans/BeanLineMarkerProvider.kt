package dev.tendril.beans

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.*
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch

/** Gutter icon on injection points (@Autowired/@Inject fields, constructor parameters) -> bean definitions. */
class BeanLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun getName() = "Spring bean injection"

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        if (element !is PsiIdentifier) return
        val type = when (val parent = element.parent) {
            is PsiField -> if (AnnotationUtil.isAnnotated(parent, INJECT_ANNOTATIONS, 0)) parent.type else null
            is PsiParameter -> if (isBeanConstructorParam(parent)) parent.type else null
            else -> null
        } as? PsiClassType ?: return

        val cls = type.resolve() ?: return
        if (cls.qualifiedName?.startsWith("java.") == true) return

        // Cached per class so repeated injection points of the same type don't redo the search;
        // no icon when no bean definition exists.
        val beans = CachedValuesManager.getCachedValue(cls) {
            CachedValueProvider.Result.create(findBeans(cls), PsiModificationTracker.MODIFICATION_COUNT)
        }
        if (beans.isEmpty()) return

        val builder = NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
            .setTargets(beans)
            .setTooltipText("Navigate to Spring bean")
            .setPopupTitle("Spring beans of type ${cls.name}")
        result.add(builder.createLineMarkerInfo(element))
    }

    private fun isBeanConstructorParam(p: PsiParameter): Boolean {
        val method = p.declarationScope as? PsiMethod ?: return false
        if (!method.isConstructor) return false
        val owner = method.containingClass ?: return false
        return isMetaAnnotated(owner, STEREOTYPES, HashSet())
    }

    /** True if [owner] carries one of [names] directly or through a meta-annotation (@Service -> @Component). */
    private fun isMetaAnnotated(owner: PsiModifierListOwner, names: Collection<String>, visited: MutableSet<String>): Boolean {
        for (a in owner.modifierList?.annotations.orEmpty()) {
            val fqn = a.qualifiedName ?: continue
            if (fqn in names) return true
            if (!visited.add(fqn)) continue
            val annotationClass = a.nameReferenceElement?.resolve() as? PsiClass ?: continue
            if (isMetaAnnotated(annotationClass, names, visited)) return true
        }
        return false
    }

    private fun findBeans(type: PsiClass): List<PsiElement> {
        val project = type.project
        val scope = GlobalSearchScope.projectScope(project)
        val beans = mutableListOf<PsiElement>()

        val candidates = ClassInheritorsSearch.search(type, scope, true).findAll() + type
        candidates.filterTo(beans) {
            !it.isInterface && isMetaAnnotated(it, STEREOTYPES, HashSet())
        }

        val beanAnn = JavaPsiFacade.getInstance(project).findClass(BEAN, GlobalSearchScope.allScope(project))
        if (beanAnn != null) {
            AnnotatedElementsSearch.searchPsiMethods(beanAnn, scope).findAll().filterTo(beans) { m ->
                val rt = (m.returnType as? PsiClassType)?.resolve()
                rt != null && (rt == type || rt.isInheritor(type, true))
            }
        }
        return beans
    }

    private companion object {
        const val BEAN = "org.springframework.context.annotation.Bean"
        val INJECT_ANNOTATIONS = listOf(
            "org.springframework.beans.factory.annotation.Autowired",
            "jakarta.inject.Inject", "javax.inject.Inject",
            "jakarta.annotation.Resource", "javax.annotation.Resource",
        )
        val STEREOTYPES = listOf(
            "org.springframework.stereotype.Component",
            "org.springframework.context.annotation.Configuration",
        )
    }
}
