package dev.tendril.endpoints

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import dev.tendril.config.ConfigNavigation
import dev.tendril.config.ConfigProfiles
import dev.tendril.config.firstNonBlank

/** Turns an [Endpoint] into something you can send: URL, cURL command, or an `.http` request. Needs a read action. */
object EndpointRequests {
    private const val PKG = "org.springframework.web.bind.annotation."
    private const val REQUEST_BODY = PKG + "RequestBody"
    private const val REQUEST_PARAM = PKG + "RequestParam"

    fun url(project: Project, e: Endpoint): String = baseUrl(project) + e.path

    fun curl(project: Project, e: Endpoint): String {
        val parts = mutableListOf("curl -g -X ${verb(e)}")
        val body = bodyOf(e.method.element, oneLine = true)
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

    /**
     * http://localhost:<server.port or 8080><server.servlet.context-path>, read from the project's config files for the
     * chosen profile. With [fallback] false only the config of [selected] (null: the default config) is read.
     */
    fun baseUrl(
        project: Project,
        selected: String? = dev.tendril.feign.FeignResolver.selectedProfile(project),
        fallback: Boolean = true,
    ): String {
        val profiles = ConfigProfiles.effectiveProfiles(project, selected)
        fun value(key: String) = dev.tendril.feign.FeignResolver.valueFor(ConfigNavigation.allConfigElements(project, key), profiles, fallback)
        val port = value("server.port")?.takeIf { it.all(Char::isDigit) } ?: "8080"
        val context = value("server.servlet.context-path")?.trim('/')?.takeIf { it.isNotEmpty() }
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

    /**
     * `?a=value&b=value` from the `@RequestParam` parameters, plus the fields of a Feign `@SpringQueryMap` /
     * `@QueryMap` object with sample values; a map there becomes `{{query}}`, since its keys are only known at runtime.
     */
    internal fun queryOf(method: PsiMethod?): String {
        method ?: return ""
        val parts = method.parameterList.parameters.flatMap { p ->
            p.getAnnotation(REQUEST_PARAM)?.let { ann -> return@flatMap listOf("${ann.firstNonBlank("value", "name") ?: p.name}=value") }
            if (QUERY_MAPS.any { p.getAnnotation(it) != null }) queryMapOf(p.type) else emptyList()
        }
        return if (parts.isEmpty()) "" else parts.joinToString("&", prefix = "?")
    }

    private val QUERY_MAPS = listOf("org.springframework.cloud.openfeign.SpringQueryMap", "feign.QueryMap")

    /**
     * The query Feign's encoder writes for an object: one parameter per field (superclass fields too), named by the
     * field or its `@feign.Param`, not by Jackson annotations. Nested objects are left out; a list gives one sample.
     */
    private fun queryMapOf(type: PsiType): List<String> {
        val cls = (type as? PsiClassType)?.resolve() ?: return emptyList()
        if (com.intellij.psi.util.InheritanceUtil.isInheritor(cls, "java.util.Map")) return listOf("{{query}}")
        val fields: List<Pair<String, PsiType>> = if (cls.isRecord) cls.recordComponents.map { it.name to it.type }
        else generateSequence(cls) { it.superClass }.takeWhile { it.qualifiedName?.startsWith("java.") != true }.toList()
            .asReversed().flatMap { c ->
                c.fields.filter {
                    it !is com.intellij.psi.PsiEnumConstant && !it.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC) &&
                        !it.hasModifierProperty(com.intellij.psi.PsiModifier.TRANSIENT)
                }.map { f -> (f.getAnnotation("feign.Param")?.firstNonBlank("value") ?: f.name) to f.type }
            }
        return fields.mapNotNull { (name, fieldType) ->
            val sample = dev.tendril.mocks.SampleJson.of(fieldType)
            val scalar = if (sample is com.google.gson.JsonArray) sample.firstOrNull() else sample
            scalar?.takeIf { it.isJsonPrimitive }?.let { "$name=${java.net.URLEncoder.encode(it.asString, Charsets.UTF_8)}" }
        }
    }

    private fun body(e: Endpoint): String? = bodyOf(e.method.element)

    private val pretty = com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    private val compact = com.google.gson.GsonBuilder().disableHtmlEscaping().create()

    /**
     * A sample JSON body for the `@RequestBody` parameter of [method], built from its type (fields of DTOs, records
     * and Kotlin data classes with `@JsonProperty` names, one element per collection). With [implicit], as for a
     * Feign method, an unannotated parameter is the body too. Pretty-printed, or on one line with [oneLine].
     */
    internal fun bodyOf(method: PsiMethod?, implicit: Boolean = false, oneLine: Boolean = false): String? {
        val params = method?.parameterList?.parameters ?: return null
        val param = params.firstOrNull { it.getAnnotation(REQUEST_BODY) != null }
            ?: params.takeIf { implicit }?.firstOrNull { p -> p.annotations.all(::isNullability) && !isNotBody(p.type) }
            ?: return null
        val sample = dev.tendril.mocks.SampleJson.of(param.type) ?: return null
        return (if (oneLine) compact else pretty).toJson(sample)
    }

    /** Kotlin light parameters carry these without anyone writing them. */
    private fun isNullability(a: com.intellij.psi.PsiAnnotation) = a.qualifiedName?.startsWith("org.jetbrains.annotations.") == true

    /** Parameters Feign handles itself, never the body. */
    private fun isNotBody(type: PsiType): Boolean {
        val name = (type as? PsiClassType)?.resolve()?.qualifiedName ?: return false
        return name == "java.net.URI" || name.startsWith("feign.") || name.startsWith("org.springframework.data.domain.")
    }
}
