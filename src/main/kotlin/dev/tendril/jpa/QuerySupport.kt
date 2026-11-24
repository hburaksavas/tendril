package dev.tendril.jpa

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.uast.injectionHostUExpression
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.uast.UastHintedVisitorAdapter
import com.intellij.util.ProcessingContext
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Inside `@Query("...")`: Ctrl+Click on an entity name opens the entity, on an attribute (`u.address.city`) the
 * field or getter, on `:name` / `?1` the method parameter. Renaming the target renames the query text. Java and Kotlin.
 */
class QueryReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerUastReferenceProvider(
            injectionHostUExpression(),
            uastInjectionHostReferenceProvider { _, host -> referencesOf(host) },
            PsiReferenceRegistrar.DEFAULT_PRIORITY,
        )
    }

    private fun referencesOf(host: PsiLanguageInjectionHost): Array<PsiReference> {
        val query = QueryAnnotations.queryOf(host) ?: return PsiReference.EMPTY_ARRAY
        val result = ArrayList<PsiReference>()
        fun add(start: Int, end: Int, create: (TextRange) -> PsiReference) {
            val (target, range) = query.source.hostRange(start, end) ?: return
            if (target == host) result += create(range)
        }

        QueryResolver.of(query)?.let { (_, resolved) ->
            for ((entity, type) in resolved.entityTargets) {
                if (entity.isSpel) continue
                add(entity.start, entity.end) { EntityReference(host, it, type) }
            }
            for ((segment, attribute) in resolved.attributeTargets) {
                add(segment.start, segment.end) { AttributeReference(host, it, attribute) }
            }
        }
        val s = query.structure
        for (token in s.namedParams + s.positionalParams) {
            val bound = QueryParameters.target(query, token) ?: continue
            // `:name` without the colon, so a rename replaces the name only
            add(token.start + 1, token.end) { ParameterReference(host, it, bound) }
        }
        return result.toTypedArray()
    }
}

/** Resolves to a light element for Kotlin; matches the Kotlin declaration too. */
private abstract class QueryReference(host: PsiElement, range: TextRange) : PsiReferenceBase<PsiElement>(host, range, true) {
    abstract override fun resolve(): PsiElement

    override fun isReferenceTo(element: PsiElement): Boolean {
        val target = resolve()
        val manager = target.manager
        return manager.areElementsEquivalent(target, element) || manager.areElementsEquivalent(target.navigationElement, element)
    }
}

private class EntityReference(host: PsiElement, range: TextRange, private val type: ManagedType) :
    QueryReference(host, range) {
    override fun resolve(): PsiElement = type.cls

    // with an explicit @Entity(name = ...) the query uses that name, which a class rename does not change;
    // a qualified name in the query is left as it is as well
    override fun handleElementRename(newElementName: String): PsiElement =
        if (type.hasExplicitName || '.' in value) element else super.handleElementRename(newElementName)
}

private class AttributeReference(host: PsiElement, range: TextRange, private val attribute: Attribute) :
    QueryReference(host, range) {
    override fun resolve(): PsiElement = attribute.element

    override fun handleElementRename(newElementName: String): PsiElement =
        super.handleElementRename(if (attribute.element is PsiMethod) propertyNameOfGetter(newElementName) else newElementName)
}

private class ParameterReference(host: PsiElement, range: TextRange, private val bound: BoundParameter) :
    QueryReference(host, range) {
    override fun resolve(): PsiElement = bound.parameter

    // @Param("name") names the parameter for the query; renaming the Java parameter does not change it
    override fun handleElementRename(newElementName: String): PsiElement =
        if (bound.explicitName || value.firstOrNull()?.isDigit() == true) element else super.handleElementRename(newElementName)
}

/** Completion inside `@Query("...")`: entity names, attributes after `alias.`, aliases, and `:parameter` names. */
class QueryCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                    val host = PsiTreeUtil.getParentOfType(parameters.position, PsiLanguageInjectionHost::class.java, false)
                        ?: return
                    val query = QueryAnnotations.queryOf(host) ?: return
                    complete(query, result)
                }
            },
        )
    }

    private fun complete(query: QueryString, result: CompletionResultSet) {
        val text = query.source.text
        val caret = text.indexOf(QueryResolver.DUMMY).takeIf { it >= 0 } ?: return
        val s = query.structure
        val token = s.tokens.firstOrNull { caret >= it.start && caret < it.end } ?: return

        if (token.kind == TokenKind.NAMED_PARAM) {
            val matcher = result.withPrefixMatcher(text.substring(token.start + 1, caret))
            for (p in query.method?.let(QueryParameters::of).orEmpty()) {
                matcher.addElement(LookupElementBuilder.create(p.parameter, p.name).withTypeText(p.parameter.type.presentableText, true))
            }
            return
        }
        if (token.kind != TokenKind.IDENT) return
        val (resolver, _) = QueryResolver.of(query) ?: return
        val model = EntityModels.get(query.annotation.sourcePsi!!.project)
        val matcher = result.withPrefixMatcher(text.substring(token.start, caret))

        fun entities() = model.entities.forEach { e ->
            matcher.addElement(
                LookupElementBuilder.create(e.cls, e.entityName ?: e.cls.name.orEmpty())
                    .withIcon(e.cls.getIcon(0))
                    .withTypeText(e.cls.qualifiedName?.substringBeforeLast('.', ""), true),
            )
        }
        fun aliases() = s.aliases.filter { QueryResolver.DUMMY !in it.name.text }.forEach { a ->
            val type = (a.source as? AliasSource.Entity)?.entity?.name
            matcher.addElement(LookupElementBuilder.create(a.name.text).withTypeText(type, true))
        }

        val entity = s.entities.firstOrNull { token in it.tokens }
        if (entity != null) {
            entities()
            if (entity.inJoin) aliases()
            return
        }
        val path = s.paths.firstOrNull { token in it.segments }
        val index = path?.segments?.indexOf(token) ?: 0
        if (path == null || index == 0) {
            aliases()
            return
        }
        val type = resolver.typeOfPrefix(path, index) ?: return
        val subtypes = model.entities.filter { it !== type && it.cls.isInheritor(type.cls, true) }
        val seen = HashSet<String>()
        for (t in listOf(type) + subtypes) for (a in t.attributes.values) {
            if (!seen.add(a.name)) continue
            var item = LookupElementBuilder.create(a.element, a.name).withTypeText(a.type.presentableText, true)
            (a.element as? PsiModifierListOwner)?.let { item = item.withIcon(it.getIcon(0)) }
            if (t !== type) item = item.withTailText("  (${t.cls.name})", true)
            matcher.addElement(item)
        }
    }
}

/** Unknown entities and attributes in a JPQL `@Query`. Java and Kotlin. */
class JpqlQueryInspection : AbstractBaseUastLocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        queryVisitor(holder) { query ->
            val (_, resolved) = QueryResolver.of(query) ?: return@queryVisitor
            report(holder, query, resolved.problems)
        }
}

/** `:name` / `?1` with no method parameter behind it, and method parameters the query never uses. */
class QueryParameterInspection : AbstractBaseUastLocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        queryVisitor(holder) { query ->
            val method = query.annotation.getParentOfType<UMethod>() ?: return@queryVisitor
            val parameters = QueryParameters.of(method.javaPsi)
            val problems = QueryParameters.check(query, parameters) { unused ->
                val anchor = method.uastParameters.firstOrNull { it.javaPsi == unused.parameter }
                    ?.let { it.uastAnchor?.sourcePsi ?: it.sourcePsi }
                    ?: return@check
                if (anchor.containingFile != holder.file) return@check
                val how = if (unused.explicitName) "':${unused.name}'" else "'${unused.name}'"
                holder.registerProblem(anchor, "Parameter $how is not used in the query", ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
            }
            report(holder, query, problems)
        }
}

private fun queryVisitor(holder: ProblemsHolder, check: (QueryString) -> Unit): PsiElementVisitor =
    UastHintedVisitorAdapter.create(
        holder.file.language,
        object : AbstractUastNonRecursiveVisitor() {
            override fun visitAnnotation(node: UAnnotation): Boolean {
                QueryAnnotations.queries(node).forEach(check)
                return true
            }
        },
        arrayOf(UAnnotation::class.java),
    )

private fun report(holder: ProblemsHolder, query: QueryString, problems: List<QueryProblem>) {
    for (p in problems) {
        val (host, range) = query.source.hostRange(p.start, p.end) ?: continue
        if (host.containingFile != holder.file) continue
        holder.registerProblem(host, p.message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, range)
    }
}

/**
 * Finds the `@Query` references to an entity, attribute or repository-method parameter, so Find Usages and Rename
 * include query text (the platform does not look into string literals by itself).
 */
class QueryReferenceSearcher :
    com.intellij.openapi.application.QueryExecutorBase<PsiReference, com.intellij.psi.search.searches.ReferencesSearch.SearchParameters>(true) {

    override fun processQuery(
        parameters: com.intellij.psi.search.searches.ReferencesSearch.SearchParameters,
        consumer: com.intellij.util.Processor<in PsiReference>,
    ) {
        val target = dev.tendril.javaViewOf(parameters.elementToSearch) ?: return
        val word = when (target) {
            is PsiClass -> target.name?.takeIf { target.hasJpaAnnotation() }
            is PsiField -> target.name.takeIf { target.containingClass?.hasJpaAnnotation() == true }
            is PsiMethod -> com.intellij.psi.util.PropertyUtilBase.getPropertyNameByGetter(target)
                .takeIf { com.intellij.psi.util.PropertyUtilBase.isSimplePropertyGetter(target) && target.containingClass?.hasJpaAnnotation() == true }
            is PsiParameter -> target.name.takeIf {
                (target.declarationScope as? PsiMethod)?.let { m -> QueryNames.ANNOTATIONS.any(m::hasAnnotation) } == true
            }
            else -> null
        } ?: return
        // not effectiveSearchScope: scope optimizers there drop files that hold no code reference to the target,
        // which is exactly where query strings are
        val scope = parameters.scopeDeterminedByUser.intersectWith(
            com.intellij.psi.search.PsiSearchHelper.getInstance(target.project).getUseScope(target),
        )
        com.intellij.psi.search.PsiSearchHelper.getInstance(target.project).processElementsWithWord(
            { element, offset ->
                if (element !is PsiLanguageInjectionHost) return@processElementsWithWord true
                element.references.filter { it.rangeInElement.containsOffset(offset) && it.isReferenceTo(target) }
                    .all(consumer::process)
            },
            scope, word, com.intellij.psi.search.UsageSearchContext.IN_STRINGS, true,
        )
    }

    private fun PsiClass.hasJpaAnnotation() =
        (JpaNames.ENTITY + JpaNames.EMBEDDABLE + JpaNames.MAPPED_SUPERCLASS).any(::hasAnnotation)
}
