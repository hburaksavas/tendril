package dev.tendril.mocks

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * A mocked response as written in `.tendril/mocks/<name>.json`:
 *
 * ```json
 * {
 *   "kind": "feign",                          // or "http": an endpoint of this application
 *   "target": "OrdersClient#get(String)",     // feign: the client method (Feign's configKey)
 *   "label": "OrdersClient.get",
 *   "enabled": true,
 *   "when": { "method": "GET", "path": "/v1/orders/42", "query": {}, "headers": {}, "bodyContains": null },
 *   "response": { "status": 200, "headers": { "Content-Type": "application/json" }, "body": { ... },
 *                 "delayMs": 0, "fault": null }
 * }
 * ```
 *
 * `when.path` is required for an endpoint (the full path, context path included; `{x}` and `*` are one segment,
 * `**` the rest) and optional for a Feign method (the end of the called path). `fault` is `timeout` or `refused`,
 * Feign only. A body that is a JSON string is sent as text, anything else as JSON.
 */
data class MockDefinition(
    val kind: String,
    val target: String?,
    val label: String,
    val enabled: Boolean,
    val method: String?,
    val path: String?,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val bodyContains: String?,
    val status: Int,
    val responseHeaders: Map<String, String>,
    val body: JsonElement?,
    val delayMs: Long,
    val fault: String?,
) {
    val isFeign get() = kind == FEIGN

    /** The body as sent: a JSON string as its text, any other JSON as JSON. */
    val bodyText: String
        get() = when {
            body == null || body.isJsonNull -> ""
            body.isJsonPrimitive && body.asJsonPrimitive.isString -> body.asString
            else -> MockFiles.compact.toJson(body)
        }

    /** The response headers as sent; a JSON body without a Content-Type gets `application/json`. */
    val effectiveHeaders: Map<String, String>
        get() {
            val out = LinkedHashMap(responseHeaders)
            val isJson = body != null && !body.isJsonNull && !(body.isJsonPrimitive && body.asJsonPrimitive.isString)
            if (isJson && out.keys.none { it.equals("Content-Type", ignoreCase = true) }) out["Content-Type"] = "application/json"
            return out
        }

    /** Specific mocks first: conditions weigh more than literal path segments. */
    val specificity: Int
        get() {
            val literals = path.orEmpty().split('/').count { it.isNotEmpty() && it != "*" && it != "**" && !it.startsWith("{") }
            return 4 * (query.size + headers.size + (if (bodyContains.isNullOrEmpty()) 0 else 1)) + 2 * literals +
                (if (method != null) 1 else 0)
        }

    /** One line for lists: `GET /v1/orders/42 → 404, 300 ms`. */
    val summary: String
        get() = buildString {
            append(method ?: "ANY")
            append(' ')
            append(path ?: if (isFeign) "any call" else "?")
            if (query.isNotEmpty()) append(query.entries.joinToString("&", "?") { "${it.key}=${it.value}" })
            append("  →  ")
            append(fault?.let { "fault: $it" } ?: status.toString())
            if (delayMs > 0) append(", $delayMs ms")
        }

    companion object {
        const val FEIGN = "feign"
        const val HTTP = "http"
    }
}

/** A file of the mocks folder: its definition, or why it could not be read. */
data class MockEntry(val file: Path, val definition: MockDefinition?, val error: String?) {
    val name: String get() = file.fileName.toString().removeSuffix(".json")
}

object MockFiles {
    internal val compact = GsonBuilder().disableHtmlEscaping().create()
    private val pretty = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().serializeNulls().create()

    /** `<project>/.tendril/mocks`. */
    fun directory(basePath: String): Path = Path.of(basePath, ".tendril", "mocks")

    fun parse(text: String): MockDefinition {
        val o = JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject ?: error("Not a JSON object")
        val kind = o.str("kind")?.lowercase() ?: error("\"kind\" is missing: \"feign\" or \"http\"")
        require(kind == MockDefinition.FEIGN || kind == MockDefinition.HTTP) { "\"kind\" is \"feign\" or \"http\", not \"$kind\"" }
        val w = o.obj("when")
        val r = o.obj("response")
        val definition = MockDefinition(
            kind = kind,
            target = o.str("target"),
            label = o.str("label").orEmpty(),
            enabled = o.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
            method = w?.str("method")?.uppercase()?.takeIf { it.isNotBlank() && it != "ANY" },
            path = w?.str("path")?.takeIf { it.isNotBlank() },
            query = w?.map("query").orEmpty(),
            headers = w?.map("headers").orEmpty(),
            bodyContains = w?.str("bodyContains")?.takeIf { it.isNotEmpty() },
            status = r?.get("status")?.takeIf { it.isJsonPrimitive }?.asInt ?: 200,
            responseHeaders = r?.map("headers").orEmpty(),
            body = r?.get("body")?.takeIf { !it.isJsonNull },
            delayMs = r?.get("delayMs")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0,
            fault = r?.str("fault")?.lowercase()?.takeIf { it.isNotBlank() },
        )
        if (definition.isFeign) require(!definition.target.isNullOrBlank()) { "A Feign mock needs \"target\", the client method" }
        else require(definition.path != null) { "An endpoint mock needs \"when.path\"" }
        require(definition.fault == null || definition.fault in FAULTS) { "\"fault\" is \"timeout\" or \"refused\"" }
        require(definition.fault == null || definition.isFeign) { "\"fault\" works for Feign mocks only" }
        require(definition.status in 100..599) { "\"status\" must be an HTTP status" }
        return definition
    }

    private val FAULTS = setOf("timeout", "refused")

    fun toText(d: MockDefinition): String {
        val o = JsonObject()
        o.addProperty("kind", d.kind)
        if (d.isFeign) o.addProperty("target", d.target)
        o.addProperty("label", d.label)
        o.addProperty("enabled", d.enabled)
        o.add("when", JsonObject().apply {
            addProperty("method", d.method)
            addProperty("path", d.path)
            add("query", d.query.toJson())
            add("headers", d.headers.toJson())
            if (d.isFeign) addProperty("bodyContains", d.bodyContains)
        })
        o.add("response", JsonObject().apply {
            addProperty("status", d.status)
            add("headers", d.responseHeaders.toJson())
            add("body", d.body)
            addProperty("delayMs", d.delayMs)
            if (d.isFeign) addProperty("fault", d.fault)
        })
        return pretty.toJson(o) + "\n"
    }

    /** [text] with `"enabled"` set, everything else kept. */
    fun withEnabled(text: String, enabled: Boolean): String {
        val o = JsonParser.parseString(text).asJsonObject
        val copy = JsonObject() // rebuilt to keep the key order; a missing "enabled" goes after "label"
        for ((k, v) in o.entrySet()) {
            copy.add(k, if (k == "enabled") JsonPrimitive(enabled) else v)
            if (k == "label" && !o.has("enabled")) copy.addProperty("enabled", enabled)
        }
        if (!copy.has("enabled")) copy.addProperty("enabled", enabled)
        return pretty.toJson(copy) + "\n"
    }

    fun read(directory: Path): List<MockEntry> {
        if (!Files.isDirectory(directory)) return emptyList()
        val files = Files.list(directory).use { s -> s.filter { it.fileName.toString().endsWith(".json") && Files.isRegularFile(it) }.sorted().toList() }
        return files.map { file ->
            runCatching { MockEntry(file, parse(Files.readString(file)), null) }
                .getOrElse { MockEntry(file, null, it.message ?: it.javaClass.simpleName) }
        }
    }

    /** The enabled mocks in the order the agent tries them: most specific first, then by file name. */
    fun active(entries: List<MockEntry>): List<Pair<String, MockDefinition>> =
        entries.mapNotNull { e -> e.definition?.takeIf { it.enabled }?.let { e.name to it } }
            .sortedByDescending { it.second.specificity }

    /** The `/mocks` form of the recorder agent (see `Mocks.parse` in the recorder), with its version. */
    fun form(rules: List<Pair<String, MockDefinition>>): Pair<String, String> {
        val pairs = mutableListOf<Pair<String, String>>()
        pairs += "count" to rules.size.toString()
        rules.forEachIndexed { i, (id, d) ->
            val p = "$i."
            pairs += p + "id" to id
            pairs += p + "kind" to d.kind
            d.target?.let { pairs += p + "key" to it }
            pairs += p + "label" to d.label.ifEmpty { id }
            d.method?.let { pairs += p + "method" to it }
            d.path?.let { pairs += p + "path" to it }
            d.query.forEach { (k, v) -> pairs += "${p}q.$k" to v }
            d.headers.forEach { (k, v) -> pairs += "${p}h.$k" to v }
            d.bodyContains?.let { pairs += p + "bodyContains" to it }
            pairs += p + "status" to d.status.toString()
            d.effectiveHeaders.entries.forEachIndexed { n, (k, v) -> pairs += "${p}rh.$n" to "$k: $v" }
            pairs += p + "body" to d.bodyText
            if (d.delayMs > 0) pairs += p + "delayMs" to d.delayMs.toString()
            d.fault?.let { pairs += p + "fault" to it }
        }
        fun encode(list: List<Pair<String, String>>) = list.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(encode(pairs).toByteArray())
        val version = digest.take(8).joinToString("") { "%02x".format(it) }
        return encode(listOf("version" to version) + pairs) to version
    }

    /** A file name for [label] that is not taken yet in [directory]: `OrdersClient.get.json`, `OrdersClient.get-2.json`. */
    fun freeName(directory: Path, label: String): String {
        val base = label.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').ifEmpty { "mock" }
        var name = "$base.json"
        var n = 2
        while (Files.exists(directory.resolve(name))) name = "$base-${n++}.json"
        return name
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.map(key: String): Map<String, String> =
        obj(key)?.entrySet()?.filter { it.value.isJsonPrimitive }?.associateTo(LinkedHashMap()) { it.key to it.value.asString }.orEmpty()

    private fun Map<String, String>.toJson() = JsonObject().also { o -> forEach { (k, v) -> o.addProperty(k, v) } }
}
