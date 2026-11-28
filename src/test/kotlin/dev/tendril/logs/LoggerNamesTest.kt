package dev.tendril.logs

import junit.framework.TestCase

class LoggerNamesTest : TestCase() {

    private fun logger(line: String) = LoggerNames.find(line)?.let { line.substring(it) }

    fun `test logger column of Boot 3 and Boot 2 lines`() {
        assertEquals(
            "o.s.b.w.embedded.tomcat.TomcatWebServer",
            logger("2026-11-28T13:43:32.123+03:00  INFO 12345 --- [shop] [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat initialized with port 8080 (http)"),
        )
        assertEquals(
            "c.e.order.OrderService",
            logger("2026-11-28 13:43:32.123 DEBUG 12345 --- [nio-8080-exec-1] c.e.order.OrderService                   : Placing order 42"),
        )
        assertEquals(
            "app.OrderService",
            logger("2026-11-28T13:43:32.123Z  WARN 1 --- [shop] [main] [4bf92f3577b34da6-00f067aa0ba902b7] app.OrderService : slow"),
        )
    }

    fun `test lines in other formats are not linked`() {
        assertNull(logger("13:43:32.123 [main] INFO app.OrderService - started"))
        assertNull(logger("Exception in thread \"main\" java.lang.IllegalStateException: x"))
        assertNull(logger("2026-11-28 INFO 1 --- [main] ROOT : no dots"))
    }

    fun `test shortened names match their classes`() {
        assertTrue(LoggerNames.matches("o.s.b.w.embedded.tomcat.TomcatWebServer", "org.springframework.boot.web.embedded.tomcat.TomcatWebServer"))
        assertTrue(LoggerNames.matches("app.OrderService", "app.OrderService"))
        assertTrue(LoggerNames.matches("c.e.o.OrderService\$Audit", "com.example.order.OrderService.Audit"))
        assertTrue(LoggerNames.matches("app.OrderService\$\$SpringCGLIB\$\$0", "app.OrderService"))
        // cut on the left when even the shortened name is too long
        assertTrue(LoggerNames.matches("ervlet.AVeryLongControllerNameThatDoesNotFit", "app.web.servlet.AVeryLongControllerNameThatDoesNotFit"))

        assertFalse(LoggerNames.matches("o.s.b.w.embedded.tomcat.TomcatWebServer", "org.springframework.boot.web.server.TomcatWebServer"))
        assertFalse(LoggerNames.matches("c.e.OrderService", "app.OrderService"))
        assertFalse(LoggerNames.matches("app.OrderService", "app.OrderServiceImpl"))
    }

    fun `test complete names`() {
        assertTrue(LoggerNames.isComplete("org.hibernate.SQL"))
        assertFalse(LoggerNames.isComplete("o.h.SQL"))
        assertEquals("Audit", LoggerNames.simpleName("c.e.OrderService\$Audit"))
        assertEquals("OrderService", LoggerNames.simpleName("app.OrderService\$\$SpringCGLIB\$\$0"))
    }
}
