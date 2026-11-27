package dev.tendril.calls.clientgen

import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.util.PackageUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiUtil
import dev.tendril.calls.CallMatching
import dev.tendril.endpoints.EndpointRequests
import dev.tendril.feign.FeignResolver
import dev.tendril.feign.FeignScanner
import org.jetbrains.jps.model.java.JavaSourceRootType

/** A Feign client of the project the new method goes to. */
class TargetClient(
    val name: String,
    val qualifiedName: String,
    val packageName: String,
    /** Its `path`, resolved. */
    val path: String,
    val kotlin: Boolean,
    /** Its service is the backend application (by name, or by a local address on the backend's port). */
    val callsService: Boolean,
    /** The name of its method that calls this endpoint already, if one does. */
    val existingMethod: String?,
    val pointer: SmartPsiElementPointer<PsiClass>,
)

/** The project a client method is created in: its clients, and where new code goes by default. */
class TargetInfo(
    val project: Project,
    val clients: List<TargetClient>,
    val defaultPackage: String,
    val kotlin: Boolean,
    /** The project's clients give `url`: a new one gets a url property too (else it relies on discovery). */
    val usesUrl: Boolean,
    val records: Boolean,
    val anchor: SmartPsiElementPointer<PsiElement>?,
)

object ClientTargets {
    private const val BOOT_APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication"

    /** Needs a read action in [target]. */
    fun analyze(target: Project, e: EndpointSpec): TargetInfo {
        val profile = FeignResolver.selectedProfile(target)
        val pointers = SmartPointerManager.getInstance(target)
        val wanted = CallMatching.join(e.contextPath, e.path)
        val verb = EndpointRequests.verbOf(e.httpMethod)
        val clients = FeignScanner.cached(target).mapNotNull { def ->
            val cls = def.cls.element ?: return@mapNotNull null
            val info = FeignResolver.resolve(target, def, profile)
            val url = info.resolution.url?.let(CallMatching::parseUrl)
            val byName = CallMatching.serviceKey(info.name.ifBlank { def.name }) == CallMatching.serviceKey(e.service)
            val byAddress = url != null && CallMatching.isLocal(url.host) && url.port == e.port
            val path = FeignResolver.substitute(target, def.path, profile).first
            val existing = def.calls.firstOrNull {
                EndpointRequests.verbOf(it.httpMethod) == verb && CallMatching.join(path, it.subPath).let { p ->
                    CallMatching.pathMatches(p, wanted)
                }
            }?.methodName
            TargetClient(
                name = cls.name ?: def.className, qualifiedName = cls.qualifiedName ?: def.className,
                packageName = (cls.containingFile as? PsiClassOwner)?.packageName.orEmpty(), path = path,
                kotlin = cls.navigationElement.containingFile?.name?.endsWith(".kt") == true,
                callsService = byName || byAddress, existingMethod = existing.takeIf { byName || byAddress },
                pointer = pointers.createSmartPsiElementPointer(cls),
            )
        }.sortedByDescending { it.callsService }
        val app = bootApplication(target)
        val anchor: PsiElement? = clients.firstOrNull()?.pointer?.element ?: app
        val defaultPackage = clients.firstOrNull()?.packageName
            ?: (app?.containingFile as? PsiClassOwner)?.packageName?.let { if (it.isEmpty()) "client" else "$it.client" } ?: "client"
        val kotlin = clients.firstOrNull()?.kotlin ?: (app?.navigationElement?.containingFile?.name?.endsWith(".kt") == true)
        val records = anchor?.let { PsiUtil.getLanguageLevel(it).isAtLeast(LanguageLevel.JDK_16) } ?: true
        return TargetInfo(
            target, clients, defaultPackage, kotlin,
            usesUrl = FeignScanner.cached(target).any { it.url.isNotBlank() }, records = records,
            anchor = anchor?.let { pointers.createSmartPsiElementPointer(it) },
        )
    }

    private fun bootApplication(project: Project): PsiClass? {
        val annotation = JavaPsiFacade.getInstance(project).findClass(BOOT_APPLICATION, GlobalSearchScope.allScope(project)) ?: return null
        return AnnotatedElementsSearch.searchPsiClasses(annotation, GlobalSearchScope.projectScope(project)).findFirst()
    }

    /** What the dialog decided. */
    class Choice(
        /** Null: a new client. */
        val client: TargetClient?,
        val newClientName: String,
        val packageName: String,
        val dtoPackage: String,
        val methodName: String,
        val withUrl: Boolean,
    )

    /** The files to write and the text of the method, computed from the choice. Needs a read action in the target. */
    class Plan(
        val info: TargetInfo,
        val choice: Choice,
        val kotlin: Boolean,
        val methodText: String,
        val methodImports: Set<String>,
        /** New files: package to (file name to text). */
        val files: List<Triple<String, String, String>>,
        val reused: List<String>,
        val notes: List<String>,
    ) {
        val summary: String
            get() = buildString {
                val client = choice.client?.name ?: choice.newClientName
                append(if (choice.client == null) "Creates $client with ${choice.methodName}" else "Adds ${choice.methodName} to $client")
                val dtos = files.filter { it.second.removeSuffix(".kt").removeSuffix(".java") != choice.newClientName }
                if (dtos.isNotEmpty()) append("; copies ${dtos.joinToString(", ") { it.second.substringBeforeLast('.') }} to ${choice.dtoPackage}")
                if (reused.isNotEmpty()) append("; uses the existing ${reused.joinToString(", ")}")
            }
    }

    fun plan(info: TargetInfo, endpoint: EndpointSpec, dtos: List<DtoSpec>, choice: Choice): Plan {
        val kotlin = choice.client?.kotlin ?: info.kotlin
        val scope = GlobalSearchScope.projectScope(info.project)
        val cache = PsiShortNamesCache.getInstance(info.project)
        // a class with the same name in the target is taken as the DTO already there
        val reused = dtos.mapNotNull { d ->
            cache.getClassesByName(d.name, scope).firstOrNull()?.qualifiedName?.let { d.name to it }
        }.toMap()
        fun remap(t: TypeRef): TypeRef = when {
            t.kind == TypeRef.Kind.DTO && t.name in reused -> TypeRef(TypeRef.Kind.CLASS, reused.getValue(t.name), t.args.map(::remap))
            else -> t.copy(args = t.args.map(::remap))
        }
        val e = endpoint.copy(params = endpoint.params.map { it.copy(type = remap(it.type)) }, returns = endpoint.returns?.let(::remap))

        val clientPackage = choice.client?.packageName ?: choice.packageName
        val full = CallMatching.join(e.contextPath, e.path)
        val notes = e.notes.toMutableList()
        val clientPath = choice.client?.path ?: e.contextPath
        val methodPath = when {
            clientPath.isEmpty() || clientPath == "/" -> full
            full == clientPath -> "/"
            full.startsWith(clientPath.trimEnd('/') + "/") -> full.removePrefix(clientPath.trimEnd('/'))
            else -> full.also { notes += "The client's path $clientPath is not the start of $full: check the method's path" }
        }
        val methodContext = ClientText.Context(kotlin, clientPackage, choice.dtoPackage)
        val methodText = ClientText.method(e, choice.methodName, methodPath, methodContext)

        val files = mutableListOf<Triple<String, String, String>>()
        val ext = if (kotlin) "kt" else "java"
        for (d in dtos) {
            if (d.name in reused) continue
            val c = ClientText.Context(kotlin, choice.dtoPackage, choice.dtoPackage)
            val remapped = d.copy(fields = d.fields.map { it.copy(type = remap(it.type)) })
            files += Triple(choice.dtoPackage, "${d.name}.$ext", ClientText.dto(remapped, info.records, c))
        }
        if (choice.client == null) {
            val url = if (choice.withUrl) "\${${e.service}.url:http://localhost:${e.port}}" else null
            val c = ClientText.Context(kotlin, clientPackage, choice.dtoPackage, methodContext.imports)
            files += Triple(clientPackage, "${choice.newClientName}.$ext", ClientText.client(choice.newClientName, e.service, url, e.contextPath, methodText, c))
        }
        return Plan(info, choice, kotlin, methodText, methodContext.imports, files, reused.keys.toList(), notes)
    }

    /** Writes [plan] into its project and opens the client there. Call on the EDT. */
    fun write(plan: Plan): Result<Unit> = runCatching {
        val project = plan.info.project
        val anchor = plan.choice.client?.pointer?.element ?: plan.info.anchor?.element
        val module = anchor?.let { ModuleUtilCore.findModuleForPsiElement(it) }
            ?: error("No module to write to in ${project.name}: open a Spring Boot application class or a Feign client there first")
        var opened: Pair<PsiFile, Int>? = null
        WriteCommandAction.writeCommandAction(project).withName("Create Feign Client Method").run<RuntimeException> {
            for ((pkg, name, text) in plan.files) {
                val dir = PackageUtil.findOrCreateDirectoryForPackage(module, pkg, sourceRoot(module, anchor, plan.kotlin), false)
                    ?: error("Cannot create the package $pkg")
                if (dir.findFile(name) != null) continue
                val file = PsiFileFactory.getInstance(project)
                    .createFileFromText(name, FileTypeManager.getInstance().getFileTypeByExtension(name.substringAfterLast('.')), text)
                val added = dir.add(file) as PsiFile
                CodeStyleManager.getInstance(project).reformat(added)
                if (name.substringBeforeLast('.') == plan.choice.newClientName && plan.choice.client == null) {
                    opened = added to added.text.indexOf(" ${plan.choice.methodName}(").coerceAtLeast(0)
                }
            }
            plan.choice.client?.let { client -> opened = insert(project, client, plan) }
        }
        val (file, offset) = opened ?: return@runCatching
        file.virtualFile?.let { OpenFileDescriptor(project, it, offset).navigate(true) }
        ProjectUtil.focusProjectWindow(project, true)
    }

    /** Adds the method before the closing brace of [client] and the imports it needs, as text (Java or Kotlin). */
    private fun insert(project: Project, client: TargetClient, plan: Plan): Pair<PsiFile, Int> {
        val cls = client.pointer.element ?: error("${client.name} is no longer there")
        val source = cls.navigationElement
        val file = source.containingFile ?: error("${client.name} has no file")
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: error("${client.name} cannot be edited")
        val end = source.textRange.endOffset
        val brace = document.text.lastIndexOf('}', end - 1)
        require(brace >= source.textRange.startOffset) { "${client.name} has no body to add a method to" }
        val methodStart = brace
        document.insertString(brace, "\n" + plan.methodText)
        val methodEnd = brace + 1 + plan.methodText.length

        val existing = Regex("""^import\s+((?:\w+\.)*(?:\w+|\*))""", RegexOption.MULTILINE).findAll(document.text).map { it.groupValues[1] }.toSet()
        val missing = plan.methodImports.filter { i ->
            i.substringBeforeLast('.') != client.packageName && i !in existing && "${i.substringBeforeLast('.')}.*" !in existing
        }
        var shift = 0
        if (missing.isNotEmpty()) {
            val semicolon = if (plan.kotlin) "" else ";"
            val lastImport = Regex("""^import\s+.*$""", RegexOption.MULTILINE).findAll(document.text).lastOrNull()
            val pkg = Regex("""^package\s+.*$""", RegexOption.MULTILINE).find(document.text)
            val text = missing.joinToString("") { "import $it$semicolon\n" }
            val at = when {
                lastImport != null -> lastImport.range.last + 2
                pkg != null -> pkg.range.last + 2
                else -> 0
            }.coerceAtMost(document.textLength)
            val inserted = if (lastImport == null) "\n$text" else text
            document.insertString(at, inserted)
            shift = inserted.length
        }
        documents.commitDocument(document)
        CodeStyleManager.getInstance(project).reformatText(file, methodStart + shift, methodEnd + shift)
        documents.commitDocument(document)
        val offset = document.text.indexOf(" ${plan.choice.methodName}(", methodStart + shift).coerceAtLeast(0)
        return file to offset
    }

    private fun sourceRoot(module: Module, anchor: PsiElement, kotlin: Boolean): com.intellij.psi.PsiDirectory {
        val roots = ModuleRootManager.getInstance(module).getSourceRoots(JavaSourceRootType.SOURCE)
        val anchorFile = anchor.navigationElement.containingFile?.virtualFile
        val language = if (kotlin) "/kotlin" else "/java"
        val root = roots.firstOrNull { it.path.endsWith(language) && anchorFile != null && com.intellij.openapi.vfs.VfsUtilCore.isAncestor(it, anchorFile, false) }
            ?: roots.firstOrNull { it.path.endsWith(language) }
            ?: roots.firstOrNull { anchorFile != null && com.intellij.openapi.vfs.VfsUtilCore.isAncestor(it, anchorFile, false) }
            ?: roots.firstOrNull() ?: error("The module ${module.name} has no source folder")
        return PsiManager.getInstance(module.project).findDirectory(root) ?: error("Cannot open ${root.path}")
    }
}
