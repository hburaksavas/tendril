package dev.tendril.calls

import junit.framework.TestCase
import java.nio.file.Files

class FetchScannerTest : TestCase() {

    private fun scan(text: String, env: Map<String, String> = emptyMap()) =
        FetchScanner.scanText(text.trimIndent(), env).map { "${it.method} ${it.url}" }

    fun `test string and template literals`() {
        assertEquals(
            listOf("GET /api/orders", "GET /api/orders/*", "GET /api/orders/*/items"),
            scan(
                """
                fetch('/api/orders');
                const r = await fetch(`/api/orders/${'$'}{id}`)
                fetch("/api/orders/" + 1) // not understood: skipped below
                fetch(`/api/orders/${'$'}{id}/items?page=${'$'}{p}`)
                """,
            ).filter { it != "GET /api/orders/" },
        )
    }

    fun `test the method comes from the options`() {
        assertEquals(
            listOf("POST /api/orders", "DELETE /api/orders/*", "null /api/orders"),
            scan(
                """
                fetch('/api/orders', { method: 'POST', body: JSON.stringify(order) })
                fetch(`/api/orders/${'$'}{id}`, {
                  headers: { 'Content-Type': 'application/json' },
                  method: "delete",
                })
                fetch('/api/orders', options)
                """,
            ),
        )
    }

    fun `test the base address from env and from a constant`() {
        val env = mapOf("REACT_APP_API_URL" to "http://localhost:8080/", "VITE_API" to "http://localhost:9000")
        assertEquals(
            listOf(
                "GET http://localhost:8080/orders",
                "GET http://localhost:9000/orders/*",
                "GET http://localhost:8080/x",
                "GET */orders",
            ),
            scan(
                """
                fetch(`${'$'}{process.env.REACT_APP_API_URL}/orders`)
                fetch(`${'$'}{import.meta.env.VITE_API}/orders/${'$'}{id}`)
                const API = process.env.REACT_APP_API_URL ?? 'http://fallback';
                fetch(`${'$'}{API}/x`)
                fetch(`${'$'}{config.base}/orders`)
                """,
                env,
            ),
        )
    }

    fun `test a url held in a constant`() {
        assertEquals(listOf("GET /api/users"), scan("const url = '/api/users'\nconst res = fetch(url)"))
    }

    fun `test comments, other functions and files are skipped`() {
        assertEquals(
            emptyList<String>(),
            scan(
                """
                // fetch('/api/old')
                prefetch('/api/x'); useFetch('/api/y')
                fetch('./data.json')
                """,
            ),
        )
    }

    fun `test a project on disk with env files`() {
        val root = Files.createTempDirectory("fetch-scan")
        try {
            Files.writeString(root.resolve("package.json"), "{}")
            Files.writeString(root.resolve(".env"), "VITE_API=http://localhost:1111\n")
            Files.writeString(root.resolve(".env.local"), "VITE_API='http://localhost:8080'\n")
            Files.createDirectories(root.resolve("src/api"))
            Files.writeString(root.resolve("src/api/orders.ts"), "\n\nexport const get = (id) => fetch(`\${import.meta.env.VITE_API}/orders/\${id}`)\n")
            Files.createDirectories(root.resolve("node_modules/lib"))
            Files.writeString(root.resolve("node_modules/lib/index.js"), "fetch('/ignored')")
            Files.writeString(root.resolve("src/api/orders.test.ts"), "fetch('/ignored')")

            val calls = FetchScanner.scanRoot(root)
            assertEquals(listOf("http://localhost:8080/orders/*"), calls.map { it.url })
            assertEquals(2, calls.single().location.line)
            assertTrue(calls.single().location.file.endsWith("src/api/orders.ts"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
