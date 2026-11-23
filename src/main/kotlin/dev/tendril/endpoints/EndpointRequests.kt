package dev.tendril.endpoints

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import dev.tendril.config.ConfigNavigation
import dev.tendril.config.ConfigProfiles
import dev.tendril.config.firstNonBlank

/** Turns an [Endpoint] into something you can send: URL, cURL command, or an `.http` request. Needs a read action. */
object EndpointRequests {
    private const val PKG = "org.springframework.web.bind.annotation."
    private const val REQUEST_BODY = PKG + "RequestBody"
    private const val REQUEST_PARAM = PKG + "RequestParam"
    private val NUMBERS = setOf(
        "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
        "java.lang.Double", "java.lang.Float", "java.math.BigDecimal", "java.math.BigInteger",
    )

    fun url(project: Project, e: Endpoint): String = baseUrl(project) + e.path

    fun curl(project: Project, e: Endpoint): String {
        val parts = mutableListOf("curl -g -X ${verb(e)}")
        val body = body(e)
        if (body != null) parts += "-H 'Content-Type: application/json'"
        parts += "'${url(project, e)}${query(e)}'"
        if (body != null) parts += "-d '${body.replace("'", "'\\''")}'"
        return parts.joinToString(" \\\n  ")
    }

    /** JetBrains HTTP Client format; path variables become `{{name}}` placeholders. */
    fun http(project: Project, e: Endpoint): String = buildString {
        val path = Regex("\\{([^}/]+)}").replace(e.path) { "{{${it.groupValues[1].substringBefore(':')}}}" }
        append("### ${e.className}.${e.methodName}\n")
        append("${verb(e)} ${baseUrl(project)}$path${query(e)}\n")
        val body = body(e)
        if (body != null) append("Content-Type: application/json\n\n").append(body).append('\n')
    }

    private fun verb(e: Endpoint) = verbOf(e.httpMethod)

    internal fun verbOf(httpMethod: String) = if (httpMethod == "ANY") "GET" else httpMethod

    /** `{id}` / `{id:regex}` -> `{{id}}` for the HTTP client. */
    internal fun httpPath(path: String): String =
        Regex("\\{([^}/]+)}").replace(path) { "{{${it.groupValues[1].substringBefore(':')}}}" }

    /** Opens [text] as a scratch `.http` file. */
    internal fun openHttpScratch(project: Project, fileName: String, text: String) {
        val file = com.intellij.ide.scratch.ScratchRootType.getInstance()
            .createScratchFile(project, fileName, null, text) ?: return
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** http://localhost:<server.port or 8080><server.servlet.context-path>, read from the project's config files. */
    fun baseUrl(project: Project): String {
        val port = configValue(project, "server.port")?.takeIf { it.all(Char::isDigit) } ?: "8080"
        val context = configValue(project, "server.servlet.context-path")?.trim('/')?.takeIf { it.isNotEmpty() }
        return "http://localhost:$port" + (context?.let { "/$it" } ?: "")
    }

    /**
     * Where the actuator answers, from the config files: `management.server.port` when the management endpoints run
     * on their own port, else the server's; `management.endpoints.web.base-path` (default `/actuator`).
     */
    fun actuatorUrl(project: Project): String {
        fun digits(key: String) = configValue(project, key)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val managementPort = digits("management.server.port")
        val port = managementPort ?: digits("server.port") ?: "8080"
        val context = if (managementPort != null) configValue(project, "management.server.base-path")
        else configValue(project, "server.servlet.context-path")
        val base = configValue(project, "management.endpoints.web.base-path")?.trim('/')?.takeIf { it.isNotEmpty() } ?: "actuator"
        return "http://localhost:$port" + (context?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "/$it" } ?: "") + "/$base"
    }

    private fun configValue(project: Project, key: String): String? {
        val profiles = ConfigProfiles.effectiveProfiles(project, dev.tendril.feign.FeignResolver.selectedProfile(project))
        return dev.tendril.feign.FeignResolver.valueFor(ConfigNavigation.allConfigElements(project, key), profiles)
    }

    private fun query(e: Endpoint): String = queryOf(e.method.element)

    internal fun queryOf(method: PsiMethod?): String {
        method ?: return ""
        val names = method.parameterList.parameters.mapNotNull { p ->
            val ann = p.getAnnotation(REQUEST_PARAM) ?: return@mapNotNull null
            ann.firstNonBlank("value", "name") ?: p.name
        }
        return if (names.isEmpty()) "" else names.joinToString("&", prefix = "?") { "$it=value" }
    }

    private fun body(e: Endpoint): String? = bodyOf(e.method.element)

    internal fun bodyOf(method: PsiMethod?): String? {
        val param = method?.parameterList?.parameters?.firstOrNull { it.getAnnotation(REQUEST_BODY) != null }
            ?: return null
        return skeleton(param.type, 0, 0)
    }

    /** A JSON template for [type]: scalar defaults, nested objects up to three levels. */
    private fun skeleton(type: PsiType, depth: Int, indent: Int): String {
        if (type is PsiPrimitiveType) return when (type.canonicalText) {
            "boolean" -> "false"
            "char" -> "\"a\""
            else -> "0"
        }
        if (type is PsiArrayType) return "[]"
        val cls = (type as? PsiClassType)?.resolve() ?: return "null"
        val q = cls.qualifiedName.orEmpty()
        return when {
            q == "java.lang.String" || q == "java.lang.CharSequence" -> "\"string\""
            q in NUMBERS -> "0"
            q == "java.lang.Boolean" -> "false"
            q.startsWith("java.time.") || q == "java.util.Date" -> "\"2024-01-01T00:00:00\""
            q == "java.util.Map" || q.endsWith("Map") && q.startsWith("java.util.") -> "{}"
            q.startsWith("java.util.") -> "[]"
            q.startsWith("java.") -> "null"
            cls.isEnum -> "\"${cls.fields.filterIsInstance<PsiEnumConstant>().firstOrNull()?.name ?: ""}\""
            depth >= 3 -> "{}"
            else -> {
                val fields = cls.allFields.filter { !it.hasModifierProperty(PsiModifier.STATIC) }
                if (fields.isEmpty()) "{}" else {
                    val pad = "  ".repeat(indent + 1)
                    fields.joinToString(",\n", prefix = "{\n", postfix = "\n${"  ".repeat(indent)}}") {
                        "$pad\"${it.name}\": ${skeleton(it.type, depth + 1, indent + 1)}"
                    }
                }
            }
        }
    }
}
