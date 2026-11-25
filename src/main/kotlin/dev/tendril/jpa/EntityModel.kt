package dev.tendril.jpa

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PropertyUtilBase
import com.intellij.psi.util.PsiUtil
import dev.tendril.beans.SpringNames
import java.beans.Introspector
import java.util.concurrent.ConcurrentHashMap

internal object JpaNames {
    private val PACKAGES = listOf("jakarta.persistence", "javax.persistence")
    private fun both(simple: String) = PACKAGES.map { "$it.$simple" }

    val ENTITY = both("Entity")
    val EMBEDDABLE = both("Embeddable")
    val MAPPED_SUPERCLASS = both("MappedSuperclass")
    val ID = both("Id") + both("EmbeddedId")
    val EMBEDDED = both("Embedded") + both("EmbeddedId")
    val TRANSIENT = both("Transient")
}

internal enum class ManagedKind { ENTITY, EMBEDDABLE, MAPPED_SUPERCLASS }

/** A persistent attribute: a field, or a getter for property access. */
internal class Attribute(
    val name: String,
    /** The field or getter; for Kotlin the light element. */
    val element: PsiElement,
    val type: PsiType,
    /** The class the attribute leads to: the element type of a collection, the value type of a map. */
    val targetFqn: String?,
    val collection: Boolean,
    val embedded: Boolean,
    val id: Boolean,
)

/** An entity, embeddable or mapped superclass. Attributes are read on first use (inside a read action). */
internal class ManagedType(val cls: PsiClass, val kind: ManagedKind, val entityName: String?) {
    val fqn: String = cls.qualifiedName.orEmpty()

    /** Own and inherited attributes; a subclass attribute hides the one of its superclass. */
    val attributes: Map<String, Attribute> by lazy { readAttributes(cls) }

    val idAttribute: Attribute? get() = attributes.values.firstOrNull { it.id }

    /** `@Entity(name = "...")` differs from the class name: a rename of the class must not touch the query. */
    val hasExplicitName: Boolean get() = entityName != cls.name
}

internal class EntityModel(private val types: Map<String, ManagedType>) {
    /** False when neither jakarta.persistence nor javax.persistence is on the class path. */
    val available: Boolean get() = types.isNotEmpty()

    val entities: List<ManagedType> = types.values.filter { it.kind == ManagedKind.ENTITY }

    private val byEntityName = entities.associateBy { it.entityName }
    private val bySimpleName = entities.groupBy { it.cls.name }
    private val implied = ConcurrentHashMap<String, ManagedType>()

    /** An entity by the name a query uses: its entity name, its class name, or its qualified name. */
    fun entity(name: String): ManagedType? =
        byEntityName[name] ?: types[name]?.takeIf { it.kind == ManagedKind.ENTITY } ?: bySimpleName[name]?.singleOrNull()

    fun type(fqn: String?): ManagedType? = fqn?.let { types[it] }

    /** The type an attribute leads to; an `@Embedded` class counts even without `@Embeddable`. */
    fun target(attribute: Attribute, project: Project): ManagedType? {
        val fqn = attribute.targetFqn ?: return null
        types[fqn]?.let { return it }
        if (!attribute.embedded) return null
        return implied[fqn] ?: JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
            ?.let { cls -> implied.computeIfAbsent(fqn) { ManagedType(cls, ManagedKind.EMBEDDABLE, null) } }
    }

    /**
     * [name] declared by a subclass of [type] (Hibernate treats `a.subclassAttribute` as an implicit downcast).
     */
    fun subtypeAttribute(type: ManagedType, name: String): Attribute? =
        types.values.asSequence()
            .filter { it !== type && it.cls.isInheritor(type.cls, true) }
            .firstNotNullOfOrNull { it.attributes[name] }

    /** The domain type of the Spring Data repository that declares [method] (`JpaRepository<User, Long>` -> User). */
    fun domainTypeOf(method: PsiMethod): ManagedType? {
        val repository = method.containingClass ?: return null
        val type = JavaPsiFacade.getElementFactory(repository.project).createType(repository)
        val domain = PsiUtil.substituteTypeParameter(type, SpringNames.DATA_REPOSITORY, 0, false) as? PsiClassType
        return type(domain?.resolve()?.qualifiedName)
    }
}

internal object EntityModels {
    /** Cached per project; rebuilt when declarations in Java or Kotlin code change. Call inside a read action. */
    fun get(project: Project): EntityModel =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(build(project), dev.tendril.CodeStructureTracker.getInstance(project))
        }

    private fun build(project: Project): EntityModel {
        val facade = JavaPsiFacade.getInstance(project)
        val all = GlobalSearchScope.allScope(project)
        val types = LinkedHashMap<String, ManagedType>()
        val kinds = listOf(
            JpaNames.ENTITY to ManagedKind.ENTITY,
            JpaNames.EMBEDDABLE to ManagedKind.EMBEDDABLE,
            JpaNames.MAPPED_SUPERCLASS to ManagedKind.MAPPED_SUPERCLASS,
        )
        for ((names, kind) in kinds) for (annotationName in names) {
            val annotationClass = facade.findClass(annotationName, all) ?: continue
            for (cls in AnnotatedElementsSearch.searchPsiClasses(annotationClass, all).findAll()) {
                if (cls.isAnnotationType) continue
                val fqn = cls.qualifiedName ?: continue
                val entityName = if (kind != ManagedKind.ENTITY) null else
                    cls.getAnnotation(annotationName)
                        ?.let { com.intellij.codeInsight.AnnotationUtil.getStringAttributeValue(it, "name") }
                        ?.takeIf(String::isNotBlank) ?: cls.name
                types.putIfAbsent(fqn, ManagedType(cls, kind, entityName))
            }
        }
        return EntityModel(types)
    }
}

private fun PsiModifierListOwner.hasAny(names: List<String>) = names.any { hasAnnotation(it) }

private fun isMapped(cls: PsiClass) = cls.hasAny(JpaNames.ENTITY) || cls.hasAny(JpaNames.MAPPED_SUPERCLASS)

/** [cls] and its persistent superclasses (entities and mapped superclasses), nearest first. */
private fun persistentChain(cls: PsiClass): List<PsiClass> =
    listOf(cls) + generateSequence(cls.superClass) { it.superClass }.takeWhile(::isMapped).toList()

private fun readAttributes(cls: PsiClass): Map<String, Attribute> {
    val chain = persistentChain(cls)
    val fields = chain.flatMap { c -> c.fields.filter(::isPersistentField) }
    // @Id on a getter (and on no field) means property access: the attributes are the getters
    val propertyAccess = fields.none { it.hasAny(JpaNames.ID) } &&
        chain.any { c -> c.methods.any { it.hasAny(JpaNames.ID) } }

    val result = LinkedHashMap<String, Attribute>()
    for (f in fields) result.putIfAbsent(f.name, attribute(f.name, f, f.type))
    if (propertyAccess) {
        for (c in chain) for (m in c.methods) {
            if (!PropertyUtilBase.isSimplePropertyGetter(m) || m.hasModifierProperty(PsiModifier.STATIC)) continue
            if (m.hasAny(JpaNames.TRANSIENT)) continue
            val name = PropertyUtilBase.getPropertyNameByGetter(m)
            val type = m.returnType ?: continue
            result.putIfAbsent(name, attribute(name, m, type))
        }
    }
    return result
}

private fun isPersistentField(f: PsiField): Boolean =
    !f.hasModifierProperty(PsiModifier.STATIC) && !f.hasModifierProperty(PsiModifier.TRANSIENT) &&
        !f.hasAny(JpaNames.TRANSIENT)

private fun attribute(name: String, element: PsiModifierListOwner, type: PsiType): Attribute {
    var targetFqn: String? = null
    var collection = false
    val raw = (type as? PsiClassType)?.resolve()
    if (raw != null) {
        val elementType = when {
            isOrInherits(raw, "java.util.Collection") -> PsiUtil.substituteTypeParameter(type, "java.util.Collection", 0, false)
            isOrInherits(raw, "java.util.Map") -> PsiUtil.substituteTypeParameter(type, "java.util.Map", 1, false)
            else -> null
        }
        if (elementType != null || isOrInherits(raw, "java.util.Collection") || isOrInherits(raw, "java.util.Map")) {
            collection = true
            targetFqn = (elementType as? PsiClassType)?.resolve()?.qualifiedName
        } else {
            targetFqn = raw.qualifiedName
        }
    }
    return Attribute(
        name = name,
        element = element,
        type = type,
        targetFqn = targetFqn,
        collection = collection,
        embedded = element.hasAny(JpaNames.EMBEDDED),
        id = element.hasAny(JpaNames.ID),
    )
}

private fun isOrInherits(cls: PsiClass, base: String) = cls.qualifiedName == base || InheritanceUtil.isInheritor(cls, base)

/** `getEmailAddress` -> `emailAddress`, for a rename that starts from a getter. */
internal fun propertyNameOfGetter(methodName: String): String =
    PropertyUtilBase.getPropertyName(methodName) ?: Introspector.decapitalize(methodName)
