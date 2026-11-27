package dev.tendril.mocks

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiEllipsisType
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.TypeConversionUtil
import dev.tendril.endpoints.EndpointScanner
import dev.tendril.feign.FeignResolver
import dev.tendril.feign.FeignScanner
import dev.tendril.recordings.Recording
import dev.tendril.recordings.RecordingKind
import java.net.URI

/** New mocks: from a Feign or controller method of the code, or from a recorded call. */
object MockFactory {

    /** Feign's configKey of [method]: `OrdersClient#get(String)`, raw simple type names. Needs a read action. */
    fun configKey(method: PsiMethod): String {
        val params = method.parameterList.parameters.joinToString(",") { p ->
            val type = (p.type as? PsiEllipsisType)?.toArrayType() ?: p.type
            TypeConversionUtil.erasure(type).presentableText
        }
        return "${method.containingClass?.name}#${method.name}($params)"
    }

    /**
     * A mock of a Feign client method (answers its calls) or of a controller handler (answers requests to it),
     * with a sample body built from the return type. Null for any other method. Needs a read action.
     */
    fun forMethod(project: Project, method: PsiMethod): MockDefinition? {
        val cls = method.containingClass ?: return null
        val label = "${cls.name}.${method.name}"
        val body = SampleJson.of(method.returnType)
        val headers = if (body != null && !(body.isJsonPrimitive && body.asJsonPrimitive.isString)) mapOf("Content-Type" to "application/json") else emptyMap()
        if (cls.isInterface && FeignScanner.isClient(cls)) {
            return definition(MockDefinition.FEIGN, configKey(method), label, null, null, 200, headers, body)
        }
        val target = method.navigationElement
        val endpoint = EndpointScanner.cached(project).firstOrNull { it.method.element?.navigationElement == target } ?: return null
        val contextPath = FeignResolver.appValue(project, "server.servlet.context-path", method)?.trim().orEmpty()
        val path = EndpointScanner.join(contextPath, endpoint.path)
        return definition(
            MockDefinition.HTTP, null, label, endpoint.httpMethod.takeIf { it != "ANY" }, path, 200, headers, body,
        )
    }

    private val SKIPPED_HEADERS = setOf("content-length", "transfer-encoding", "connection", "keep-alive", "date", "set-cookie", "vary")

    /** A mock answering like [r] did; null when it cannot be one (a message, no response, a binary body). */
    fun fromRecording(r: Recording): MockDefinition? {
        if (r.kind == RecordingKind.MESSAGE || r.status == null || r.responseBodyEncoding == "base64") return null
        val path = runCatching { URI.create(r.url.orEmpty()).rawPath }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val headers = r.responseHeaders
            .filter { it.first.lowercase() !in SKIPPED_HEADERS && it.second != "***" }
            .associateTo(LinkedHashMap()) { it.first to it.second }
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        val body: JsonElement? = r.responseBody?.let { text ->
            if (contentType?.contains("json", ignoreCase = true) != false) runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { !it.isJsonPrimitive }
                ?: JsonPrimitive(text)
            else JsonPrimitive(text)
        }
        return if (r.kind == RecordingKind.OUTGOING) {
            val key = r.client ?: return null
            definition(MockDefinition.FEIGN, key, key.replace('#', '.').substringBefore('('), r.method, path, r.status, headers, body)
        } else {
            definition(MockDefinition.HTTP, null, r.handler ?: path, r.method, path, r.status, headers, body)
        }
    }

    private fun definition(
        kind: String, target: String?, label: String, method: String?, path: String?, status: Int,
        headers: Map<String, String>, body: JsonElement?,
    ) = MockDefinition(
        kind = kind, target = target, label = label, enabled = true, method = method, path = path,
        query = emptyMap(), headers = emptyMap(), bodyContains = null, status = status, responseHeaders = headers,
        body = body, delayMs = 0, fault = null,
    )
}
