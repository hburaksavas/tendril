package dev.tendril.http

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.random.Random

class ParsedRequest(val method: String, val url: String, val headers: List<Pair<String, String>>, val body: String?)

class HttpResult(
    val status: Int,
    val headers: List<Pair<String, String>>,
    val body: String,
    val millis: Long,
    val bytes: Int,
)

/** A small reader for the `.http` request format Tendril generates (and the common subset people write by hand). */
object HttpRequestText {
    private val VARIABLE = Regex("""\{\{\s*([^{}]+?)\s*}}""")
    private val FILE_VARIABLE = Regex("""^@(\w[\w.-]*)\s*=\s*(.*)$""")
    private val METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE")

    /** Names of the `{{variables}}` used in [text]; `{{$uuid}}`-style dynamic ones are not included. */
    fun variables(text: String): List<String> =
        VARIABLE.findAll(text).map { it.groupValues[1] }.filter { !it.startsWith("$") }.distinct().toList()

    /** File-level variables: lines of the form `@name = value`. */
    fun fileVariables(text: String): Map<String, String> =
        text.lines().mapNotNull { FILE_VARIABLE.matchEntire(it.trim())?.let { m -> m.groupValues[1] to m.groupValues[2].trim() } }
            .toMap()

    /** The request that contains line [line]; requests are separated by lines starting with `###`. */
    fun block(text: String, line: Int): String {
        val lines = text.lines()
        val separators = lines.indices.filter { lines[it].startsWith("###") }
        if (separators.isEmpty()) return text
        val index = separators.indexOfLast { it <= line }.coerceAtLeast(0)
        val start = separators[index]
        val end = separators.getOrElse(index + 1) { lines.size }
        return lines.subList(start, end).joinToString("\n")
    }

    fun parse(block: String, variables: Map<String, String>): Result<ParsedRequest> = runCatching {
        val lines = block.lines()
        val requestLine = lines.indexOfFirst {
            val t = it.trim()
            t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("//") && !FILE_VARIABLE.matches(t)
        }
        require(requestLine >= 0) { "No request found. Write a line like: GET http://localhost:8080/api" }

        val head = substitute(lines[requestLine].trim(), variables)
        val parts = head.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val first = parts.first().uppercase()
        val (method, url) = if (first in METHODS && parts.size > 1) first to parts[1] else "GET" to parts.first()

        val headers = mutableListOf<Pair<String, String>>()
        var i = requestLine + 1
        while (i < lines.size && lines[i].isNotBlank()) {
            val t = lines[i].trim()
            i++
            if (t.startsWith("#") || t.startsWith("//")) continue
            val colon = t.indexOf(':')
            if (colon > 0) headers += t.substring(0, colon).trim() to substitute(t.substring(colon + 1).trim(), variables)
        }
        val body = lines.drop(i + 1).joinToString("\n").trim().takeIf { it.isNotEmpty() }?.let { substitute(it, variables) }

        val unresolved = (listOf(url) + headers.map { it.second } + listOfNotNull(body))
            .flatMap { s -> VARIABLE.findAll(s).map { it.groupValues[1] } }.distinct()
        require(unresolved.isEmpty()) {
            "Undefined variable(s): ${unresolved.joinToString(", ")}. Define them as '@name = value' lines " +
                "or in http-client.env.json"
        }
        ParsedRequest(method, url, headers, body)
    }

    private fun substitute(text: String, variables: Map<String, String>): String =
        VARIABLE.replace(text) { m ->
            val name = m.groupValues[1]
            variables[name]?.takeIf { it.isNotEmpty() } ?: dynamic(name) ?: m.value
        }

    private fun dynamic(name: String): String? = when (name) {
        "\$uuid" -> UUID.randomUUID().toString()
        "\$timestamp" -> (System.currentTimeMillis() / 1000).toString()
        "\$randomInt" -> Random.nextInt(0, 1000).toString()
        else -> null
    }
}

/** `http-client.env.json` + `http-client.private.env.json` in the project root: environment -> variables. */
object HttpEnvironments {
    fun load(project: Project): Map<String, Map<String, String>> {
        val base = project.basePath?.let { Path.of(it) } ?: return emptyMap()
        val merged = linkedMapOf<String, MutableMap<String, String>>()
        for (file in listOf("http-client.env.json", "http-client.private.env.json")) {
            val path = base.resolve(file)
            if (!Files.isRegularFile(path)) continue
            val root = runCatching { JsonParser.parseString(Files.readString(path)).asJsonObject }.getOrNull() ?: continue
            for ((env, vars) in root.entrySet()) {
                if (!vars.isJsonObject) continue
                val target = merged.getOrPut(env) { linkedMapOf() }
                for ((k, v) in vars.asJsonObject.entrySet()) if (v.isJsonPrimitive) target[k] = v.asString
            }
        }
        return merged
    }
}

object HttpExecutor {
    fun send(request: ParsedRequest): HttpResult {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .apply {
                // The IDE's trust store (system + user-accepted certificates). Proxy: the JVM default selector,
                // which the IDE replaces with its own proxy settings, is used implicitly.
                runCatching { com.intellij.util.net.ssl.CertificateManager.getInstance().sslContext }.getOrNull()?.let { sslContext(it) }
            }
            .build()

        val publisher = request.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val builder = HttpRequest.newBuilder(URI.create(request.url)).timeout(Duration.ofSeconds(60))
            .method(request.method, publisher)
        for ((name, value) in request.headers) runCatching { builder.header(name, value) } // restricted ones are skipped

        val started = System.nanoTime()
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        val millis = (System.nanoTime() - started) / 1_000_000
        val bytes = response.body()
        val headers = response.headers().map().entries.sortedBy { it.key }.flatMap { (k, vs) -> vs.map { k to it } }
        return HttpResult(response.statusCode(), headers, String(bytes, StandardCharsets.UTF_8), millis, bytes.size)
    }

    /** Pretty-prints JSON, returns anything else unchanged. */
    fun pretty(body: String): String {
        val t = body.trim()
        if (!(t.startsWith("{") || t.startsWith("["))) return body
        return runCatching { GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(t)) }.getOrDefault(body)
    }

    fun reason(status: Int): String = when (status) {
        200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
        301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        409 -> "Conflict"; 415 -> "Unsupported Media Type"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
        else -> ""
    }
}
