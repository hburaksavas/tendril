package dev.tendril.recordings.tests

import com.intellij.ide.util.PackageUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.CommonClassNames
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.search.GlobalSearchScope
import dev.tendril.endpoints.EndpointScanner
import dev.tendril.feign.FeignResolver
import dev.tendril.feign.FeignScanner
import dev.tendril.http.HttpExecutor
import dev.tendril.mocks.MockFactory
import dev.tendril.recordings.Recording
import dev.tendril.recordings.RecordingKind
import dev.tendril.recordings.RecordingText
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.net.URI

/** "Generate Test" on a recorded request: builds the [RecordedTest] from the code and writes it to the test folder. */
object RecordedTests {

    class Plan(val test: RecordedTest, val module: Module)

    private const val MOCKITO_BEAN = "org.springframework.test.context.bean.override.mockito.MockitoBean"
    private const val MOCK_BEAN = "org.springframework.boot.test.mock.mockito.MockBean"

    /** Headers the test should not send: the transport sets them, or they only describe the client. */
    private val SKIPPED = setOf(
        "host", "content-length", "connection", "transfer-encoding", "expect", "upgrade", "accept-encoding",
        "user-agent", "postman-token", "cache-control", "pragma", "origin", "referer", "content-type",
    )

    /**
     * What the test for [r] holds; [outgoing] are the recorded Feign calls (those of the same flow become stubs).
     * Fails with a message the user can act on. Needs a read action.
     */
    fun plan(project: Project, r: Recording, outgoing: List<Recording>): Result<Plan> = runCatching {
        require(r.kind == RecordingKind.INCOMING) { "Tests are made from incoming requests." }
        val status = r.status ?: error("No response was recorded for this request.")
        require(r.bodyEncoding != "base64") { "The request body is binary and cannot be written into a test." }
        require(r.responseBodyEncoding != "base64") { "The response body is binary and cannot be expected in a test." }

        val candidates = EndpointScanner.cached(project).filter { RecordingText.matchesRoute(r, it) }
        val byHandler = candidates.filter { r.handler == "${it.className.substringAfterLast('.')}.${it.methodName}" }
        val endpoint = byHandler.ifEmpty { candidates }.distinctBy { it.method }.singleOrNull()
            ?: error("No unique controller method was found for this request.")
        val handler = endpoint.method.element ?: error("The controller method is no longer there.")
        val controller = handler.containingClass ?: error("The controller method is no longer there.")
        val module = ModuleUtilCore.findModuleForPsiElement(handler) ?: error("The controller is not in a module.")
        val kotlin = handler.navigationElement.containingFile?.name?.endsWith(".kt") == true
        val packageName = (controller.containingFile as? PsiClassOwner)?.packageName.orEmpty()

        val uri = runCatching { URI.create(r.url.orEmpty()) }.getOrNull() ?: error("The recorded address cannot be read.")
        val target = (uri.rawPath ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val contextPath = FeignResolver.appValue(project, "server.servlet.context-path", handler)?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() && target.startsWith(it) }

        val notes = mutableListOf<String>()
        val masked = r.headers.filter { it.second == "***" }.map { it.first }.distinct()
        if (masked.isNotEmpty()) {
            val what = if (masked.size == 1) "${masked.single()} was" else "${masked.joinToString(", ")} were"
            notes += "$what masked when recorded: set ${if (masked.size == 1) "it" else "them"} here, or use @WithMockUser when the endpoint needs a user"
        }
        val headers = r.headers.filter { it.first.lowercase() !in SKIPPED && it.second != "***" }
        val contentType = r.headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
        val responseType = r.responseHeaders.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
        val responseBody = r.responseBody?.takeIf { it.isNotEmpty() }
        val json = responseBody != null && (responseType?.contains("json", ignoreCase = true) == true ||
            HttpExecutor.pretty(responseBody) != responseBody || responseBody.trim().let { it.startsWith("{") || it.startsWith("[") })
        if (responseBody?.contains("\"***\"") == true) notes += "The recorder masked secret fields (\"***\") of the response: adjust the expected body"

        val scope = module.getModuleWithDependenciesAndLibrariesScope(true)
        val facade = JavaPsiFacade.getInstance(project)
        val mockAnnotation = if (facade.findClass(MOCKITO_BEAN, scope) != null) MOCKITO_BEAN else MOCK_BEAN
        val imports = sortedSetOf<String>()
        val (clients, stubs) = stubsFor(project, r, outgoing, kotlin, imports)

        val base = controller.name ?: "Controller"
        var className = "${base}RecordedTest"
        var n = 2
        val projectScope = GlobalSearchScope.projectScope(project)
        while (facade.findClass(if (packageName.isEmpty()) className else "$packageName.$className", projectScope) != null) {
            className = "${base}Recorded${n++}Test"
        }

        Plan(
            RecordedTest(
                kotlin = kotlin, packageName = packageName, className = className,
                testName = testName(handler.name, r.method ?: "GET", uri.rawPath ?: "/", status, kotlin),
                mockAnnotation = mockAnnotation, httpMethod = (r.method ?: "GET").uppercase(), uri = target,
                contextPath = contextPath, headers = headers, maskedHeaders = masked, contentType = contentType,
                body = r.body?.takeIf { it.isNotEmpty() }?.let { HttpExecutor.pretty(it) }, status = status,
                responseBody = responseBody?.let { if (json) HttpExecutor.pretty(it) else it }, responseIsJson = json,
                clients = clients, stubs = stubs, notes = notes, imports = imports,
            ),
            module,
        )
    }

    internal fun testName(handler: String, method: String, path: String, status: Int, kotlin: Boolean): String =
        if (kotlin) {
            val words = path.split('/').filter { it.isNotEmpty() }.joinToString(" ") { it.replace(Regex("[^A-Za-z0-9_-]+"), "_") }
            "${method.uppercase()} $words returns $status".replace(Regex(" +"), " ").trim()
        } else {
            handler + "Returns" + status
        }

    /** One mocked client per Feign interface called in the request's flow, one stub per client method. */
    private fun stubsFor(
        project: Project, r: Recording, outgoing: List<Recording>, kotlin: Boolean, imports: MutableSet<String>,
    ): Pair<List<MockedClient>, List<Stub>> {
        if (r.flow <= 0) return emptyList<MockedClient>() to emptyList()
        val calls = outgoing.filter { it.flow == r.flow }.sortedBy { it.time }
        if (calls.isEmpty()) return emptyList<MockedClient>() to emptyList()
        val methods = HashMap<String, PsiMethod>()
        for (def in FeignScanner.cached(project)) {
            def.cls.element?.methods?.forEach { methods.putIfAbsent(MockFactory.configKey(it), it) }
        }
        val clients = LinkedHashMap<String, MockedClient>()
        val stubs = mutableListOf<Stub>()
        for ((key, recorded) in calls.groupBy { it.client ?: "" }) {
            val method = methods[key]
            val cls = method?.containingClass
            if (method == null || cls == null) {
                stubs += Stub("", "", emptyList(), emptyList(), listOf("A call to ${recorded.first().url} was recorded, but its Feign client was not found"))
                continue
            }
            val client = clients.getOrPut(cls.qualifiedName ?: cls.name.orEmpty()) {
                MockedClient(cls.qualifiedName ?: cls.name.orEmpty(), cls.name.orEmpty(), cls.name.orEmpty().replaceFirstChar { it.lowercase() })
            }
            val matchers = method.parameterList.parameters.map { matcher(it.type, kotlin) }
            val notes = mutableListOf<String>()
            val values = mutableListOf<StubValue>()
            recorded.forEachIndexed { i, call ->
                val which = if (recorded.size > 1) "Call ${i + 1} of ${cls.name}.${method.name}" else "${cls.name}.${method.name}"
                when {
                    call.error != null -> notes += "$which failed when recorded (${call.error}): stub it with willThrow(...)"
                    (call.status ?: 0) >= 400 -> notes += "$which returned ${call.status} when recorded, which Feign throws: stub it with willThrow(...)"
                    else -> valueOf(method.returnType, call, kotlin, imports)
                        .onSuccess { if (it != null) values += it else notes += "$which returns nothing: no stub needed" }
                        .onFailure { notes += "$which: ${it.message}" }
                }
            }
            stubs += Stub(client.field, method.name, matchers, values.takeIf { notes.none { n -> n.contains("willThrow") } }.orEmpty(), notes)
        }
        return clients.values.toList() to stubs
    }

    private fun matcher(type: PsiType, kotlin: Boolean): String {
        val name = when (type) {
            PsiTypes.intType() -> "anyInt"
            PsiTypes.longType() -> "anyLong"
            PsiTypes.booleanType() -> "anyBoolean"
            PsiTypes.doubleType() -> "anyDouble"
            PsiTypes.floatType() -> "anyFloat"
            PsiTypes.shortType() -> "anyShort"
            PsiTypes.byteType() -> "anyByte"
            PsiTypes.charType() -> "anyChar"
            else -> return if (kotlin) "anyArg()" else "any()"
        }
        return if (kotlin) "ArgumentMatchers.$name()" else "$name()"
    }

    /** The recorded response of [call] as the client method returns it; null for a method returning nothing. */
    private fun valueOf(type: PsiType?, call: Recording, kotlin: Boolean, imports: MutableSet<String>): Result<StubValue?> = runCatching {
        if (type == null || type == PsiTypes.voidType()) return@runCatching null
        var inner: PsiType = type
        var wrapper = StubValue.Wrapper.NONE
        val name = (type as? PsiClassType)?.resolve()?.qualifiedName
        if (name == "java.util.Optional" || name == "org.springframework.http.ResponseEntity") {
            inner = (type as PsiClassType).parameters.firstOrNull() ?: error("its return type has no type argument")
            wrapper = if (name == "java.util.Optional") StubValue.Wrapper.OPTIONAL else StubValue.Wrapper.RESPONSE_ENTITY
            if (wrapper == StubValue.Wrapper.RESPONSE_ENTITY) imports += "org.springframework.http.ResponseEntity"
        }
        if (inner is PsiClassType && inner.resolve()?.qualifiedName == "java.lang.Void") return@runCatching null
        val body = call.responseBody ?: error("no body was recorded, stub it by hand")
        require(call.responseBodyEncoding != "base64") { "the recorded body is binary, stub it by hand" }
        val isString = inner is PsiClassType && inner.resolve()?.qualifiedName == CommonClassNames.JAVA_LANG_STRING
        if (isString) return@runCatching StubValue(null, body, wrapper, call.status ?: 200)
        StubValue(typeText(inner, kotlin, imports), HttpExecutor.pretty(body), wrapper, call.status ?: 200)
    }

    private val KOTLIN_NAMES = mapOf(
        "java.lang.String" to "String", "java.lang.Integer" to "Int", "java.lang.Long" to "Long", "java.lang.Boolean" to "Boolean",
        "java.lang.Double" to "Double", "java.lang.Float" to "Float", "java.lang.Short" to "Short", "java.lang.Byte" to "Byte",
        "java.lang.Character" to "Char", "java.lang.Object" to "Any", "java.util.List" to "List", "java.util.Map" to "Map",
        "java.util.Set" to "Set", "java.util.Collection" to "Collection", "java.lang.Iterable" to "Iterable",
    )
    private val KOTLIN_PRIMITIVES = mapOf(
        "int" to "Int", "long" to "Long", "boolean" to "Boolean", "double" to "Double", "float" to "Float",
        "short" to "Short", "byte" to "Byte", "char" to "Char",
    )

    /** [type] as written in a test (boxed in Java, Kotlin names in Kotlin); the classes it names go to [imports]. */
    internal fun typeText(type: PsiType, kotlin: Boolean, imports: MutableSet<String>): String = when (type) {
        is PsiPrimitiveType ->
            if (kotlin) KOTLIN_PRIMITIVES[type.canonicalText] ?: "Any" else type.getBoxedTypeName() ?: "Object"
        is PsiArrayType -> {
            val component = type.componentType
            if (kotlin) {
                if (component is PsiPrimitiveType) "${KOTLIN_PRIMITIVES[component.canonicalText] ?: "Any"}Array"
                else "Array<${typeText(component, true, imports)}>"
            } else "${typeText(component, false, imports).let { if (component is PsiPrimitiveType) component.canonicalText else it }}[]"
        }
        is PsiWildcardType -> {
            val bound = type.bound
            when {
                bound == null -> if (kotlin) "*" else "?"
                type.isExtends -> if (kotlin) "out ${typeText(bound, true, imports)}" else "? extends ${typeText(bound, false, imports)}"
                else -> if (kotlin) "in ${typeText(bound, true, imports)}" else "? super ${typeText(bound, false, imports)}"
            }
        }
        is PsiClassType -> {
            val cls = type.resolve()
            val fqn = cls?.qualifiedName
            val args = type.parameters.takeIf { it.isNotEmpty() }?.joinToString(", ", "<", ">") { typeText(it, kotlin, imports) }.orEmpty()
            val simple = when {
                fqn == null -> type.className
                kotlin && fqn in KOTLIN_NAMES -> KOTLIN_NAMES.getValue(fqn)
                else -> {
                    // a nested class is written as Outer.Inner, importing the outermost class
                    var top = cls!!
                    while (top.containingClass != null) top = top.containingClass!!
                    top.qualifiedName?.let { if (!it.startsWith("java.lang.") || it.count { c -> c == '.' } > 2) imports += it }
                    fqn.removePrefix(top.qualifiedName.orEmpty().substringBeforeLast('.', "")).trimStart('.')
                }
            }
            simple + args
        }
        else -> if (kotlin) "Any" else "Object"
    }

    /** Writes the test into the module's test folder (Kotlin or Java one by the language) and opens it. Call on the EDT. */
    fun write(project: Project, plan: Plan): Result<VirtualFile> = runCatching {
        val roots = ModuleRootManager.getInstance(plan.module).getSourceRoots(JavaSourceRootType.TEST_SOURCE)
        val language = if (plan.test.kotlin) "/kotlin" else "/java"
        val root = roots.firstOrNull { it.path.endsWith(language) } ?: roots.firstOrNull()
            ?: error("The module ${plan.module.name} has no test source folder. Mark one (for example src/test/java) and try again.")
        val extension = if (plan.test.kotlin) "kt" else "java"
        val text = RecordedTestText.render(plan.test)
        val file = WriteCommandAction.writeCommandAction(project).withName("Generate Test from Recording").compute<PsiFile, RuntimeException> {
            val base = PsiManager.getInstance(project).findDirectory(root) ?: error("Cannot open ${root.path}")
            val dir = PackageUtil.findOrCreateDirectoryForPackage(plan.module, plan.test.packageName, base, false)
                ?: error("Cannot create the package ${plan.test.packageName}")
            val name = "${plan.test.className}.$extension"
            require(dir.findFile(name) == null) { "$name exists already" }
            val created = PsiFileFactory.getInstance(project)
                .createFileFromText(name, FileTypeManager.getInstance().getFileTypeByExtension(extension), text)
            dir.add(created) as PsiFile
        }
        val vf = file.virtualFile ?: error("The test was not written")
        FileEditorManager.getInstance(project).openFile(vf, true)
        vf
    }
}
