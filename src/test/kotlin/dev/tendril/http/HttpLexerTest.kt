package dev.tendril.http

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.junit.Assert.*
import org.junit.Test

class HttpLexerTest {

    private class Tok(val type: IElementType, val text: String, val start: Int, val state: Int)

    private fun lex(text: String): List<Tok> {
        val lexer = HttpLexer()
        lexer.start(text)
        val out = mutableListOf<Tok>()
        while (lexer.tokenType != null) {
            out += Tok(lexer.tokenType!!, text.substring(lexer.tokenStart, lexer.tokenEnd), lexer.tokenStart, lexer.state)
            lexer.advance()
        }
        return out
    }

    private fun kinds(text: String) = lex(text).filter { it.type != TokenType.WHITE_SPACE }.map { it.type to it.text }

    private val sample = "@host = http://h\n\n### Create\nPOST {{host}}/a/{{id}}?x=1\nContent-Type: application/json\n" +
        "X-Id: {{id}}\n# a comment\n\n{\n  \"n\": \"{{name}}\"\n}\n\n### Next\nGET http://h/b\n"

    @Test
    fun `tokens cover the whole text without gaps`() {
        val tokens = lex(sample)
        assertEquals(sample, tokens.joinToString("") { it.text })
        var expected = 0
        for (t in tokens) {
            assertEquals("gap before '${t.text}'", expected, t.start)
            expected += t.text.length
        }
    }

    @Test
    fun `classifies the parts of a request`() {
        val t = kinds("### Create\nPOST {{host}}/a\nAccept: */*\n\n{\"a\":1}\n")
        assertEquals(HttpTokens.SEPARATOR to "### Create", t[0])
        assertEquals(HttpTokens.METHOD to "POST", t[1])
        assertEquals(HttpTokens.URL_VARIABLE to "{{host}}", t[2])
        assertEquals(HttpTokens.URL to "/a", t[3])
        assertEquals(HttpTokens.HEADER_NAME to "Accept", t[4])
        assertEquals(HttpTokens.HEADER_VALUE to ": */*", t[5])
        assertEquals(HttpTokens.BODY to "{\"a\":1}", t[6])
    }

    @Test
    fun `a request line with only a url has no method token`() {
        val t = kinds("http://a/b\n")
        assertEquals(listOf(HttpTokens.URL to "http://a/b"), t)
    }

    @Test
    fun `variables are found in headers and bodies`() {
        val types = lex("GET http://a\nX: {{h}}\n\n{{b}} text\n").map { it.type }
        assertEquals(2, types.count { it == HttpTokens.VARIABLE })
    }

    @Test
    fun `comments and file variables are recognised before the request line`() {
        val t = kinds("# note\n@a = 1\nGET http://a\n")
        assertEquals(HttpTokens.COMMENT, t[0].first)
        assertEquals(HttpTokens.FILE_VARIABLE, t[1].first)
        assertEquals(HttpTokens.METHOD, t[2].first)
    }

    @Test
    fun `a separator starts a new request after a body`() {
        val t = kinds("GET http://a\n\nbody line\n### Two\nGET http://b\n")
        assertTrue(t.any { it.first == HttpTokens.BODY && it.second == "body line" })
        val second = t.dropWhile { it.first != HttpTokens.SEPARATOR }
        assertEquals(HttpTokens.METHOD, second[1].first)
    }

    @Test
    fun `can restart at the file start and at separators only`() {
        val tokens = lex("GET http://a\nX: 1\n\n### Two\nGET http://b\n")
        val restartable = tokens.filter { it.state == 0 }.map { it.type }
        assertEquals(HttpTokens.METHOD, restartable.first()) // the token at offset 0
        assertTrue(restartable.contains(HttpTokens.SEPARATOR))
        assertTrue(tokens.first { it.type == HttpTokens.HEADER_NAME }.state != 0)
    }

    @Test
    fun `handles windows line endings`() {
        val text = "GET http://a\r\nAccept: */*\r\n\r\nbody\r\n"
        assertEquals(text, lex(text).joinToString("") { it.text })
    }

    @Test
    fun `an empty text produces no tokens`() {
        assertTrue(lex("").isEmpty())
    }
}
