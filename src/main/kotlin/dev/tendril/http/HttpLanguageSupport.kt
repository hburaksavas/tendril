package dev.tendril.http

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import javax.swing.Icon

/** `.http` / `.rest` request files, readable and runnable without the paid HTTP client plugin. */
object HttpLanguage : Language("TendrilHttp")

object HttpFileType : LanguageFileType(HttpLanguage) {
    override fun getName() = "Tendril HTTP"
    override fun getDescription() = "HTTP request file"
    override fun getDefaultExtension() = "http"
    override fun getIcon(): Icon = AllIcons.Nodes.Services
}

class HttpTokenType(name: String) : IElementType(name, HttpLanguage)

object HttpTokens {
    val SEPARATOR = HttpTokenType("SEPARATOR")
    val COMMENT = HttpTokenType("COMMENT")
    val FILE_VARIABLE = HttpTokenType("FILE_VARIABLE")
    val METHOD = HttpTokenType("METHOD")
    val URL = HttpTokenType("URL")
    val URL_VARIABLE = HttpTokenType("URL_VARIABLE")
    val HEADER_NAME = HttpTokenType("HEADER_NAME")
    val HEADER_VALUE = HttpTokenType("HEADER_VALUE")
    val BODY = HttpTokenType("BODY")
    val VARIABLE = HttpTokenType("VARIABLE")
}

class HttpFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, HttpLanguage) {
    override fun getFileType() = HttpFileType
}

/** A flat PSI: the file with one leaf per token. Enough for highlighting and for gutter icons on request lines. */
class HttpParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = HttpLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType() = FILE
    override fun getCommentTokens(): TokenSet = TokenSet.create(HttpTokens.COMMENT, HttpTokens.SEPARATOR)
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = throw UnsupportedOperationException("flat tree")
    override fun createFile(viewProvider: FileViewProvider): PsiFile = HttpFile(viewProvider)

    private companion object {
        val FILE = IFileElementType(HttpLanguage)
    }
}

/**
 * Line based lexer. A request is: separator line (`###`), request line, header lines, blank line, body.
 * `@name = value` lines, `#` and `//` comments are recognised wherever they appear before the body.
 * The lexer can only restart at the file start or at a separator, which is where it reports state 0.
 */
class HttpLexer : LexerBase() {
    private class Token(val type: IElementType, val start: Int, val end: Int)

    private var buffer: CharSequence = ""
    private var endOffset = 0
    private var origin = 0
    private var tokens: List<Token> = emptyList()
    private var index = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        this.origin = startOffset
        this.tokens = scan(startOffset)
        this.index = 0
    }

    override fun getState(): Int {
        val t = tokens.getOrNull(index) ?: return 0
        return if (t.start == origin || t.type == HttpTokens.SEPARATOR) 0 else 1
    }

    override fun getTokenType(): IElementType? = tokens.getOrNull(index)?.type
    override fun getTokenStart(): Int = tokens.getOrNull(index)?.start ?: endOffset
    override fun getTokenEnd(): Int = tokens.getOrNull(index)?.end ?: endOffset
    override fun advance() {
        index++
    }

    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    private enum class Mode { REQUEST, HEADERS, BODY }

    private fun scan(from: Int): List<Token> {
        val out = ArrayList<Token>()
        var pos = from
        var mode = Mode.REQUEST
        while (pos < endOffset) {
            var lineEnd = pos
            while (lineEnd < endOffset && buffer[lineEnd] != '\n') lineEnd++
            val next = if (lineEnd < endOffset) lineEnd + 1 else lineEnd // include the newline
            var contentEnd = lineEnd
            if (contentEnd > pos && buffer[contentEnd - 1] == '\r') contentEnd--

            var contentStart = pos
            while (contentStart < contentEnd && (buffer[contentStart] == ' ' || buffer[contentStart] == '\t')) contentStart++
            if (contentStart > pos) out += Token(TokenType.WHITE_SPACE, pos, contentStart)

            val text = buffer.subSequence(contentStart, contentEnd).toString()
            val trimmed = text.trim()
            when {
                text.startsWith("###") -> {
                    out += Token(HttpTokens.SEPARATOR, contentStart, contentEnd)
                    mode = Mode.REQUEST
                }
                mode == Mode.BODY -> if (text.isNotEmpty()) variables(out, contentStart, contentEnd, HttpTokens.BODY, HttpTokens.VARIABLE)
                trimmed.isEmpty() -> {
                    if (text.isNotEmpty()) out += Token(TokenType.WHITE_SPACE, contentStart, contentEnd)
                    if (mode == Mode.HEADERS) mode = Mode.BODY
                }
                trimmed.startsWith("#") || trimmed.startsWith("//") -> out += Token(HttpTokens.COMMENT, contentStart, contentEnd)
                mode == Mode.REQUEST && FILE_VARIABLE.matches(trimmed) -> out += Token(HttpTokens.FILE_VARIABLE, contentStart, contentEnd)
                mode == Mode.REQUEST -> {
                    requestLine(out, contentStart, contentEnd, text)
                    mode = Mode.HEADERS
                }
                else -> headerLine(out, contentStart, contentEnd, text)
            }
            if (next > contentEnd) out += Token(TokenType.WHITE_SPACE, contentEnd, next)
            pos = next
        }
        return out
    }

    private fun requestLine(out: MutableList<Token>, start: Int, end: Int, text: String) {
        var urlStart = start
        val space = text.indexOfFirst { it == ' ' || it == '\t' }
        if (space > 0 && text.substring(0, space).uppercase() in METHODS) {
            out += Token(HttpTokens.METHOD, start, start + space)
            var u = start + space
            while (u < end && (buffer[u] == ' ' || buffer[u] == '\t')) u++
            if (u > start + space) out += Token(TokenType.WHITE_SPACE, start + space, u)
            urlStart = u
        }
        if (urlStart < end) variables(out, urlStart, end, HttpTokens.URL, HttpTokens.URL_VARIABLE)
    }

    private fun headerLine(out: MutableList<Token>, start: Int, end: Int, text: String) {
        val colon = text.indexOf(':')
        if (colon <= 0) {
            variables(out, start, end, HttpTokens.HEADER_VALUE, HttpTokens.VARIABLE)
            return
        }
        out += Token(HttpTokens.HEADER_NAME, start, start + colon)
        variables(out, start + colon, end, HttpTokens.HEADER_VALUE, HttpTokens.VARIABLE)
    }

    /** Splits [start, end) into plain pieces of type [plain] and `{{variable}}` pieces of type [variable]. */
    private fun variables(out: MutableList<Token>, start: Int, end: Int, plain: IElementType, variable: IElementType) {
        var last = start
        for (m in VARIABLE.findAll(buffer.subSequence(start, end))) {
            val s = start + m.range.first
            val e = start + m.range.last + 1
            if (s > last) out += Token(plain, last, s)
            out += Token(variable, s, e)
            last = e
        }
        if (last < end) out += Token(plain, last, end)
    }

    private companion object {
        val VARIABLE = Regex("""\{\{[^{}\n]*}}""")
        val FILE_VARIABLE = Regex("""@\w[\w.-]*\s*=.*""")
        val METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE")
    }
}

class HttpSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = HttpLexer()

    override fun getTokenHighlights(type: IElementType?): Array<TextAttributesKey> = when (type) {
        HttpTokens.SEPARATOR -> arrayOf(SEPARATOR)
        HttpTokens.COMMENT -> arrayOf(COMMENT)
        HttpTokens.FILE_VARIABLE -> arrayOf(FILE_VARIABLE)
        HttpTokens.METHOD -> arrayOf(METHOD)
        HttpTokens.HEADER_NAME -> arrayOf(HEADER_NAME)
        HttpTokens.VARIABLE, HttpTokens.URL_VARIABLE -> arrayOf(VARIABLE)
        else -> emptyArray()
    }

    private companion object {
        val SEPARATOR = TextAttributesKey.createTextAttributesKey("TENDRIL_HTTP_SEPARATOR", DefaultLanguageHighlighterColors.DOC_COMMENT)
        val COMMENT = TextAttributesKey.createTextAttributesKey("TENDRIL_HTTP_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val FILE_VARIABLE = TextAttributesKey.createTextAttributesKey("TENDRIL_HTTP_FILE_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
        val METHOD = TextAttributesKey.createTextAttributesKey("TENDRIL_HTTP_METHOD", DefaultLanguageHighlighterColors.KEYWORD)
        val HEADER_NAME = TextAttributesKey.createTextAttributesKey("TENDRIL_HTTP_HEADER_NAME", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
        val VARIABLE = TextAttributesKey.createTextAttributesKey("TENDRIL_HTTP_VARIABLE", DefaultLanguageHighlighterColors.METADATA)
    }
}

class HttpSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = HttpSyntaxHighlighter()
}
