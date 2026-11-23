package dev.tendril.resources

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.uast.injectionHostUExpression
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.expressions.UInjectionHost
import org.jetbrains.uast.getParentOfType

/**
 * Spring resource locations: a string in an annotation that names a script, like `@Value("classpath:db/seed.sql")`,
 * `@Sql("/schema.sql")` or `@Resource(name = "...")`-style attributes. Only `.sql` files are followed for now.
 */
internal object SqlResources {
    private val PREFIX = Regex("^(?:classpath\\*?:|file:)")

    /** `classpath:/db/a.sql` -> `db/a.sql`; null when [location] is not a plain `.sql` path. */
    fun relativePath(location: String): String? {
        val path = location.trim().replace('\\', '/').replace(PREFIX, "").trimStart('/')
        if (!path.endsWith(".sql", ignoreCase = true) || path.length <= 4) return null
        if ('$' in path || '*' in path || '{' in path) return null // placeholders and patterns are not one file
        return path
    }

    /** The `.sql` files in the project that [relative] points at: the path is matched from its end. */
    fun resolve(project: Project, relative: String): List<PsiFile> {
        val scope = GlobalSearchScope.projectScope(project)
        val manager = PsiManager.getInstance(project)
        val fileName = relative.substringAfterLast('/')
        return FilenameIndex.getVirtualFilesByName(fileName, scope)
            .filter { it.path == relative || it.path.endsWith("/$relative") }
            .mapNotNull { manager.findFile(it) }
    }
}

/** Ctrl+Click on `"classpath:db/seed.sql"` in an annotation opens the file. Java and Kotlin. */
class SqlResourceReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerUastReferenceProvider(
            injectionHostUExpression(),
            uastInjectionHostReferenceProvider { expression, host -> referencesOf(expression, host) },
            PsiReferenceRegistrar.DEFAULT_PRIORITY,
        )
    }

    private fun referencesOf(expression: UExpression, host: PsiLanguageInjectionHost): Array<PsiReference> {
        if (expression !is UInjectionHost || !expression.isString) return PsiReference.EMPTY_ARRAY
        val text = host.text
        if (!text.trimEnd('"', '\'').endsWith(".sql", ignoreCase = true)) return PsiReference.EMPTY_ARRAY // cheap first
        if (expression.getParentOfType<UAnnotation>() == null) return PsiReference.EMPTY_ARRAY

        val value = expression.evaluateToString() ?: return PsiReference.EMPTY_ARRAY
        val relative = SqlResources.relativePath(value) ?: return PsiReference.EMPTY_ARRAY
        val start = text.indexOf(value).takeIf { it >= 0 } ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(SqlFileReference(host, TextRange(start, start + value.length), relative))
    }
}

private class SqlFileReference(element: PsiElement, range: TextRange, private val relative: String) :
    PsiPolyVariantReferenceBase<PsiElement>(element, range, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        SqlResources.resolve(element.project, relative).map { PsiElementResolveResult(it) }.toTypedArray()

    override fun isReferenceTo(element: PsiElement): Boolean = multiResolve(false).any { it.element == element }
}

/** A `.sql` file -> the annotations that load it: gutter icon on the first line. */
class SqlUsagesLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun getName() = "Tendril: where a SQL script is used"

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        // the first leaf of a .sql file, whichever language the IDE parses it as
        if (element.textRange.startOffset != 0 || PsiTreeUtil.firstChild(element) !== element) return
        val file = element.containingFile ?: return
        if (file.virtualFile?.extension?.equals("sql", ignoreCase = true) != true) return

        val usages = ReferencesSearch.search(file, GlobalSearchScope.projectScope(element.project))
            .findAll().map { it.element }.distinct()
        if (usages.isEmpty()) return

        result.add(
            NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
                .setTargets(usages)
                .setTooltipText("Used by ${usages.size} annotation${if (usages.size == 1) "" else "s"}")
                .setPopupTitle("Code using ${file.name}")
                .createLineMarkerInfo(element),
        )
    }
}
