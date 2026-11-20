package dev.tendril.endpoints

import dev.tendril.SpringTestCase

class EndpointScannerTest : SpringTestCase() {

    private fun endpoints() = EndpointScanner.scan(project).map { "${it.httpMethod} ${it.path}" }

    fun `test combines the class path with the method path`() {
        myFixture.addClass(
            """
            package app;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/api/brands")
            public class BrandController {
                @GetMapping public String list() { return ""; }
                @GetMapping("/{id}") public String get(@PathVariable Long id) { return ""; }
                @PostMapping("/") public void create() {}
                @DeleteMapping(path = "{id}") public void delete(@PathVariable Long id) {}
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf("DELETE /api/brands/{id}", "GET /api/brands", "GET /api/brands/{id}", "POST /api/brands"),
            endpoints().sorted(),
        )
    }

    fun `test a request mapping without a method is ANY`() {
        myFixture.addClass(
            """
            package app;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class PingController {
                @RequestMapping("/ping") public String ping() { return ""; }
                @RequestMapping(value = "/both", method = {RequestMethod.GET, RequestMethod.POST}) public String both() { return ""; }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("ANY /ping", "GET /both", "POST /both"), endpoints().sorted())
    }

    fun `test plain controllers count and other classes do not`() {
        myFixture.addClass(
            """
            package app;
            import org.springframework.web.bind.annotation.*;
            import org.springframework.stereotype.Controller;
            @Controller
            public class ViewController { @GetMapping("/home") public String home() { return ""; } }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package app;
            import org.springframework.web.bind.annotation.*;
            public class NotAController { @GetMapping("/nope") public String nope() { return ""; } }
            """.trimIndent(),
        )
        assertEquals(listOf("GET /home"), endpoints())
    }

    fun `test the cached scan follows code changes`() {
        val file = myFixture.addClass(
            """
            package app;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class C { @GetMapping("/one") public String one() { return ""; } }
            """.trimIndent(),
        ).containingFile
        assertEquals(listOf("GET /one"), EndpointScanner.cached(project).map { "${it.httpMethod} ${it.path}" })

        myFixture.openFileInEditor(file.virtualFile)
        myFixture.type("")
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val doc = myFixture.editor.document
            doc.setText(doc.text.replace("/one", "/two"))
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        assertEquals(listOf("GET /two"), EndpointScanner.cached(project).map { "${it.httpMethod} ${it.path}" })
    }
}
