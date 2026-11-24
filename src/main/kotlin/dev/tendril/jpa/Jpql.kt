package dev.tendril.jpa

/*
 * A small, forgiving reading of JPQL / HQL text: enough to find the entity names, the identification variables
 * (aliases), the path expressions and the parameters of a query. It is not a grammar; whatever it does not
 * understand it skips, so a query it cannot follow produces no findings rather than false ones. No PSI here.
 */

internal enum class TokenKind { IDENT, DOT, COMMA, LPAREN, RPAREN, NAMED_PARAM, POSITIONAL_PARAM, STRING, NUMBER, SPEL, OTHER }

/** [start] and [end] are offsets in the query text, [end] exclusive. */
internal class Token(val kind: TokenKind, val text: String, val start: Int, val end: Int) {
    fun isWord(vararg words: String) = kind == TokenKind.IDENT && words.any { it.equals(text, ignoreCase = true) }
    override fun toString() = "$kind($text)"
}

internal object JpqlLexer {

    fun tokenize(s: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        fun add(kind: TokenKind, end: Int) {
            out += Token(kind, s.substring(i, end), i, end)
            i = end
        }
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                s.startsWith("--", i) -> i = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                s.startsWith("/*", i) -> i = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
                c == '\'' || c == '"' || c == '`' -> add(TokenKind.STRING, quotedEnd(s, i))
                c == '#' && s.startsWith("{", i + 1) -> add(TokenKind.SPEL, spelEnd(s, i + 1))
                (c == ':' || c == '?') && s.startsWith("#{", i + 1) -> add(TokenKind.SPEL, spelEnd(s, i + 2))
                c == ':' && s.startsWith("::", i) -> add(TokenKind.OTHER, i + 2) // a PostgreSQL cast, not a parameter
                c == ':' && i + 1 < s.length && Character.isJavaIdentifierStart(s[i + 1]) ->
                    add(TokenKind.NAMED_PARAM, identEnd(s, i + 1))
                c == '?' && i + 1 < s.length && s[i + 1].isDigit() ->
                    add(TokenKind.POSITIONAL_PARAM, digitsEnd(s, i + 1))
                Character.isJavaIdentifierStart(c) -> add(TokenKind.IDENT, identEnd(s, i))
                c.isDigit() -> add(TokenKind.NUMBER, numberEnd(s, i))
                c == '.' -> add(TokenKind.DOT, i + 1)
                c == ',' -> add(TokenKind.COMMA, i + 1)
                c == '(' -> add(TokenKind.LPAREN, i + 1)
                c == ')' -> add(TokenKind.RPAREN, i + 1)
                else -> add(TokenKind.OTHER, i + 1)
            }
        }
        return out
    }

    private fun identEnd(s: String, from: Int): Int {
        var j = from
        while (j < s.length && Character.isJavaIdentifierPart(s[j])) j++
        return j
    }

    private fun digitsEnd(s: String, from: Int): Int {
        var j = from
        while (j < s.length && s[j].isDigit()) j++
        return j
    }

    private fun numberEnd(s: String, from: Int): Int {
        var j = from
        while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_' ||
                (s[j] == '.' && j + 1 < s.length && s[j + 1].isDigit()))
        ) j++
        return j
    }

    /** End of a quoted literal starting at [from]; a doubled quote is an escaped quote. */
    private fun quotedEnd(s: String, from: Int): Int {
        val quote = s[from]
        var j = from + 1
        while (j < s.length) {
            if (s[j] == quote) {
                if (j + 1 < s.length && s[j + 1] == quote) j += 2 else return j + 1
            } else j++
        }
        return s.length
    }

    /** End of `{...}` starting at the brace [brace], braces nested. */
    private fun spelEnd(s: String, brace: Int): Int {
        var depth = 0
        var j = brace
        while (j < s.length) {
            when (s[j]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return j + 1
            }
            j++
        }
        return s.length
    }
}

/** An entity named in a FROM / JOIN / UPDATE clause: a simple name, a qualified name, or SpEL like `#{#entityName}`. */
internal class EntityName(val tokens: List<Token>, val inJoin: Boolean) {
    val name: String = tokens.joinToString(".") { it.text }
    val start get() = tokens.first().start
    val end get() = tokens.last().end
    val isSpel get() = tokens.first().kind == TokenKind.SPEL
}

/** `u.address.city`: [segments] are the identifiers, the first one an alias (when it resolves to one). */
internal class JpqlPath(val segments: List<Token>)

internal sealed class AliasSource {
    class Entity(val entity: EntityName) : AliasSource()
    class Join(val path: JpqlPath) : AliasSource()
}

internal class AliasDecl(val name: Token, val source: AliasSource)

internal class JpqlStructure(
    val tokens: List<Token>,
    val entities: List<EntityName>,
    val aliases: List<AliasDecl>,
    val paths: List<JpqlPath>,
    val namedParams: List<Token>,
    val positionalParams: List<Token>,
    /** `#name` used in SpEL expressions (`:#{#user.id}`), which bind method parameters by name. */
    val spelNames: Set<String>,
    /** `[0]` used in SpEL expressions (`?#{[0]}`): zero-based parameter indexes. */
    val spelIndexes: Set<Int>,
) {
    private val aliasByName = aliases.associateBy { it.name.text.lowercase() }

    /** Identification variables are case-insensitive. */
    fun alias(name: String): AliasDecl? = aliasByName[name.lowercase()]

    fun namedParamName(token: Token) = token.text.removePrefix(":")
    fun positionalIndex(token: Token) = token.text.removePrefix("?").toIntOrNull()
}

internal object JpqlParser {

    /** Words that end a range declaration, so they are never read as an alias. */
    private val RESERVED = setOf(
        "where", "join", "left", "right", "inner", "outer", "full", "cross", "order", "group", "having", "on", "with",
        "set", "union", "intersect", "except", "as", "fetch", "select", "from", "and", "or", "not", "limit", "offset",
        "in", "by", "fetch", "update", "delete", "insert", "values",
    )
    private val CLAUSE_END = setOf("where", "group", "order", "having", "select", "union", "intersect", "except", "set", "limit", "offset")

    private val SPEL_NAME = Regex("#([A-Za-z_][A-Za-z0-9_]*)")
    private val SPEL_INDEX = Regex("\\[(\\d+)]")

    fun parse(text: String): JpqlStructure = Parser(JpqlLexer.tokenize(text)).run()

    private class Parser(val t: List<Token>) {
        val entities = ArrayList<EntityName>()
        val aliases = ArrayList<AliasDecl>()
        val paths = ArrayList<JpqlPath>()
        val named = ArrayList<Token>()
        val positional = ArrayList<Token>()
        val spelNames = HashSet<String>()
        val spelIndexes = HashSet<Int>()
        val consumed = BooleanArray(t.size)

        fun at(i: Int): Token? = t.getOrNull(i)
        fun isAliasWord(tok: Token?) = tok != null && tok.kind == TokenKind.IDENT && tok.text.lowercase() !in RESERVED

        fun run(): JpqlStructure {
            var depth = 0
            val fromAtDepth = HashSet<Int>() // nesting depths whose FROM clause is still open
            var i = 0
            while (i < t.size) {
                val tok = t[i]
                when {
                    tok.kind == TokenKind.LPAREN -> depth++
                    tok.kind == TokenKind.RPAREN -> { fromAtDepth.remove(depth); depth-- }
                    tok.isWord("from") -> { fromAtDepth += depth; i = rangeDeclaration(i + 1, inJoin = false); continue }
                    tok.isWord("update") && t.subList(0, i).none { it.kind == TokenKind.IDENT } -> {
                        i = rangeDeclaration(i + 1, inJoin = false); continue
                    }
                    tok.kind == TokenKind.COMMA && depth in fromAtDepth -> {
                        i = if (at(i + 1)?.isWord("in") == true && at(i + 2)?.kind == TokenKind.LPAREN) collectionMember(i + 3)
                        else rangeDeclaration(i + 1, inJoin = false)
                        continue
                    }
                    tok.isWord("join") -> { i = join(i + 1); continue }
                    tok.kind == TokenKind.IDENT && tok.text.lowercase() in CLAUSE_END -> fromAtDepth.remove(depth)
                    tok.isWord("new") && at(i + 1)?.kind == TokenKind.IDENT -> {
                        i = dottedName(i + 1).let { (end, _) -> end }; continue // constructor expression: a class name
                    }
                    tok.kind == TokenKind.IDENT && !consumed[i] && at(i - 1)?.kind != TokenKind.DOT &&
                        at(i + 1)?.kind == TokenKind.DOT && at(i + 2)?.kind == TokenKind.IDENT -> {
                        val (end, path) = path(i)
                        paths += path
                        i = end; continue
                    }
                    tok.kind == TokenKind.NAMED_PARAM -> named += tok
                    tok.kind == TokenKind.POSITIONAL_PARAM -> positional += tok
                    tok.kind == TokenKind.SPEL -> {
                        SPEL_NAME.findAll(tok.text).map { it.groupValues[1] }.filter { it != "entityName" }.forEach { spelNames += it }
                        SPEL_INDEX.findAll(tok.text).mapNotNull { it.groupValues[1].toIntOrNull() }.forEach { spelIndexes += it }
                    }
                }
                i++
            }
            return JpqlStructure(t, entities, aliases, paths, named, positional, spelNames, spelIndexes)
        }

        /** `a.b.c` from [from]: (index after it, the tokens). */
        fun dottedName(from: Int): Pair<Int, List<Token>> {
            val parts = arrayListOf(t[from])
            consumed[from] = true
            var j = from + 1
            while (at(j)?.kind == TokenKind.DOT && at(j + 1)?.kind == TokenKind.IDENT) {
                parts += t[j + 1]
                consumed[j + 1] = true
                j += 2
            }
            return j to parts
        }

        fun path(from: Int): Pair<Int, JpqlPath> = dottedName(from).let { (end, parts) -> end to JpqlPath(parts) }

        /** `Entity [AS] alias` (or a SpEL entity name). Returns the index after it. */
        fun rangeDeclaration(from: Int, inJoin: Boolean): Int {
            val first = at(from) ?: return from
            val (afterName, entity) = when {
                first.kind == TokenKind.SPEL -> (from + 1) to EntityName(listOf(first), inJoin)
                // any word: an entity may well be called Order or Group
                first.kind == TokenKind.IDENT -> dottedName(from).let { (end, parts) -> end to EntityName(parts, inJoin) }
                else -> return from
            }
            entities += entity
            return alias(afterName) { AliasSource.Entity(entity) }
        }

        /** After JOIN: `[FETCH] alias.path [AS] x`, or an entity join `Entity [AS] x`. */
        fun join(from: Int): Int {
            var j = from
            if (at(j)?.isWord("fetch") == true) j++
            val first = at(j) ?: return j
            if (first.kind == TokenKind.IDENT && at(j + 1)?.kind == TokenKind.DOT && at(j + 2)?.kind == TokenKind.IDENT) {
                val (end, path) = path(j)
                paths += path
                return alias(end) { AliasSource.Join(path) }
            }
            return rangeDeclaration(j, inJoin = true)
        }

        /** Old-style `, IN (alias.collection) [AS] x`; [from] is just inside the parenthesis. */
        fun collectionMember(from: Int): Int {
            if (at(from)?.kind != TokenKind.IDENT) return from
            val (end, path) = path(from)
            paths += path
            if (at(end)?.kind != TokenKind.RPAREN) return end
            return alias(end + 1) { AliasSource.Join(path) }
        }

        fun alias(from: Int, source: () -> AliasSource): Int {
            var j = from
            if (at(j)?.isWord("as") == true) j++
            val name = at(j)
            if (!isAliasWord(name)) return from
            consumed[j] = true
            aliases += AliasDecl(name!!, source())
            return j + 1
        }
    }
}
