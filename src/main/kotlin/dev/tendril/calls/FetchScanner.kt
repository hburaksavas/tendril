package dev.tendril.calls

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Finds the `fetch(...)` calls of a JavaScript or TypeScript frontend by reading its files from disk, so the
 * project does not have to be open (nor IntelliJ to understand JavaScript). Understands:
 *  - a string or template literal: `fetch('/api/orders')`, `` fetch(`${API_URL}/orders/${id}`) ``
 *  - a constant of the same file holding one: `const url = ...; fetch(url)`
 *  - a base address from `.env` files (`process.env.REACT_APP_API_URL`, `import.meta.env.VITE_API_URL`), directly
 *    or through a constant of the same file (`const API = process.env.REACT_APP_API ?? 'http://localhost:8080'`)
 *  - `method: 'POST'` in the options passed inline
 * Pure: no IntelliJ APIs, safe on any thread.
 */
object FetchScanner {
    private val EXTENSIONS = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs")
    private val SKIPPED_DIRS = setOf(
        "node_modules", ".git", ".idea", ".gradle", "build", "dist", "out", "target", ".next", ".nuxt", "coverage",
        "storybook-static", ".turbo", ".cache", "__tests__", "__mocks__", "public",
    )
    private val ENV_FILES = listOf(".env", ".env.development", ".env.local", ".env.development.local")
    private const val MAX_FILES = 20_000
    private const val MAX_FILE_SIZE = 1_000_000L
    private const val TTL_MILLIS = 30_000L

    private val FETCH = Regex("""(?<![\w$])fetch\s*\(""")
    private val METHOD = Regex("""\bmethod\s*:\s*(['"`])([A-Za-z]+)\1""")
    private val ENV_REF = Regex("""env\.([A-Za-z_][A-Za-z0-9_]*)""")
    private const val HOLE = '\u0001'

    private class Cached(val at: Long, val calls: List<FetchCall>)
    private val cache = ConcurrentHashMap<String, Cached>()

    /** The fetch calls under [root], read again at most every 30 seconds. Empty when [root] has no frontend files. */
    fun cached(root: String): List<FetchCall> {
        val now = System.currentTimeMillis()
        cache[root]?.takeIf { now - it.at < TTL_MILLIS }?.let { return it.calls }
        val calls = runCatching { scanRoot(Path.of(root)) }.getOrDefault(emptyList())
        cache[root] = Cached(now, calls)
        return calls
    }

    fun scanRoot(root: Path): List<FetchCall> {
        if (!root.isDirectory()) return emptyList()
        val out = mutableListOf<FetchCall>()
        var files = 0
        fun walk(dir: Path, env: Map<String, String>) {
            val own = if (Files.exists(dir.resolve("package.json"))) env + readEnv(dir) else env
            val children = runCatching { Files.newDirectoryStream(dir).use { it.toList() } }.getOrDefault(emptyList())
            for (child in children.sortedBy { it.name }) {
                if (files >= MAX_FILES) return
                if (child.isDirectory()) {
                    if (child.name !in SKIPPED_DIRS && !child.name.startsWith(".")) walk(child, own)
                } else if (isSource(child)) {
                    files++
                    val text = runCatching { Files.readString(child) }.getOrNull() ?: continue
                    val path = child.toAbsolutePath().toString().replace('\\', '/')
                    for (raw in scanText(text, own)) {
                        out += FetchCall(raw.method, raw.url, Location(path, lineOf(text, raw.offset)))
                    }
                }
            }
        }
        walk(root, emptyMap())
        return out
    }

    private fun isSource(file: Path): Boolean {
        val name = file.name
        if (file.extension !in EXTENSIONS || name.endsWith(".d.ts") || name.contains(".min.")) return false
        if (name.contains(".test.") || name.contains(".spec.") || name.contains(".stories.")) return false
        return runCatching { Files.size(file) <= MAX_FILE_SIZE }.getOrDefault(false)
    }

    /** `KEY=value` lines of the `.env` files in [dir]; later files win, as with Vite and Create React App. */
    fun readEnv(dir: Path): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        for (name in ENV_FILES) {
            val file = dir.resolve(name)
            if (!Files.isRegularFile(file)) continue
            runCatching { Files.readAllLines(file) }.getOrDefault(emptyList()).forEach { line ->
                parseEnvLine(line)?.let { (k, v) -> env[k] = v }
            }
        }
        return env
    }

    internal fun parseEnvLine(line: String): Pair<String, String>? {
        val t = line.trim().removePrefix("export ").trim()
        if (t.isEmpty() || t.startsWith("#") || '=' !in t) return null
        val key = t.substringBefore('=').trim()
        var value = t.substringAfter('=').trim()
        if (value.length >= 2 && value.first() == value.last() && value.first() in "'\"") value = value.substring(1, value.length - 1)
        else value = value.substringBefore(" #")
        return key to value.trim()
    }

    class RawFetch(val method: String?, val url: String, val offset: Int)

    /** The fetch calls in one file's [text]; [env] holds the `.env` values of its package. */
    fun scanText(text: String, env: Map<String, String>): List<RawFetch> {
        val out = mutableListOf<RawFetch>()
        for (m in FETCH.findAll(text)) {
            val open = m.range.last // the '('
            if (isInComment(text, m.range.first)) continue
            val argStart = skipSpace(text, open + 1)
            val url = urlArgument(text, argStart, env) ?: continue
            val close = closingParen(text, open)
            val args = text.substring(argStart, close)
            val method = when {
                METHOD.containsMatchIn(args) -> METHOD.find(args)!!.groupValues[2].uppercase()
                hasSecondArgument(text, argStart, close) && !Regex("""\{[^}]*\}""").containsMatchIn(args.substringAfter(',', "")) -> null
                else -> "GET"
            }
            out += RawFetch(method, url, m.range.first)
        }
        return out
    }

    /** The normalised URL of the first argument at [at], or null when it is not something this scanner understands. */
    private fun urlArgument(text: String, at: Int, env: Map<String, String>): String? {
        if (at >= text.length) return null
        val c = text[at]
        if (c == '\'' || c == '"' || c == '`') {
            val parts = literal(text, at) ?: return null
            return normalise(parts, text, env)
        }
        val ident = Regex("""[A-Za-z_$][\w$]*""").matchAt(text, at)?.value ?: return null
        val next = skipSpace(text, at + ident.length)
        if (next < text.length && text[next] != ',' && text[next] != ')') return null // an expression: give up
        val init = constantInitializer(text, ident) ?: return null
        val start = skipSpace(text, init)
        if (start >= text.length || text[start] !in "'\"`") return null
        return normalise(literal(text, start) ?: return null, text, env)
    }

    /** Text and `${...}` holes of the literal starting at [at]: odd entries are expressions. */
    private fun literal(text: String, at: Int): List<String>? {
        val quote = text[at]
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var i = at + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> { if (i + 1 < text.length) current.append(text[i + 1]); i += 2; continue }
                c == quote -> { parts += current.toString(); return parts }
                c == '\n' && quote != '`' -> return null
                quote == '`' && c == '$' && i + 1 < text.length && text[i + 1] == '{' -> {
                    parts += current.toString(); current.clear()
                    var depth = 1
                    var j = i + 2
                    while (j < text.length && depth > 0) {
                        if (text[j] == '{') depth++ else if (text[j] == '}') depth--
                        j++
                    }
                    parts += text.substring(i + 2, j - 1).trim()
                    i = j
                    continue
                }
                else -> current.append(c)
            }
            i++
        }
        return null
    }

    /**
     * `http://host:port/path`, `/path`, or `*` followed by the path (unknown base) with `*` for each segment that holds an
     * expression; query string and fragment dropped.
     */
    private fun normalise(parts: List<String>, text: String, env: Map<String, String>): String? {
        val sb = StringBuilder()
        var unknownBase = false
        for ((i, part) in parts.withIndex()) {
            if (i % 2 == 0) { sb.append(part); continue }
            if (sb.isEmpty() && !unknownBase) {
                val base = baseValue(part, text, env)
                if (base != null) sb.append(base.trimEnd('/')) else unknownBase = true
            } else sb.append(HOLE)
        }
        var url = sb.toString().substringBefore('?').substringBefore('#').trim()
        if (url.isEmpty() && !unknownBase) return null
        val absolute = Regex("^https?://[^/]+").find(url)?.value
        var path = if (absolute != null) url.removePrefix(absolute) else url
        if (!unknownBase && absolute == null && !path.startsWith("/")) {
            if (path.startsWith("./") || path.startsWith("../") || '.' in path.substringAfterLast('/')) return null // a file
            path = "/$path"
        }
        path = path.split('/').joinToString("/") { if (HOLE in it) "*" else it }
        url = (absolute ?: "") + path
        return if (unknownBase) "*" + (if (url.startsWith("/")) url else "/$url") else url
    }

    /** The value of a base-address expression: an env variable, or a constant of the file that holds a literal or env variable. */
    private fun baseValue(expression: String, text: String, env: Map<String, String>): String? {
        ENV_REF.find(expression)?.let { return env[it.groupValues[1]] }
        val ident = Regex("""^[A-Za-z_$][\w$]*$""").find(expression)?.value ?: return null
        val init = constantInitializer(text, ident) ?: return null
        val statement = text.substring(init, minOf(text.length, init + 300)).substringBefore(';').substringBefore('\n')
        ENV_REF.find(statement)?.let { env[it.groupValues[1]]?.let { v -> return v } }
        Regex("""(['"`])(https?://[^'"`]*|/[^'"`]*)\1""").find(statement)?.let { return it.groupValues[2] }
        return null
    }

    /** Offset of the initializer of `const|let|var <name> =` in [text]. */
    private fun constantInitializer(text: String, name: String): Int? =
        Regex("""\b(?:const|let|var)\s+${Regex.escape(name)}\s*(?::[^=]+)?=""").find(text)?.range?.last?.plus(1)

    private fun skipSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** Offset of the ')' that closes the '(' at [open], skipping strings and nested brackets; bounded. */
    private fun closingParen(text: String, open: Int): Int {
        var depth = 0
        var i = open
        val end = minOf(text.length, open + 4000)
        while (i < end) {
            when (val c = text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> { depth--; if (depth == 0) return i }
                '\'', '"', '`' -> {
                    i++
                    while (i < end && text[i] != c) { if (text[i] == '\\') i++; i++ }
                }
            }
            i++
        }
        return end
    }

    private fun hasSecondArgument(text: String, from: Int, close: Int): Boolean {
        var depth = 0
        var i = from
        while (i < close) {
            when (val c = text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) return true
                '\'', '"', '`' -> { i++; while (i < close && text[i] != c) { if (text[i] == '\\') i++; i++ } }
            }
            i++
        }
        return false
    }

    private fun isInComment(text: String, at: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val before = text.substring(lineStart, at).trimStart()
        return before.startsWith("//") || before.startsWith("*")
    }

    private fun lineOf(text: String, offset: Int): Int {
        var line = 0
        for (i in 0 until minOf(offset, text.length)) if (text[i] == '\n') line++
        return line
    }
}
