package dev.tendril.startup

import com.intellij.psi.search.GlobalSearchScope
import dev.tendril.SpringTestCase

class StartupChoicesTest : SpringTestCase() {

    private fun choices(target: StartupTarget, listener: PortListener? = null) =
        StartupChoices(project, GlobalSearchScope.allScope(project)).of(target, listener)

    private fun texts(target: StartupTarget) = choices(target).map { it.text }

    private fun docText(name: String) =
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(name))!!.text

    private fun choose(target: StartupTarget, text: String) =
        choices(target).single { it.text == text }.perform(project)

    fun `test missing concrete class can be registered`() {
        myFixture.addClass("package app; public class PaymentClient {}")
        val target = StartupTarget.Type("app.PaymentClient", missingBean = true)
        assertEquals(listOf("Go to PaymentClient", "Annotate 'PaymentClient' with @Component"), texts(target))

        choose(target, "Annotate 'PaymentClient' with @Component")
        val cls = myFixture.findClass("app.PaymentClient")
        assertNotNull(cls.getAnnotation("org.springframework.stereotype.Component"))
    }

    fun `test missing interface offers its implementations that are no beans`() {
        myFixture.addClass("package app; public interface Gateway {}")
        myFixture.addClass("package app; public class Stripe implements Gateway {}")
        myFixture.addClass("package app; @org.springframework.stereotype.Service public class Paypal implements Gateway {}")
        assertEquals(
            listOf("Go to Gateway", "Go to implementation Paypal", "Go to implementation Stripe", "Annotate 'Stripe' with @Component"),
            texts(StartupTarget.Type("app.Gateway", missingBean = true)),
        )
    }

    fun `test a type that is not missing is only navigated to`() {
        myFixture.addClass("package app; public class MailProperties {}")
        assertEquals(listOf("Go to MailProperties"), texts(StartupTarget.Type("app.MailProperties")))
        assertEquals(emptyList<String>(), texts(StartupTarget.Type("app.Unknown", missingBean = true)))
    }

    fun `test injection points resolve to the member`() {
        myFixture.addClass(
            """
            package app;
            public class OrderService {
                private Object client;
                public OrderService(String a, Integer payments) {}
                public Object setup(String name, Long limit) { return null; }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("Go to payments in OrderService"), texts(StartupTarget.InjectionPoint("app.OrderService", Member.ConstructorParameter(1))))
        assertEquals(listOf("Go to client in OrderService"), texts(StartupTarget.InjectionPoint("app.OrderService", Member.Field("client"))))
        assertEquals(listOf("Go to limit in OrderService"), texts(StartupTarget.InjectionPoint("app.OrderService", Member.MethodParameter("setup", 1))))
        assertEquals(listOf("Go to OrderService in OrderService"), texts(StartupTarget.InjectionPoint("app.OrderService", Member.Field("missing"))))
    }

    fun `test ambiguous candidate can be marked primary`() {
        myFixture.addClass(
            """
            package app;
            @org.springframework.context.annotation.Configuration
            public class PaymentConfig { @org.springframework.context.annotation.Bean public Object paypal() { return null; } }
            """.trimIndent(),
        )
        val target = StartupTarget.BeanDefinition("paypal", StartupReport.classNames("app/PaymentConfig.class"), "paypal")
        assertEquals(listOf("Go to bean 'paypal'", "Mark 'paypal' as @Primary"), texts(target))

        choose(target, "Mark 'paypal' as @Primary")
        val method = myFixture.findClass("app.PaymentConfig").findMethodsByName("paypal", false).single()
        assertNotNull(method.getAnnotation("org.springframework.context.annotation.Primary"))
        assertEquals(listOf("Go to bean 'paypal'"), texts(target))
    }

    fun `test missing key is added to the main configuration`() {
        myFixture.addFileToProject("application.properties", "server.port=8080\n")
        myFixture.addFileToProject("application-dev.properties", "")
        val target = StartupTarget.ConfigKey("payment.url")
        assertEquals(listOf("Add 'payment.url' to application.properties"), texts(target))

        choose(target, "Add 'payment.url' to application.properties")
        val text = docText("application.properties")
        assertTrue(text, text.contains("payment.url="))
    }

    fun `test missing key is added to yml as nested keys`() {
        yaml("application.yml", "app:\n  name: shop")
        choose(StartupTarget.ConfigKey("app.mail.host"), "Add 'app.mail.host' to application.yml")
        val text = docText("application.yml")
        assertTrue(text, text.contains("mail:") && text.contains("host:"))
    }

    fun `test existing key is navigated to`() {
        yaml("application.yml", "app:\n  mail:\n    port: abc")
        assertEquals(listOf("Go to app.mail.port in application.yml"), texts(StartupTarget.ConfigKey("app.mail.port")))
    }

    fun `test origin finds the source file`() {
        yaml("config/application.yml", "a: 1")
        yaml("application.yml", "a: 2")
        val choices = StartupChoices(project, GlobalSearchScope.allScope(project))
        assertEquals("/src/config/application.yml", choices.originFile("/shop/target/classes/config/application.yml")?.path)
        assertEquals(listOf("Go to application.yml:3"), texts(StartupTarget.Origin("application.yml", 3, 9)))
    }

    fun `test port in use is changed to a free one`() {
        myFixture.addFileToProject("application.properties", "server.port=8080\n")
        val free = StartupChoices.freePort(8080)!!
        val running = RunningApp("OrderApp") {}
        assertEquals(
            listOf("Stop 'OrderApp', which is listening on 8080", "Set server.port to $free in application.properties", "Go to server.port in application.properties"),
            choices(StartupTarget.Port(8080), PortListener("java.exe (PID 1)", running)).map { it.text },
        )
        choose(StartupTarget.Port(8080), "Set server.port to $free in application.properties")
        val text = docText("application.properties")
        assertTrue(text, text.contains("server.port=$free"))
    }

    fun `test port without configuration adds server port`() {
        yaml("application.yml", "app:\n  name: shop")
        val free = StartupChoices.freePort(8080)!!
        assertEquals(
            listOf("Add server.port=$free to application.yml", "Port 8080 is used by java.exe (PID 1)"),
            choices(StartupTarget.Port(8080), PortListener("java.exe (PID 1)", null)).map { it.text },
        )
    }
}
