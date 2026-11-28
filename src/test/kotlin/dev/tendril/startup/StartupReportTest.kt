package dev.tendril.startup

import junit.framework.TestCase

class StartupReportTest : TestCase() {

    private fun targets(line: String) = StartupReport.links(line).map { line.substring(it.start, it.end) to it.target }

    fun `test ordinary log lines have no links`() {
        assertEquals(emptyList<ReportLink>(), StartupReport.links("2026-11-28 INFO  Started OrderApplication in 2.1 seconds"))
        assertEquals(emptyList<ReportLink>(), StartupReport.links("APPLICATION FAILED TO START"))
    }

    fun `test missing bean for a constructor parameter`() {
        val line = "Parameter 0 of constructor in app.order.OrderService required a bean of type 'app.pay.PaymentClient' that could not be found."
        assertEquals(
            listOf(
                "app.order.OrderService" to StartupTarget.InjectionPoint("app.order.OrderService", Member.ConstructorParameter(0)),
                "app.pay.PaymentClient" to StartupTarget.Type("app.pay.PaymentClient", missingBean = true),
            ),
            targets(line),
        )
    }

    fun `test field, method parameter and setter injection points`() {
        assertEquals(
            StartupTarget.InjectionPoint("app.OrderService", Member.Field("client")),
            targets("Field client in app.OrderService required a bean of type 'app.Client' that could not be found.").first().second,
        )
        assertEquals(
            StartupTarget.InjectionPoint("app.Config", Member.MethodParameter("orderService", 1)),
            targets("Parameter 1 of method orderService in app.Config required a single bean, but 2 were found:").single().second,
        )
        assertEquals(
            StartupTarget.InjectionPoint("app.Config", Member.Method("setClient")),
            targets("Method setClient in app.Config required a bean of type 'app.Client' that could not be found.").first().second,
        )
    }

    fun `test the action line names the missing type`() {
        assertEquals(
            listOf("app.pay.PaymentClient" to StartupTarget.Type("app.pay.PaymentClient", missingBean = true)),
            targets("Consider defining a bean of type 'app.pay.PaymentClient' in your configuration."),
        )
    }

    fun `test candidates of an ambiguous injection`() {
        val (name, target) = targets("\t- stripe: defined in file [D:\\shop\\target\\classes\\app\\pay\\Stripe.class]").single()
        assertEquals("stripe", name)
        target as StartupTarget.BeanDefinition
        assertEquals(null, target.method)
        assertTrue("app.pay.Stripe" in target.classes)
        assertEquals("shop.target.classes.app.pay.Stripe", target.classes.first())

        val byMethod = targets("\t- paypal: defined by method 'paypal' in class path resource [app/pay/PaymentConfig.class]").single().second
        assertEquals(StartupTarget.BeanDefinition("paypal", listOf("app.pay.PaymentConfig", "pay.PaymentConfig", "PaymentConfig"), "paypal"), byMethod)

        val byClassName = targets("\t- paypal: defined by method 'paypal' in app.pay.PaymentConfig").single().second
        assertEquals(StartupTarget.BeanDefinition("paypal", listOf("app.pay.PaymentConfig"), "paypal"), byClassName)

        val programmatic = targets("\t- clock: a programmatically registered singleton").single().second
        assertEquals(StartupTarget.BeanDefinition("clock", emptyList(), null), programmatic)
    }

    fun `test beans of a cycle`() {
        val defined = targets("|  orderService defined in file [/shop/build/classes/java/main/app/OrderService.class]").single().second
        assertTrue("app.OrderService" in (defined as StartupTarget.BeanDefinition).classes)

        val line = "|  paymentService (field private app.OrderService app.PaymentService.orders)"
        assertEquals(
            listOf(
                "paymentService" to StartupTarget.BeanDefinition("paymentService", listOf("app.PaymentService"), null),
                "(field private app.OrderService app.PaymentService.orders)" to
                    StartupTarget.InjectionPoint("app.PaymentService", Member.Field("orders")),
            ),
            targets(line),
        )
        assertEquals(emptyList<ReportLink>(), StartupReport.links("↑     ↓"))
    }

    fun `test binding failures`() {
        assertEquals(
            listOf(
                "app.mail" to StartupTarget.ConfigKey("app.mail"),
                "app.MailProperties" to StartupTarget.Type("app.MailProperties"),
            ),
            targets("Binding to target org.springframework.boot.context.properties.bind.BindException: Failed to bind properties under 'app.mail' to app.MailProperties failed:"),
        )
        assertEquals(listOf("app.mail.port" to StartupTarget.ConfigKey("app.mail.port")), targets("    Property: app.mail.port"))
        assertEquals(
            listOf("application.yml] - 12:11" to StartupTarget.Origin("application.yml", 12, 11)),
            targets("    Origin: class path resource [application.yml] - 12:11"),
        )
        assertEquals(
            StartupTarget.Origin("config/application.yml", 3, 9),
            targets("    Origin: class path resource [config/application.yml] from shop.jar - 3:9").single().second,
        )
        assertEquals(emptyList<ReportLink>(), StartupReport.links("    Origin: \"server.port\" from property source \"commandLineArgs\""))
    }

    fun `test port in use`() {
        assertEquals(StartupTarget.Port(8080), targets("Web server failed to start. Port 8080 was already in use.").single().second)
        assertEquals(
            StartupTarget.Port(8080),
            targets("Identify and stop the process that's listening on port 8080 or configure this application to listen on another port.").single().second,
        )
    }

    fun `test unresolved placeholder and data source url`() {
        assertEquals(
            listOf("payment.url" to StartupTarget.ConfigKey("payment.url")),
            targets("Caused by: java.lang.IllegalArgumentException: Could not resolve placeholder 'payment.url' in value \"\${payment.url}\""),
        )
        assertEquals(
            listOf("'url' attribute" to StartupTarget.ConfigKey("spring.datasource.url")),
            targets("Failed to configure a DataSource: 'url' attribute is not specified and no embedded datasource could be configured."),
        )
    }

    fun `test netstat rows`() {
        val output = """

              Proto  Local Address          Foreign Address        State           PID
              TCP    0.0.0.0:135            0.0.0.0:0              LISTENING       1104
              TCP    0.0.0.0:8080           0.0.0.0:0              DİNLENİYOR      23456
              TCP    127.0.0.1:52000        127.0.0.1:8080         ESTABLISHED     777
              TCP    [::]:9090              [::]:0                 LISTENING       4321
        """.trimIndent()
        assertEquals(23456L, PortListeners.parseNetstat(output, 8080))
        assertEquals(4321L, PortListeners.parseNetstat(output, 9090))
        assertEquals(null, PortListeners.parseNetstat(output, 52000))
    }
}
