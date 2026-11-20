package dev.tendril.beans

import com.intellij.codeInsight.AnnotationUtil
import dev.tendril.config.firstNonBlank
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

object SpringNames {
    const val COMPONENT = "org.springframework.stereotype.Component"
    const val BEAN = "org.springframework.context.annotation.Bean"
    const val AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired"
    const val QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier"
    const val PRIMARY = "org.springframework.context.annotation.Primary"
    const val LAZY = "org.springframework.context.annotation.Lazy"
    const val PROFILE = "org.springframework.context.annotation.Profile"
    const val CONDITIONAL = "org.springframework.context.annotation.Conditional"
    const val CONDITIONAL_BOOT_PREFIX = "org.springframework.boot.autoconfigure.condition.Conditional"
    const val CONFIGURATION_PROPERTIES = "org.springframework.boot.context.properties.ConfigurationProperties"
    const val DATA_REPOSITORY = "org.springframework.data.repository.Repository"
    const val NO_REPOSITORY_BEAN = "org.springframework.data.repository.NoRepositoryBean"
    const val REPOSITORY_DEFINITION = "org.springframework.data.repository.RepositoryDefinition"
    const val MYBATIS_MAPPER = "org.apache.ibatis.annotations.Mapper"
    const val FEIGN_CLIENT = "org.springframework.cloud.openfeign.FeignClient"
    const val LOMBOK_REQUIRED = "lombok.RequiredArgsConstructor"
    const val LOMBOK_ALL = "lombok.AllArgsConstructor"

    val FIELD_INJECT = listOf(AUTOWIRED, "jakarta.inject.Inject", "javax.inject.Inject")
    val RESOURCE = listOf("jakarta.annotation.Resource", "javax.annotation.Resource")
    val NAMED = listOf("jakarta.inject.Named", "javax.inject.Named")
    val WRAPPER_TYPES = setOf(
        "org.springframework.beans.factory.ObjectProvider",
        "org.springframework.beans.factory.ObjectFactory",
        "jakarta.inject.Provider", "javax.inject.Provider",
    )
}

/** A Spring bean the project defines: a stereotype class, a `@Bean` method, or a proxied interface. */
class BeanDef(
    val name: String,
    val type: PsiClass,
    /** The [PsiClass] or [PsiMethod] that defines the bean. */
    val element: PsiElement,
    /** `@Service`, `@Bean`, `Repository`, ... */
    val label: String,
    val primary: Boolean,
    /** `@Profile` / `@Conditional*`: may legitimately coexist with other beans of the same type. */
    val conditional: Boolean,
)

enum class InjectionKind { FIELD, CONSTRUCTOR, BEAN_METHOD }

/** Where a bean dependency is requested: an injected field, or a constructor / `@Bean` method parameter. */
class InjectionPoint(
    val element: PsiVariable,
    val name: String,
    val target: PsiClass,
    val owner: BeanDef,
    val kind: InjectionKind,
    val qualified: Boolean,
    val lazy: Boolean,
    val optional: Boolean,
    /** True for @Autowired / @Inject (as opposed to @Resource or implicit constructor injection). */
    val viaAutowiredOrInject: Boolean,
)

class BeanModel(val beans: List<BeanDef>, val points: List<InjectionPoint>) {
    val pointByElement: Map<PsiVariable, InjectionPoint> = points.associateBy { it.element }

    private val candidateCache = HashMap<PsiClass, List<BeanDef>>()

    /** Beans whose type is [target] or a subtype of it. Call inside a read action. */
    fun candidates(target: PsiClass): List<BeanDef> = synchronized(candidateCache) {
        candidateCache.getOrPut(target) { beans.filter { it.type == target || it.type.isInheritor(target, true) } }
    }

    /** Bean definition element -> injection points that may receive it. */
    val injectedInto: Map<PsiElement, List<InjectionPoint>> by lazy {
        val map = HashMap<PsiElement, MutableList<InjectionPoint>>()
        for (p in points) for (c in candidates(p.target)) map.getOrPut(c.element) { mutableListOf() }.add(p)
        map
    }

    /** Unambiguous, eager dependencies between beans (what a circular reference needs). */
    private val graph: Map<BeanDef, List<BeanDef>> by lazy {
        val g = HashMap<BeanDef, MutableList<BeanDef>>()
        for (p in points) {
            if (p.lazy) continue
            val c = candidates(p.target).singleOrNull() ?: continue
            g.getOrPut(p.owner) { mutableListOf() }.add(c)
        }
        g
    }

    /** "A → B → A" if [point] closes a dependency cycle, else null. */
    fun cycleDescription(point: InjectionPoint): String? {
        if (point.lazy) return null
        val first = candidates(point.target).singleOrNull() ?: return null
        val path = shortestPath(first, point.owner) ?: return null
        return (listOf(point.owner) + path).joinToString(" → ") { it.name }
    }

    private fun shortestPath(from: BeanDef, to: BeanDef): List<BeanDef>? {
        val previous = HashMap<BeanDef, BeanDef?>()
        previous[from] = null
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current === to) {
                return generateSequence(current) { previous[it] }.toList().reversed()
            }
            for (next in graph[current].orEmpty()) {
                if (next !in previous) {
                    previous[next] = current
                    queue.add(next)
                }
            }
        }
        return null
    }
}

object BeanModels {
    /** Cached per project; rebuilt when Java code changes. Call inside a read action. */
    fun get(project: Project): BeanModel =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                BeanModelBuilder.build(project),
                PsiModificationTracker.getInstance(project).forLanguage(JavaLanguage.INSTANCE),
            )
        }
}

private object BeanModelBuilder {

    fun build(project: Project): BeanModel {
        val facade = JavaPsiFacade.getInstance(project)
        val all = GlobalSearchScope.allScope(project)
        val scope = GlobalSearchScope.projectScope(project)
        val beans = LinkedHashMap<PsiElement, BeanDef>()

        val component = facade.findClass(SpringNames.COMPONENT, all)
            ?: return BeanModel(emptyList(), emptyList())

        // Stereotypes: @Component plus every annotation meta-annotated with it (@Service, custom ones, ...).
        val stereotypes = LinkedHashMap<String, PsiClass>()
        stereotypes[SpringNames.COMPONENT] = component
        val queue = ArrayDeque(listOf(component))
        while (queue.isNotEmpty()) {
            for (c in AnnotatedElementsSearch.searchPsiClasses(queue.removeFirst(), all).findAll()) {
                val q = c.qualifiedName ?: continue
                if (c.isAnnotationType && stereotypes.putIfAbsent(q, c) == null) queue.add(c)
            }
        }

        for ((fqn, annotationClass) in stereotypes) {
            for (cls in AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll()) {
                if (cls.isAnnotationType || cls.isEnum || cls.hasModifierProperty(PsiModifier.ABSTRACT) && !cls.isInterface) continue
                val label = "@" + annotationClass.name
                val explicit = cls.getAnnotation(fqn)?.let { AnnotationUtil.getStringAttributeValue(it, "value") }
                beans.putIfAbsent(cls, def(cls, explicit, cls, label))
            }
        }

        // @ConfigurationProperties classes are registered by Boot, not by a stereotype.
        facade.findClass(SpringNames.CONFIGURATION_PROPERTIES, all)?.let { ann ->
            for (cls in AnnotatedElementsSearch.searchPsiClasses(ann, scope).findAll()) {
                if (!cls.isAnnotationType) beans.putIfAbsent(cls, def(cls, null, cls, "@ConfigurationProperties"))
            }
        }

        // Interfaces implemented by a framework proxy: Spring Data repositories, MyBatis mappers, Feign clients.
        facade.findClass(SpringNames.DATA_REPOSITORY, all)?.let { repo ->
            for (cls in ClassInheritorsSearch.search(repo, scope, true).findAll()) {
                // @NoRepositoryBean marks shared base interfaces that Spring Data never instantiates
                if (cls.isInterface && cls.getAnnotation(SpringNames.NO_REPOSITORY_BEAN) == null) {
                    beans.putIfAbsent(cls, def(cls, null, cls, "Repository"))
                }
            }
        }
        facade.findClass(SpringNames.REPOSITORY_DEFINITION, all)?.let { ann ->
            for (cls in AnnotatedElementsSearch.searchPsiClasses(ann, scope).findAll()) {
                if (cls.isInterface) beans.putIfAbsent(cls, def(cls, null, cls, "Repository"))
            }
        }
        for ((fqn, label) in listOf(SpringNames.MYBATIS_MAPPER to "@Mapper", SpringNames.FEIGN_CLIENT to "@FeignClient")) {
            val ann = facade.findClass(fqn, all) ?: continue
            for (cls in AnnotatedElementsSearch.searchPsiClasses(ann, scope).findAll()) {
                if (cls.isInterface) beans.putIfAbsent(cls, def(cls, null, cls, label))
            }
        }

        // @Bean methods
        val beanMethods = facade.findClass(SpringNames.BEAN, all)?.let {
            AnnotatedElementsSearch.searchPsiMethods(it, scope).findAll()
        }.orEmpty()
        val methodBeans = mutableListOf<Pair<PsiMethod, BeanDef>>()
        for (m in beanMethods) {
            val type = (m.returnType as? PsiClassType)?.resolve() ?: continue
            val explicit = m.getAnnotation(SpringNames.BEAN)?.firstNonBlank("name", "value")
            val bean = BeanDef(
                explicit ?: m.name, type, m, "@Bean",
                m.getAnnotation(SpringNames.PRIMARY) != null, isConditional(m),
            )
            beans[m] = bean
            methodBeans += m to bean
        }

        // Injection points
        val points = mutableListOf<InjectionPoint>()
        for (bean in beans.values) {
            val cls = bean.element as? PsiClass ?: continue
            if (cls.isInterface) continue
            collectClassPoints(cls, bean, points)
        }
        for ((method, bean) in methodBeans) {
            for (param in method.parameterList.parameters) {
                point(param, bean, InjectionKind.BEAN_METHOD, viaAutowired = false)?.let { points += it }
            }
        }
        return BeanModel(beans.values.toList(), points)
    }

    private fun def(cls: PsiClass, explicitName: String?, element: PsiElement, label: String) =
        BeanDef(
            explicitName?.takeIf { it.isNotEmpty() } ?: decapitalize(cls.name ?: "?"),
            cls, element, label,
            cls.getAnnotation(SpringNames.PRIMARY) != null, isConditional(cls),
        )

    private fun collectClassPoints(cls: PsiClass, bean: BeanDef, out: MutableList<InjectionPoint>) {
        for (field in cls.fields) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue
            val viaAutowired = SpringNames.FIELD_INJECT.any { field.getAnnotation(it) != null }
            val viaResource = SpringNames.RESOURCE.any { field.getAnnotation(it) != null }
            if (viaAutowired || viaResource) {
                point(field, bean, InjectionKind.FIELD, viaAutowired)?.let { out += it }
            }
        }

        val ctors = cls.constructors
        val chosen = ctors.firstOrNull { it.getAnnotation(SpringNames.AUTOWIRED) != null } ?: ctors.singleOrNull()
        if (chosen != null) {
            for (param in chosen.parameterList.parameters) {
                point(param, bean, InjectionKind.CONSTRUCTOR, viaAutowired = false)?.let { out += it }
            }
        } else if (ctors.isEmpty()) {
            // Lombok-generated constructor: final fields (@RequiredArgsConstructor) or all fields (@AllArgsConstructor)
            val all = cls.getAnnotation(SpringNames.LOMBOK_ALL) != null
            val required = cls.getAnnotation(SpringNames.LOMBOK_REQUIRED) != null
            if (all || required) {
                for (field in cls.fields) {
                    if (field.hasModifierProperty(PsiModifier.STATIC) || field.initializer != null) continue
                    if (required && !all && !field.hasModifierProperty(PsiModifier.FINAL)) continue
                    point(field, bean, InjectionKind.CONSTRUCTOR, viaAutowired = false)?.let { out += it }
                }
            }
        }
    }

    private fun point(v: PsiVariable, owner: BeanDef, kind: InjectionKind, viaAutowired: Boolean): InjectionPoint? {
        val target = (v.type as? PsiClassType)?.resolve() ?: return null
        val q = target.qualifiedName ?: return null
        if (target is PsiTypeParameter || q.startsWith("java.") || q in SpringNames.WRAPPER_TYPES) return null

        val autowired = v.getAnnotation(SpringNames.AUTOWIRED)
        val required = autowired?.let { AnnotationUtil.getBooleanAttributeValue(it, "required") } ?: true
        val resourceByName = SpringNames.RESOURCE.any { r ->
            v.getAnnotation(r)?.let { AnnotationUtil.getStringAttributeValue(it, "name") } != null
        }
        val qualified = v.getAnnotation(SpringNames.QUALIFIER) != null || resourceByName ||
            SpringNames.NAMED.any { v.getAnnotation(it) != null }
        val nullable = v.modifierList?.annotations.orEmpty().any { it.nameReferenceElement?.referenceName == "Nullable" }

        return InjectionPoint(
            v, v.name ?: return null, target, owner, kind, qualified,
            lazy = v.getAnnotation(SpringNames.LAZY) != null,
            optional = !required || nullable,
            viaAutowiredOrInject = viaAutowired,
        )
    }

    private fun isConditional(owner: PsiModifierListOwner): Boolean =
        owner.modifierList?.annotations.orEmpty().any {
            val q = it.qualifiedName ?: return@any false
            q == SpringNames.PROFILE || q == SpringNames.CONDITIONAL || q.startsWith(SpringNames.CONDITIONAL_BOOT_PREFIX)
        }

    /** java.beans.Introspector.decapitalize: "URLService" stays, "UserService" -> "userService". */
    private fun decapitalize(name: String): String =
        if (name.length > 1 && name[0].isUpperCase() && name[1].isUpperCase()) name
        else name.replaceFirstChar { it.lowercase() }
}
