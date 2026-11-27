package dev.tendril.feign

import com.intellij.psi.PsiMethod
import dev.tendril.config.firstNonBlank
import dev.tendril.endpoints.EndpointRequests
import dev.tendril.endpoints.EndpointScanner

/** Turns a Feign call into an address, a cURL command or an `.http` request. Needs a read action. */
object FeignRequests {
    private const val REQUEST_HEADER = "org.springframework.web.bind.annotation.RequestHeader"

    fun path(info: FeignClientInfo, call: FeignCall): String = EndpointScanner.join(info.path, call.subPath)

    /** Full address; when no base address resolves, `{{baseUrl}}` stands in so the request stays editable. */
    fun url(info: FeignClientInfo, call: FeignCall): String =
        (info.resolution.url ?: "{{baseUrl}}") + path(info, call).let { if (it == "/") "" else it }

    fun curl(info: FeignClientInfo, call: FeignCall): String {
        val method = call.method.element
        val parts = mutableListOf("curl -g -X ${EndpointRequests.verbOf(call.httpMethod)}")
        for ((header, value) in headers(method)) parts += "-H '$header: $value'"
        val body = EndpointRequests.bodyOf(method, implicit = true, oneLine = true)
        if (body != null) parts += "-H 'Content-Type: application/json'"
        parts += "'${url(info, call)}${EndpointRequests.queryOf(method)}'"
        if (body != null) parts += "-d '${body.replace("'", "'\\''")}'"
        return parts.joinToString(" \\\n  ")
    }

    fun http(info: FeignClientInfo, call: FeignCall): String = buildString {
        val method = call.method.element
        append("### ${info.def.className}.${call.methodName}  (${info.resolution.source})\n")
        if (info.resolution.url == null) append("# No address resolved: set {{baseUrl}} or add the service in Tendril\n")
        append("${EndpointRequests.verbOf(call.httpMethod)} ")
        val route = path(info, call).let { if (it == "/") "" else it }
        append(info.resolution.url ?: "{{baseUrl}}").append(EndpointRequests.httpPath(route))
            .append(EndpointRequests.queryOf(method)).append('\n')
        for ((header, value) in headers(method)) append("$header: $value\n")
        append("# Authorization: Bearer {{token}}\n")
        val body = EndpointRequests.bodyOf(method, implicit = true)
        if (body != null) append("Content-Type: application/json\n\n").append(body).append('\n')
    }

    /** `@RequestHeader` parameters as `Name: {{name}}` placeholders. */
    private fun headers(method: PsiMethod?): List<Pair<String, String>> =
        method?.parameterList?.parameters.orEmpty().mapNotNull { p ->
            val ann = p.getAnnotation(REQUEST_HEADER) ?: return@mapNotNull null
            val name = ann.firstNonBlank("value", "name") ?: p.name
            name to "{{${name.replace(Regex("[^A-Za-z0-9]+"), "_")}}}"
        }

    fun fileName(info: FeignClientInfo, call: FeignCall): String =
        "${call.httpMethod}_${info.def.className}_${call.methodName}.http"
}
