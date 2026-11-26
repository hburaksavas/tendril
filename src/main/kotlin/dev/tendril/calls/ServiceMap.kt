package dev.tendril.calls

/**
 * The service map of one project: the applications it holds, their endpoints and their Feign calls, and which Feign
 * calls each endpoint makes (through the services it calls). Built from PSI while the project is open
 * ([ServiceSnapshots]) and saved to disk ([ServiceMapStore]), so that other windows can read it while it is closed.
 * Plain data, serialised with Gson.
 */
class ProjectSnapshot(
    val version: Int,
    val projectPath: String,
    val projectName: String,
    val generatedAt: Long,
    val apps: List<AppInfo>,
    val endpoints: List<EndpointNode>,
    val feign: List<FeignNode>,
) {
    companion object {
        const val VERSION = 1
    }
}

/** Where something is in the sources: an absolute path with forward slashes and a 0-based line. */
data class Location(val file: String, val line: Int)

/** One Spring application (one module): `spring.application.name`, `server.port` and the context path. */
data class AppInfo(val name: String, val port: String, val contextPath: String)

/** A handler method; [path] is without the context path. [feignCalls] are indexes into [ProjectSnapshot.feign]. */
data class EndpointNode(
    val app: Int,
    val httpMethod: String,
    val path: String,
    val handler: String,
    val location: Location?,
    val feignCalls: List<Int>,
)

/** A mapped method of a Feign client; [path] includes the client's `path`, [url] is the resolved base address. */
data class FeignNode(
    val app: Int,
    val client: String,
    val service: String,
    val url: String?,
    val httpMethod: String,
    val path: String,
    val handler: String,
    val location: Location?,
)

/**
 * A `fetch(...)` call of a frontend. [url] is `http://host:port/path`, `/path`, or a path prefixed with `*` when the base address is an
 * expression that cannot be resolved. [httpMethod] is null when the call passes its options as a variable.
 */
data class FetchCall(val httpMethod: String?, val url: String, val location: Location)

/** URL and path comparison shared by the Feign and fetch matching. */
object CallMatching {
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "host.docker.internal", "[::1]")

    class Url(val host: String?, val port: String?, val path: String)

    /** `http://localhost:8081/api` -> localhost, 8081, /api. A string without a scheme is a path. */
    fun parseUrl(url: String): Url {
        val match = Regex("^(https?)://([^/:?#]+)(?::(\\d+))?([^?#]*)").find(url.trim())
            ?: return Url(null, null, url.substringBefore('?').substringBefore('#'))
        val (scheme, host, port, path) = match.destructured
        return Url(host.lowercase(), port.ifEmpty { if (scheme == "https") "443" else "80" }, path)
    }

    fun isLocal(host: String?) = host != null && host in LOCAL_HOSTS

    /** `orders-service`, `ORDERS_SERVICE` and `ordersService` are the same service. */
    fun serviceKey(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    fun join(a: String, b: String): String {
        val s = ("/" + a.trim('/') + "/" + b.trim('/')).replace(Regex("/+"), "/")
        return if (s.length > 1) s.trimEnd('/') else s
    }

    private fun isVariable(segment: String) = segment == "*" || (segment.startsWith("{") && segment.endsWith("}"))

    /**
     * Whether a call to [call] reaches the mapping [endpoint]. Path variables (`{id}`) on either side and `*` in the
     * call match any one segment, `**` in the mapping matches the rest. With [suffix] the call may lack a leading part
     * of the path (its base address was not known).
     */
    fun pathMatches(call: String, endpoint: String, suffix: Boolean = false): Boolean {
        val c = segments(call)
        val e = segments(endpoint)
        if (suffix) {
            if (c.none { !isVariable(it) }) return false // `${base}/${id}` says nothing
            for (start in 0..maxOf(0, e.size - c.size)) if (matchFrom(c, e, start)) return true
            return false
        }
        return matchFrom(c, e, 0)
    }

    private fun matchFrom(c: List<String>, e: List<String>, start: Int): Boolean {
        var i = 0
        while (i < c.size) {
            val j = start + i
            if (j >= e.size) return false
            if (e[j] == "**") return true
            if (!isVariable(c[i]) && !isVariable(e[j]) && c[i] != e[j]) return false
            i++
        }
        return start + i == e.size || (start + i == e.size - 1 && e.last() == "**")
    }

    fun methodMatches(call: String?, endpoint: String): Boolean =
        call == null || call == "ANY" || endpoint == "ANY" || call.equals(endpoint, ignoreCase = true)
}

/** A project that takes part in the graph, with what could be read of it. */
class ServiceSource(
    val projectPath: String,
    val name: String,
    val state: State,
    val snapshot: ProjectSnapshot?,
    val fetches: List<FetchCall>,
) {
    enum class State { CURRENT, OPEN, SAVED, NONE }
}

/** One line in the call tree; [location] opens the code, in the window of [projectPath] when that is open. */
class ChainNode(
    val kind: Kind,
    val text: String,
    val detail: String = "",
    val location: Location? = null,
    val projectPath: String? = null,
) {
    enum class Kind { SECTION, ENDPOINT, FEIGN, FETCH, NOTE }

    val children = mutableListOf<ChainNode>()

    override fun toString() = "$text $detail".trim()
}

/** Links the endpoints, Feign calls and fetch calls of several projects. Pure data: no PSI, safe on any thread. */
class ServiceGraph(val sources: List<ServiceSource>) {

    data class EndpointRef(val source: Int, val index: Int)
    data class FeignRef(val source: Int, val index: Int)
    class Target(val endpoint: EndpointRef, val byPathOnly: Boolean)

    private fun snapshot(source: Int) = sources[source].snapshot!!
    fun endpoint(ref: EndpointRef) = snapshot(ref.source).endpoints[ref.index]
    fun feign(ref: FeignRef) = snapshot(ref.source).feign[ref.index]
    private fun app(source: Int, app: Int) = snapshot(source).apps.getOrNull(app) ?: AppInfo("?", "8080", "")

    /** The path a request must have to reach [ref]: context path and mapping. */
    fun fullPath(ref: EndpointRef): String {
        val e = endpoint(ref)
        return CallMatching.join(app(ref.source, e.app).contextPath, e.path)
    }

    private val allEndpoints: List<EndpointRef> = sources.indices.flatMap { s ->
        sources[s].snapshot?.endpoints?.indices?.map { EndpointRef(s, it) }.orEmpty()
    }
    private val allFeign: List<FeignRef> = sources.indices.flatMap { s ->
        sources[s].snapshot?.feign?.indices?.map { FeignRef(s, it) }.orEmpty()
    }
    private val targetCache = HashMap<FeignRef, List<Target>>()

    /**
     * The endpoints a Feign call reaches. The service is found by the address (a local port, or a host named like
     * `spring.application.name`) or, without an address, by the client's name. When no application is that service,
     * any endpoint with the same method and path is taken, marked as matched by path only.
     */
    fun targetsOf(ref: FeignRef): List<Target> = targetCache.getOrPut(ref) {
        val f = feign(ref)
        val url = f.url?.let(CallMatching::parseUrl)
        val callPath = CallMatching.join(url?.path.orEmpty(), f.path)
        fun isSelf(e: EndpointRef) = e.source == ref.source && endpoint(e).app == f.app
        val candidates = allEndpoints.filter { !isSelf(it) && CallMatching.methodMatches(f.httpMethod, endpoint(it).httpMethod) }
        val services = candidates.filter { e ->
            val a = app(e.source, endpoint(e).app)
            when {
                url?.host == null -> CallMatching.serviceKey(f.service) == CallMatching.serviceKey(a.name)
                CallMatching.isLocal(url.host) -> url.port == a.port
                else -> CallMatching.serviceKey(url.host) == CallMatching.serviceKey(a.name) ||
                    CallMatching.serviceKey(f.service) == CallMatching.serviceKey(a.name)
            }
        }
        if (services.isNotEmpty()) {
            services.filter { CallMatching.pathMatches(callPath, fullPath(it)) }.map { Target(it, false) }
        } else {
            candidates.filter { CallMatching.pathMatches(callPath, fullPath(it)) }.map { Target(it, true) }
        }
    }

    fun feignCallersOf(ref: EndpointRef): List<Pair<FeignRef, Boolean>> =
        allFeign.mapNotNull { f -> targetsOf(f).firstOrNull { it.endpoint == ref }?.let { f to it.byPathOnly } }

    /** The endpoints of the Feign call's own project that make it. */
    fun endpointsCalling(ref: FeignRef): List<EndpointRef> =
        snapshot(ref.source).endpoints.indices.filter { ref.index in snapshot(ref.source).endpoints[it].feignCalls }
            .map { EndpointRef(ref.source, it) }

    /** The fetch calls of any source that reach [ref]. */
    fun fetchCallersOf(ref: EndpointRef): List<Pair<Int, FetchCall>> {
        val e = endpoint(ref)
        val a = app(ref.source, e.app)
        val full = fullPath(ref)
        return sources.indices.flatMap { s -> sources[s].fetches.map { s to it } }.filter { (_, call) ->
            if (!CallMatching.methodMatches(call.httpMethod, e.httpMethod)) return@filter false
            when {
                call.url.startsWith("*") -> CallMatching.pathMatches(call.url.removePrefix("*"), full, suffix = true)
                call.url.startsWith("/") -> CallMatching.pathMatches(call.url, full)
                else -> {
                    val url = CallMatching.parseUrl(call.url)
                    val sameService = if (CallMatching.isLocal(url.host)) url.port == a.port
                    else CallMatching.serviceKey(url.host.orEmpty()) == CallMatching.serviceKey(a.name)
                    sameService && CallMatching.pathMatches(url.path, full)
                }
            }
        }
    }

    // ---- trees ----

    fun endpointNode(ref: EndpointRef): ChainNode {
        val e = endpoint(ref)
        return ChainNode(
            ChainNode.Kind.ENDPOINT, "${e.httpMethod} ${fullPath(ref)}",
            "${e.handler} · ${app(ref.source, e.app).name}", e.location, sources[ref.source].projectPath,
        )
    }

    fun feignNode(ref: FeignRef, byPathOnly: Boolean = false): ChainNode {
        val f = feign(ref)
        val detail = listOfNotNull(app(ref.source, f.app).name, f.url ?: f.service.ifBlank { null },
            "matched by path only".takeIf { byPathOnly }).joinToString(" · ")
        return ChainNode(
            ChainNode.Kind.FEIGN, "${f.handler} → ${f.httpMethod} ${f.path}", detail, f.location, sources[ref.source].projectPath,
        )
    }

    private fun fetchNode(source: Int, call: FetchCall): ChainNode {
        val root = sources[source].projectPath.trimEnd('/') + "/"
        val file = call.location.file.removePrefix(root)
        return ChainNode(
            ChainNode.Kind.FETCH, "fetch ${call.httpMethod ?: "?"} ${call.url}",
            "${sources[source].name} · $file:${call.location.line + 1}", call.location, sources[source].projectPath,
        )
    }

    /** Who calls [ref], and who calls them: Feign calls (under the endpoints that make them) and fetch calls. */
    fun callersOf(ref: EndpointRef, depth: Int = 0, seen: MutableSet<Any> = mutableSetOf(ref)): List<ChainNode> {
        if (depth > MAX_DEPTH) return emptyList()
        val out = mutableListOf<ChainNode>()
        for ((f, byPath) in feignCallersOf(ref)) {
            if (!seen.add(f)) continue
            out += feignNode(f, byPath).apply { children += upstreamOf(f, depth + 1, seen) }
        }
        for ((s, call) in fetchCallersOf(ref)) out += fetchNode(s, call)
        return out
    }

    /** The endpoints that make the Feign call [ref], each with its own callers. */
    fun upstreamOf(ref: FeignRef, depth: Int = 0, seen: MutableSet<Any> = mutableSetOf(ref)): List<ChainNode> =
        endpointsCalling(ref).filter { seen.add(it) }.map { e ->
            endpointNode(e).apply { children += callersOf(e, depth + 1, seen) }
        }

    /** The Feign calls [ref] makes, each with the endpoints it reaches and what those call in turn. */
    fun callsOf(ref: EndpointRef, depth: Int = 0, seen: MutableSet<Any> = mutableSetOf(ref)): List<ChainNode> {
        if (depth > MAX_DEPTH) return emptyList()
        return endpoint(ref).feignCalls.map { FeignRef(ref.source, it) }.filter { seen.add(it) }.map { f ->
            feignNode(f).apply { children += downstreamOf(f, depth + 1, seen) }
        }
    }

    /** The endpoints the Feign call [ref] reaches, each with the calls it makes. */
    fun downstreamOf(ref: FeignRef, depth: Int = 0, seen: MutableSet<Any> = mutableSetOf(ref)): List<ChainNode> =
        targetsOf(ref).filter { seen.add(it.endpoint) }.map { t ->
            endpointNode(t.endpoint).apply {
                if (t.byPathOnly) children += ChainNode(ChainNode.Kind.NOTE, "matched by path only: no linked application is this service")
                children += callsOf(t.endpoint, depth + 1, seen)
            }
        }

    companion object {
        private const val MAX_DEPTH = 8
    }
}
