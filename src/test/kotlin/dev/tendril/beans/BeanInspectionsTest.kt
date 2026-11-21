package dev.tendril.beans

import com.intellij.codeInspection.InspectionProfileEntry
import dev.tendril.SpringTestCase
import dev.tendril.config.MissingRequiredPropertyInspection

class BeanInspectionsTest : SpringTestCase() {

    /** The inspection messages reported for [code], which is opened as [file]. */
    private fun report(file: String, code: String, vararg inspections: InspectionProfileEntry): List<String> {
        myFixture.enableInspections(*inspections)
        myFixture.configureByText(file, code.trimIndent())
        return myFixture.doHighlighting().mapNotNull { it.description }
    }

    private fun gateways(primary: Boolean = false) {
        myFixture.addClass("package app; public interface Gateway {}")
        myFixture.addClass(
            """
            package app;
            import org.springframework.stereotype.Service;
            ${if (primary) "import org.springframework.context.annotation.Primary;" else ""}
            ${if (primary) "@Primary" else ""} @Service public class Stripe implements Gateway {}
            """.trimIndent(),
        )
        myFixture.addClass("package app; import org.springframework.stereotype.Service; @Service public class Paypal implements Gateway {}")
    }

    // ---- no bean ----

    private fun consumerOf(type: String) = report(
        "Consumer.java",
        """
        package app;
        import org.springframework.stereotype.Component;
        @Component public class Consumer { public Consumer($type sink) {} }
        """,
        NoBeanFoundInspection(),
    )

    fun `test a plain class that is not a bean is reported`() {
        myFixture.addClass("package app; public class Sink {}")
        val messages = consumerOf("Sink")
        assertTrue(messages.toString(), messages.any { it.contains("No Spring bean of type 'Sink'") })
    }

    fun `test an interface whose implementations are not beans is reported`() {
        myFixture.addClass("package app; public interface Sink {}")
        myFixture.addClass("package app; public class FileSink implements Sink {}")
        val messages = consumerOf("Sink")
        assertTrue(messages.toString(), messages.any { it.contains("No Spring bean of type 'Sink'") })
    }

    fun `test an interface without any implementation stays silent because it is probably proxied`() {
        myFixture.addClass("package app; public interface Sink {}")
        val messages = consumerOf("Sink")
        assertTrue(messages.toString(), messages.none { it.contains("No Spring bean") })
    }

    fun `test an injected type with a bean is fine`() {
        myFixture.addClass("package app; public interface Sink {}")
        myFixture.addClass("package app; import org.springframework.stereotype.Component; @Component public class FileSink implements Sink {}")
        val messages = report(
            "Consumer.java",
            """
            package app;
            import org.springframework.stereotype.Component;
            @Component public class Consumer { public Consumer(Sink sink) {} }
            """,
            NoBeanFoundInspection(),
        )
        assertTrue(messages.toString(), messages.none { it.contains("No Spring bean") })
    }

    fun `test a bean method counts as a bean`() {
        myFixture.addClass("package app; public class Clock2 {}")
        myFixture.addClass(
            """
            package app;
            import org.springframework.context.annotation.*;
            @Configuration public class Config { @Bean public Clock2 clock() { return new Clock2(); } }
            """.trimIndent(),
        )
        val messages = report(
            "UsesClock.java",
            """
            package app;
            import org.springframework.stereotype.Component;
            @Component public class UsesClock { public UsesClock(Clock2 clock) {} }
            """,
            NoBeanFoundInspection(),
        )
        assertTrue(messages.toString(), messages.none { it.contains("No Spring bean") })
    }

    fun `test a custom stereotype makes a bean`() {
        myFixture.addClass(
            """
            package app;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @org.springframework.stereotype.Service
            public @interface DomainService {}
            """.trimIndent(),
        )
        myFixture.addClass("package app; public interface Rules {}")
        myFixture.addClass("package app; @DomainService public class DefaultRules implements Rules {}")
        val messages = report(
            "UsesRules.java",
            """
            package app;
            import org.springframework.stereotype.Component;
            @Component public class UsesRules { public UsesRules(Rules rules) {} }
            """,
            NoBeanFoundInspection(),
        )
        assertTrue(messages.toString(), messages.none { it.contains("No Spring bean") })
    }

    // ---- ambiguity ----

    fun `test two beans of the injected type are reported`() {
        gateways()
        val messages = report(
            "Consumer.java",
            """
            package app;
            import org.springframework.stereotype.Component;
            @Component public class Consumer { public Consumer(Gateway gateway) {} }
            """,
            AmbiguousBeanInspection(),
        )
        assertTrue(messages.toString(), messages.any { it.contains("Multiple beans of type 'Gateway'") })
    }

    fun `test a primary bean resolves the ambiguity`() {
        gateways(primary = true)
        val messages = report(
            "Consumer.java",
            """
            package app;
            import org.springframework.stereotype.Component;
            @Component public class Consumer { public Consumer(Gateway gateway) {} }
            """,
            AmbiguousBeanInspection(),
        )
        assertTrue(messages.toString(), messages.none { it.contains("Multiple beans") })
    }

    fun `test a qualifier resolves the ambiguity`() {
        gateways()
        val messages = report(
            "Consumer.java",
            """
            package app;
            import org.springframework.stereotype.Component;
            import org.springframework.beans.factory.annotation.Qualifier;
            @Component public class Consumer { public Consumer(@Qualifier("stripe") Gateway gateway) {} }
            """,
            AmbiguousBeanInspection(),
        )
        assertTrue(messages.toString(), messages.none { it.contains("Multiple beans") })
    }

    fun `test the parameter name can select the bean`() {
        gateways()
        val messages = report(
            "Consumer.java",
            """
            package app;
            import org.springframework.stereotype.Component;
            @Component public class Consumer { public Consumer(Gateway stripe) {} }
            """,
            AmbiguousBeanInspection(),
        )
        assertTrue(messages.toString(), messages.none { it.contains("Multiple beans") })
    }

    fun `test injecting a list of all beans is not ambiguous`() {
        gateways()
        val messages = report(
            "Consumer.java",
            """
            package app;
            import org.springframework.stereotype.Component;
            import java.util.List;
            @Component public class Consumer { public Consumer(List<Gateway> gateways) {} }
            """,
            AmbiguousBeanInspection(), NoBeanFoundInspection(),
        )
        assertTrue(messages.toString(), messages.none { it.contains("Multiple beans") || it.contains("No Spring bean") })
    }

    // ---- cycles and field injection ----

    fun `test a constructor cycle is reported`() {
        myFixture.addClass(
            """
            package app;
            import org.springframework.stereotype.Component;
            @Component public class B { public B(A a) {} }
            """.trimIndent(),
        )
        val messages = report(
            "A.java",
            """
            package app;
            import org.springframework.stereotype.Component;
            @Component public class A { public A(B b) {} }
            """,
            CircularDependencyInspection(),
        )
        assertTrue(messages.toString(), messages.any { it.contains("Circular bean dependency") })
    }

    fun `test lazy breaks the cycle`() {
        myFixture.addClass(
            """
            package app;
            import org.springframework.stereotype.Component;
            @Component public class B { public B(A a) {} }
            """.trimIndent(),
        )
        val messages = report(
            "A.java",
            """
            package app;
            import org.springframework.stereotype.Component;
            import org.springframework.context.annotation.Lazy;
            @Component public class A { public A(@Lazy B b) {} }
            """,
            CircularDependencyInspection(),
        )
        assertTrue(messages.toString(), messages.none { it.contains("Circular") })
    }

    fun `test field injection is flagged and constructor injection is not`() {
        myFixture.addClass("package app; import org.springframework.stereotype.Service; @Service public class Dep {}")
        val flagged = report(
            "WithField.java",
            """
            package app;
            import org.springframework.stereotype.Component;
            import org.springframework.beans.factory.annotation.Autowired;
            @Component public class WithField { @Autowired private Dep dep; }
            """,
            FieldInjectionInspection(),
        )
        assertTrue(flagged.toString(), flagged.any { it.contains("Field injection is not recommended") })

        val fine = report(
            "WithConstructor.java",
            """
            package app;
            import org.springframework.stereotype.Component;
            @Component public class WithConstructor { private final Dep dep; public WithConstructor(Dep dep) { this.dep = dep; } }
            """,
            FieldInjectionInspection(),
        )
        assertTrue(fine.toString(), fine.none { it.contains("Field injection") })
    }

    fun `test constructor parameters of configuration properties classes are bound, not injected`() {
        myFixture.addClass("package app; public class Security { public String secret; }")
        val messages = report(
            "AppProps.java",
            """
            package app;
            import org.springframework.boot.context.properties.ConfigurationProperties;
            @ConfigurationProperties(prefix = "app")
            public class AppProps { public AppProps(Security security) {} }
            """,
            NoBeanFoundInspection(), AmbiguousBeanInspection(),
        )
        assertTrue(messages.toString(), messages.none { it.contains("No Spring bean") || it.contains("Multiple beans") })
    }

    // ---- required configuration ----

    private fun required(validated: Boolean = true) = """
        package app;
        import org.springframework.boot.context.properties.ConfigurationProperties;
        import org.springframework.validation.annotation.Validated;
        import jakarta.validation.constraints.NotBlank;
        ${if (validated) "@Validated" else ""}
        @ConfigurationProperties(prefix = "broken.demo")
        public class BrokenProperties { @NotBlank private String requiredToken; private String note; }
    """

    fun `test a required property that no config file defines is reported`() {
        val messages = report("BrokenProperties.java", required(), MissingRequiredPropertyInspection())
        assertTrue(messages.toString(), messages.any { it.contains("Required property 'broken.demo.required-token' is not defined") })
    }

    fun `test a defined required property is fine in any spelling`() {
        yaml("application.yml", "broken:\n  demo:\n    requiredToken: abc")
        val messages = report("BrokenProperties.java", required(), MissingRequiredPropertyInspection())
        assertTrue(messages.toString(), messages.none { it.contains("Required property") })
    }

    fun `test a class that is not validated is not checked`() {
        val messages = report("BrokenProperties.java", required(validated = false), MissingRequiredPropertyInspection())
        assertTrue(messages.toString(), messages.none { it.contains("Required property") })
    }

    fun `test a field with an initializer is not required`() {
        val messages = report(
            "WithDefault.java",
            """
            package app;
            import org.springframework.boot.context.properties.ConfigurationProperties;
            import org.springframework.validation.annotation.Validated;
            import jakarta.validation.constraints.NotBlank;
            @Validated @ConfigurationProperties(prefix = "with.default")
            public class WithDefault { @NotBlank private String token = "x"; }
            """,
            MissingRequiredPropertyInspection(),
        )
        assertTrue(messages.toString(), messages.none { it.contains("Required property") })
    }
}
