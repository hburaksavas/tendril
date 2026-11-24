package dev.tendril.jpa

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.uast.*
import org.jetbrains.uast.expressions.UInjectionHost

internal object QueryNames {
    const val QUERY = "org.springframework.data.jpa.repository.Query"
    const val NATIVE_QUERY = "org.springframework.data.jpa.repository.NativeQuery"
    const val PARAM = "org.springframework.data.repository.query.Param"
    val ANNOTATIONS = setOf(QUERY, NATIVE_QUERY)

    /** Parameters Spring Data passes on to the query rather than binding them. */
    val NOT_BOUND = listOf(
        "org.springframework.data.domain.Pageable", "org.springframework.data.domain.Sort",
        "org.springframework.data.domain.Limit", "org.springframework.data.domain.ScrollPosition",
        "java.lang.Class", "kotlin.coroutines.Continuation",
    )
}

/**
 * The text of a query attribute, decoded from one string literal or from literals joined with `+`, and the way back
 * from an offset in that text to the literal and the offset inside it (escapes and text-block indentation included).
 */
internal class QuerySource private constructor(val text: String, private val segments: List<Segment>) {

    private class Segment(
        val host: PsiLanguageInjectionHost,
        val start: Int,
        val end: Int,
        val escaper: LiteralTextEscaper<out PsiLanguageInjectionHost>,
        val range: TextRange,
    )

    fun covers(host: PsiElement) = segments.any { it.host == host }

    /** The literal holding [start, end) of the text, and the range inside it; null when it spans two literals. */
    fun hostRange(start: Int, end: Int): Pair<PsiLanguageInjectionHost, TextRange>? {
        val s = segments.firstOrNull { start >= it.start && end <= it.end } ?: return null
        val from = s.escaper.getOffsetInHost(start - s.start, s.range)
        val to = s.escaper.getOffsetInHost(end - s.start, s.range)
        if (from < 0 || to < from) return null
        return s.host to TextRange(from, to)
    }

    /** Offset in the text of an offset inside [host]. */
    fun textOffset(host: PsiElement, offsetInHost: Int): Int? {
        val s = segments.firstOrNull { it.host == host } ?: return null
        for (i in 0..(s.end - s.start)) {
            if (s.escaper.getOffsetInHost(i, s.range) >= offsetInHost) return s.start + i
        }
        return null
    }

    companion object {
        /** Null for anything but string literals (a constant, an interpolated Kotlin string, ...). */
        fun of(expression: UExpression): QuerySource? {
            val hosts = ArrayList<PsiLanguageInjectionHost>()
            if (!collect(expression, hosts) || hosts.isEmpty()) return null
            val text = StringBuilder()
            val segments = ArrayList<Segment>()
            for (host in hosts) {
                val escaper = host.createLiteralTextEscaper()
                val range = escaper.relevantTextRange
                val start = text.length
                if (!escaper.decode(range, text)) return null
                segments += Segment(host, start, text.length, escaper, range)
            }
            return QuerySource(text.toString(), segments)
        }

        private fun collect(e: UExpression, into: MutableList<PsiLanguageInjectionHost>): Boolean {
            val host = (e as? UInjectionHost)?.takeIf { it.isString }?.sourcePsi as? PsiLanguageInjectionHost
            return when {
                host != null -> into.add(host)
                e is UParenthesizedExpression -> collect(e.expression, into)
                e is UPolyadicExpression && e.operator == UastBinaryOperator.PLUS -> e.operands.all { collect(it, into) }
                else -> false
            }
        }
    }
}

/** One query string of a `@Query` / `@NativeQuery`: its `value` or its `countQuery`. */
internal class QueryString(
    val annotation: UAnnotation,
    val attribute: String,
    val native: Boolean,
    val source: QuerySource,
) {
    val structure: JpqlStructure by lazy { JpqlParser.parse(source.text) }
    val method: PsiMethod? get() = annotation.getParentOfType<UMethod>()?.javaPsi
}

internal object QueryAnnotations {

    /** The `@Query` around [element] (a string literal in it), or null. */
    fun annotationOf(element: PsiElement?): UAnnotation? {
        val annotation = element?.toUElement()?.getParentOfType<UAnnotation>() ?: return null
        return annotation.takeIf { it.qualifiedName in QueryNames.ANNOTATIONS }
    }

    fun queries(annotation: UAnnotation): List<QueryString> {
        val name = annotation.qualifiedName ?: return emptyList()
        if (name !in QueryNames.ANNOTATIONS) return emptyList()
        val native = name == QueryNames.NATIVE_QUERY ||
            annotation.findDeclaredAttributeValue("nativeQuery")?.evaluate() == true
        return listOf("value", "countQuery").mapNotNull { attribute ->
            val value = annotation.findDeclaredAttributeValue(attribute) ?: return@mapNotNull null
            QuerySource.of(value)?.let { QueryString(annotation, attribute, native, it) }
        }
    }

    /** The query string a literal belongs to. */
    fun queryOf(host: PsiElement): QueryString? =
        annotationOf(host)?.let { a -> queries(a).firstOrNull { it.source.covers(host) } }
}

/** A finding at [start, end) of the query text. */
internal class QueryProblem(val start: Int, val end: Int, val message: String)

/** What the names of a JPQL query point at, given the entity model. */
internal class ResolvedQuery(
    val entityTargets: Map<EntityName, ManagedType>,
    val attributeTargets: Map<Token, Attribute>,
    val problems: List<QueryProblem>,
)

internal class QueryResolver(
    private val structure: JpqlStructure,
    private val model: EntityModel,
    private val domainType: ManagedType?,
    private val project: com.intellij.openapi.project.Project,
) {
    private val entityTargets = LinkedHashMap<EntityName, ManagedType>()
    private val attributeTargets = LinkedHashMap<Token, Attribute>()
    private val problems = ArrayList<QueryProblem>()
    private val aliasTypes = HashMap<AliasDecl, ManagedType?>()
    private val pathTypes = HashMap<JpqlPath, ManagedType?>()
    private val resolving = HashSet<AliasDecl>()

    fun resolve(): ResolvedQuery {
        for (e in structure.entities) entityOf(e)
        for (p in structure.paths) walk(p)
        return ResolvedQuery(entityTargets, attributeTargets, problems)
    }

    private fun entityOf(e: EntityName): ManagedType? {
        if (e.isSpel) return if ("#entityName" in e.name) domainType else null
        if (DUMMY in e.name) return null
        val type = model.entity(e.name)
        if (type != null) entityTargets[e] = type
        else if (!e.inJoin || e.tokens.size == 1) problems += QueryProblem(e.start, e.end, "Unknown entity '${e.name}'")
        return type
    }

    private fun aliasType(alias: AliasDecl): ManagedType? = aliasTypes.getOrPut(alias) {
        if (!resolving.add(alias)) return null
        try {
            when (val s = alias.source) {
                is AliasSource.Entity -> if (s.entity.isSpel && "#entityName" in s.entity.name) domainType
                else if (s.entity.isSpel) null else model.entity(s.entity.name)
                is AliasSource.Join -> walk(s.path)
            }
        } finally {
            resolving.remove(alias)
        }
    }

    /** The type a path ends in; records the attribute of each segment and unknown attributes. */
    fun walk(path: JpqlPath): ManagedType? {
        if (path in pathTypes) return pathTypes[path]
        val result = walkUncached(path, path.segments.size)
        pathTypes[path] = result
        return result
    }

    /** The type reached after the first [count] segments (completion uses a prefix of a path). */
    fun typeOfPrefix(path: JpqlPath, count: Int): ManagedType? = walkUncached(path, count, record = false)

    private fun walkUncached(path: JpqlPath, count: Int, record: Boolean = true): ManagedType? {
        val alias = structure.alias(path.segments.first().text) ?: return null
        var type = aliasType(alias) ?: return null
        var previousCollection = false
        for (segment in path.segments.subList(1, count)) {
            val name = segment.text
            if (DUMMY in name) return null
            val attribute = type.attributes[name] ?: model.subtypeAttribute(type, name)
                ?: type.idAttribute.takeIf { name == "id" }
            if (attribute == null) {
                val special = name == "class" || (previousCollection && name.lowercase() in COLLECTION_PROPERTIES)
                if (!special && record) {
                    problems += QueryProblem(segment.start, segment.end, "Unknown attribute '$name' of '${type.cls.name}'")
                }
                return null
            }
            if (record) attributeTargets[segment] = attribute
            previousCollection = attribute.collection
            type = model.target(attribute, project) ?: return null
        }
        return type
    }

    companion object {
        /** What the completion copy of a file holds at the caret. */
        const val DUMMY = "IntellijIdeaRulezzz"
        private val COLLECTION_PROPERTIES = setOf("size", "elements", "indices", "maxelement", "minelement", "maxindex", "minindex")

        /** Entity and attribute resolution of a JPQL query; null for native SQL or without JPA on the class path. */
        fun of(query: QueryString): Pair<QueryResolver, ResolvedQuery>? {
            if (query.native) return null
            val project = query.annotation.sourcePsi?.project ?: return null
            val model = EntityModels.get(project)
            if (!model.available) return null
            val resolver = QueryResolver(query.structure, model, query.method?.let(model::domainTypeOf), project)
            return resolver to resolver.resolve()
        }
    }
}

/** A method parameter the query must bind: by [name] (`:name`) or by [position] (`?1`, 1-based). */
internal class BoundParameter(val parameter: PsiParameter, val name: String, val position: Int, val explicitName: Boolean)

internal object QueryParameters {

    fun of(method: PsiMethod): List<BoundParameter> {
        val result = ArrayList<BoundParameter>()
        for (p in method.parameterList.parameters) {
            if (QueryNames.NOT_BOUND.any { InheritanceUtil.isInheritor(p.type, it) || p.type.canonicalText.startsWith(it) }) continue
            val explicit = p.getAnnotation(QueryNames.PARAM)
                ?.let { AnnotationUtil.getStringAttributeValue(it, "value") }?.takeIf(String::isNotBlank)
            result += BoundParameter(p, explicit ?: p.name, result.size + 1, explicit != null)
        }
        return result
    }

    /** Problems of the parameters the query uses; [unused] receives parameters the query never uses. */
    fun check(query: QueryString, parameters: List<BoundParameter>, unused: (BoundParameter) -> Unit): List<QueryProblem> {
        val s = query.structure
        val problems = ArrayList<QueryProblem>()
        if (s.namedParams.isNotEmpty() && s.positionalParams.isNotEmpty()) {
            val p = s.positionalParams.first()
            problems += QueryProblem(p.start, p.end, "Named (:name) and positional (?1) parameters cannot be mixed in one query")
            return problems
        }
        val byName = parameters.associateBy { it.name }
        for (token in s.namedParams) {
            val name = s.namedParamName(token)
            if (QueryResolver.DUMMY in name || name in byName) continue
            problems += QueryProblem(token.start, token.end, "No method parameter is bound to ':$name'")
        }
        for (token in s.positionalParams) {
            val index = s.positionalIndex(token) ?: continue
            if (index in 1..parameters.size) continue
            val count = parameters.size
            problems += QueryProblem(
                token.start, token.end,
                "'?$index' has no method parameter: the method has $count bindable parameter${if (count == 1) "" else "s"}",
            )
        }
        // A parameter Spring Data binds that the query does not use fails when the query is created.
        if (query.attribute == "value") {
            val names = s.namedParams.map(s::namedParamName).toSet() + s.spelNames
            val positions = s.positionalParams.mapNotNull(s::positionalIndex).toSet() + s.spelIndexes.map { it + 1 }
            for (p in parameters) if (p.name !in names && p.position !in positions) unused(p)
        }
        return problems
    }

    /** The parameter `:name` / `?1` binds, for navigation. */
    fun target(query: QueryString, token: Token): BoundParameter? {
        val parameters = query.method?.let(::of) ?: return null
        val s = query.structure
        return when (token.kind) {
            TokenKind.NAMED_PARAM -> parameters.firstOrNull { it.name == s.namedParamName(token) }
            TokenKind.POSITIONAL_PARAM -> s.positionalIndex(token)?.let { parameters.getOrNull(it - 1) }
            else -> null
        }
    }
}
