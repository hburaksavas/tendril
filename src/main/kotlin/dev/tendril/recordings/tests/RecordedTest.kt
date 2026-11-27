package dev.tendril.recordings.tests

/**
 * A test made from one recorded request: send what was received with `MockMvc`, expect what was answered, and
 * stub the Feign clients called while serving it with what they returned. Rendered as Java or Kotlin.
 */
data class RecordedTest(
    val kotlin: Boolean,
    val packageName: String,
    val className: String,
    val testName: String,
    /** `org.springframework.test.context.bean.override.mockito.MockitoBean`, or `MockBean` before Boot 3.4. */
    val mockAnnotation: String,
    val httpMethod: String,
    /** Path and query as received, context path included. */
    val uri: String,
    val contextPath: String?,
    val headers: List<Pair<String, String>>,
    /** Headers the recorder masked (`Authorization`, cookies): the test cannot send them. */
    val maskedHeaders: List<String>,
    val contentType: String?,
    val body: String?,
    val status: Int,
    val responseBody: String?,
    val responseIsJson: Boolean,
    val clients: List<MockedClient>,
    val stubs: List<Stub>,
    /** Comments for what the test cannot do by itself. */
    val notes: List<String>,
    /** Classes the stubs mention (return types), imported by name. */
    val imports: Set<String>,
)

data class MockedClient(val qualifiedName: String, val simpleName: String, val field: String)

/** `given(field.method(matchers...)).willReturn(values...)`; [values] empty means only [notes] are written. */
data class Stub(
    val field: String,
    val method: String,
    val matchers: List<String>,
    val values: List<StubValue>,
    val notes: List<String> = emptyList(),
)

/** A recorded response as the client method returns it. */
data class StubValue(
    /** Read with the ObjectMapper into this type, as written in the test's language; null: [text] is a plain string. */
    val type: String?,
    val text: String,
    val wrapper: Wrapper = Wrapper.NONE,
    val status: Int = 200,
) {
    enum class Wrapper { NONE, OPTIONAL, RESPONSE_ENTITY }
}

object RecordedTestText {

    fun render(t: RecordedTest): String = if (t.kotlin) kotlin(t) else java(t)

    private val STANDARD = setOf("GET", "POST", "PUT", "PATCH", "DELETE")

    private fun commonImports(t: RecordedTest): List<String> = buildList {
        if (t.stubs.any { s -> s.values.any { it.type != null } }) add("com.fasterxml.jackson.core.type.TypeReference")
        add("com.fasterxml.jackson.databind.ObjectMapper")
        add("org.junit.jupiter.api.Test")
        add("org.springframework.beans.factory.annotation.Autowired")
        add("org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc")
        add("org.springframework.boot.test.context.SpringBootTest")
        if (t.httpMethod !in STANDARD) add("org.springframework.http.HttpMethod")
        if (t.stubs.any { s -> s.values.any { it.wrapper == StubValue.Wrapper.RESPONSE_ENTITY } }) add("org.springframework.http.ResponseEntity")
        if (t.stubs.any { s -> s.values.any { it.wrapper == StubValue.Wrapper.OPTIONAL } }) add("java.util.Optional")
        if (t.clients.isNotEmpty()) add(t.mockAnnotation)
        add("org.springframework.test.web.servlet.MockMvc")
        t.clients.forEach { if (it.qualifiedName.substringBeforeLast('.', "") != t.packageName) add(it.qualifiedName) }
        t.imports.filter { it.substringBeforeLast('.', "") != t.packageName && !it.startsWith("java.lang.") }.forEach { add(it) }
    }.distinct().sorted()

    private fun requestCall(t: RecordedTest, quote: (String) -> String): String {
        val method = t.httpMethod.uppercase()
        return if (method in STANDARD) "${method.lowercase()}(${quote(t.uri)})"
        else "request(HttpMethod.valueOf(${quote(method)}), ${quote(t.uri)})"
    }

    // ---------------------------------------------------------------- Java

    private fun java(t: RecordedTest): String = buildString {
        if (t.packageName.isNotEmpty()) append("package ${t.packageName};\n\n")
        commonImports(t).forEach { append("import $it;\n") }
        append("\n")
        if (t.stubs.any { it.values.isNotEmpty() }) {
            append("import static org.mockito.ArgumentMatchers.*;\n")
            append("import static org.mockito.BDDMockito.given;\n")
        }
        append("import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;\n")
        append("import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;\n\n")
        append("/** Made by Tendril from a recorded request: what was received is sent again, what was answered is expected. */\n")
        append("@SpringBootTest\n@AutoConfigureMockMvc\nclass ${t.className} {\n")
        append("    @Autowired\n    MockMvc mockMvc;\n\n")
        append("    @Autowired\n    ObjectMapper objectMapper;\n")
        val annotation = t.mockAnnotation.substringAfterLast('.')
        t.clients.forEach { append("\n    @$annotation\n    ${it.simpleName} ${it.field};\n") }
        append("\n    @Test\n    void ${t.testName}() throws Exception {\n")
        t.notes.forEach { append("        // $it\n") }
        for (s in t.stubs) {
            s.notes.forEach { append("        // $it\n") }
            if (s.values.isEmpty()) continue
            val values = s.values.joinToString(",\n                ") { javaValue(it) }
            append("        given(${s.field}.${s.method}(${s.matchers.joinToString(", ")}))\n")
            append("            .willReturn(\n                $values);\n")
        }
        if (t.stubs.isNotEmpty()) append("\n")
        append("        mockMvc.perform(${requestCall(t, ::javaString)}")
        t.contextPath?.let { append("\n                .contextPath(${javaString(it)})") }
        t.headers.forEach { (k, v) -> append("\n                .header(${javaString(k)}, ${javaString(v)})") }
        t.contentType?.let { append("\n                .contentType(${javaString(it)})") }
        t.body?.let { append("\n                .content(${javaBlock(it, 16)})") }
        append(")\n")
        append("            .andExpect(status().is(${t.status}))")
        when {
            t.responseBody == null -> {}
            t.responseIsJson -> append("\n            .andExpect(content().json(${javaBlock(t.responseBody, 12)}))")
            else -> append("\n            .andExpect(content().string(${javaBlock(t.responseBody, 12)}))")
        }
        append(";\n    }\n}\n")
    }

    private fun javaValue(v: StubValue): String {
        val read = if (v.type == null) javaBlock(v.text, 16)
        else "objectMapper.readValue(${javaBlock(v.text, 16)}, new TypeReference<${v.type}>() {})"
        return when (v.wrapper) {
            StubValue.Wrapper.NONE -> read
            StubValue.Wrapper.OPTIONAL -> "Optional.of($read)"
            StubValue.Wrapper.RESPONSE_ENTITY -> "ResponseEntity.status(${v.status}).body($read)"
        }
    }

    fun javaString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    /** A one-line text as a string literal, anything longer as a text block indented by [indent]. */
    fun javaBlock(s: String, indent: Int): String {
        if (!s.contains('\n') && s.length <= 80) return javaString(s)
        val pad = " ".repeat(indent + 4)
        val lines = s.replace("\r", "").replace("\\", "\\\\").replace("\"\"\"", "\\\"\"\"").split('\n')
        // closing right after the last line: no trailing newline in the value
        return "\"\"\"\n" + lines.joinToString("\n") { if (it.isEmpty()) "" else pad + it } + "\"\"\""
    }

    // ---------------------------------------------------------------- Kotlin

    private fun kotlin(t: RecordedTest): String = buildString {
        if (t.packageName.isNotEmpty()) append("package ${t.packageName}\n\n")
        val imports = commonImports(t).toMutableList()
        if (t.stubs.any { it.values.isNotEmpty() }) {
            imports += "org.mockito.ArgumentMatchers"
            imports += "org.mockito.BDDMockito.given"
        }
        imports += "org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*"
        imports += "org.springframework.test.web.servlet.result.MockMvcResultMatchers.*"
        imports.distinct().sorted().forEach { append("import $it\n") }
        append("\n/** Made by Tendril from a recorded request: what was received is sent again, what was answered is expected. */\n")
        append("@SpringBootTest\n@AutoConfigureMockMvc\nclass ${t.className} {\n")
        append("    @Autowired\n    lateinit var mockMvc: MockMvc\n\n")
        append("    @Autowired\n    lateinit var objectMapper: ObjectMapper\n")
        val annotation = t.mockAnnotation.substringAfterLast('.')
        t.clients.forEach { append("\n    @$annotation\n    lateinit var ${it.field}: ${it.simpleName}\n") }
        append("\n    @Test\n    fun `${t.testName}`() {\n")
        t.notes.forEach { append("        // $it\n") }
        for (s in t.stubs) {
            s.notes.forEach { append("        // $it\n") }
            if (s.values.isEmpty()) continue
            val values = s.values.joinToString(",\n                ") { kotlinValue(it) }
            append("        given(${s.field}.${s.method}(${s.matchers.joinToString(", ")}))\n")
            append("            .willReturn(\n                $values,\n            )\n")
        }
        if (t.stubs.isNotEmpty()) append("\n")
        append("        mockMvc.perform(\n            ${requestCall(t, ::kotlinString)}")
        t.contextPath?.let { append("\n                .contextPath(${kotlinString(it)})") }
        t.headers.forEach { (k, v) -> append("\n                .header(${kotlinString(k)}, ${kotlinString(v)})") }
        t.contentType?.let { append("\n                .contentType(${kotlinString(it)})") }
        t.body?.let { append("\n                .content(${kotlinBlock(it, 16)})") }
        append(",\n        )\n")
        append("            .andExpect(status().`is`(${t.status}))")
        when {
            t.responseBody == null -> {}
            t.responseIsJson -> append("\n            .andExpect(content().json(${kotlinBlock(t.responseBody, 12)}))")
            else -> append("\n            .andExpect(content().string(${kotlinBlock(t.responseBody, 12)}))")
        }
        append("\n    }\n")
        if (t.stubs.any { s -> s.matchers.contains("anyArg()") }) {
            append("\n    /** `any()` for a Kotlin parameter that is not nullable. */\n")
            append("    private fun <T> anyArg(): T = ArgumentMatchers.any<T>()\n")
        }
        append("}\n")
    }

    private fun kotlinValue(v: StubValue): String {
        val read = if (v.type == null) kotlinBlock(v.text, 16)
        else "objectMapper.readValue(${kotlinBlock(v.text, 16)}, object : TypeReference<${v.type}>() {})"
        return when (v.wrapper) {
            StubValue.Wrapper.NONE -> read
            StubValue.Wrapper.OPTIONAL -> "Optional.of($read)"
            StubValue.Wrapper.RESPONSE_ENTITY -> "ResponseEntity.status(${v.status}).body($read)"
        }
    }

    fun kotlinString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    /** A one-line text as a string literal, anything longer as a raw string with `trimIndent()`. */
    fun kotlinBlock(s: String, indent: Int): String {
        if (!s.contains('\n') && s.length <= 80) return kotlinString(s)
        val pad = " ".repeat(indent + 4)
        val raw = s.replace("\r", "").replace("$", "\${'$'}").replace("\"\"\"", "\${\"\\\"\\\"\\\"\"}")
        return "\"\"\"\n" + raw.split('\n').joinToString("\n") { if (it.isEmpty()) "" else pad + it } + "\n" + pad + "\"\"\".trimIndent()"
    }
}
