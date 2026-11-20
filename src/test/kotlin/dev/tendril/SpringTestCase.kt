package dev.tendril

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * Light fixture tests without Spring on the class path: the annotations the plugin looks for are declared as
 * source in the fixture project, with the same qualified names.
 */
abstract class SpringTestCase : LightJavaCodeInsightFixtureTestCase() {

    // a real JDK, so that String, List and friends resolve
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_LATEST_WITH_LATEST_JDK

    override fun setUp() {
        super.setUp()
        addSpringStubs()
    }

    private fun annotation(fqn: String, members: String = "", meta: String = "") {
        val pkg = fqn.substringBeforeLast('.')
        val name = fqn.substringAfterLast('.')
        myFixture.addClass(
            "package $pkg;\n" +
                "import java.lang.annotation.*;\n" +
                "@Retention(RetentionPolicy.RUNTIME)\n$meta\npublic @interface $name { $members }",
        )
    }

    private fun addSpringStubs() {
        annotation("org.springframework.stereotype.Component", "String value() default \"\";")
        annotation(
            "org.springframework.stereotype.Service", "String value() default \"\";",
            "@org.springframework.stereotype.Component",
        )
        annotation(
            "org.springframework.stereotype.Repository", "String value() default \"\";",
            "@org.springframework.stereotype.Component",
        )
        annotation(
            "org.springframework.stereotype.Controller", "String value() default \"\";",
            "@org.springframework.stereotype.Component",
        )
        annotation(
            "org.springframework.web.bind.annotation.RestController", "String value() default \"\";",
            "@org.springframework.stereotype.Controller",
        )
        myFixture.addClass("package org.springframework.web.bind.annotation; public enum RequestMethod { GET, POST, PUT, DELETE, PATCH }")
        annotation(
            "org.springframework.web.bind.annotation.RequestMapping",
            "String[] value() default {}; String[] path() default {}; RequestMethod[] method() default {};",
        )
        for (verb in listOf("Get", "Post", "Put", "Delete", "Patch")) {
            annotation("org.springframework.web.bind.annotation.${verb}Mapping", "String[] value() default {}; String[] path() default {};")
        }
        annotation("org.springframework.web.bind.annotation.PathVariable", "String value() default \"\"; String name() default \"\";")
        annotation(
            "org.springframework.web.bind.annotation.RequestParam",
            "String value() default \"\"; String name() default \"\"; boolean required() default true;",
        )
        annotation("org.springframework.web.bind.annotation.RequestBody")
        annotation("org.springframework.web.bind.annotation.RequestHeader", "String value() default \"\"; String name() default \"\";")
        annotation("org.springframework.beans.factory.annotation.Value", "String value();")
        annotation("org.springframework.beans.factory.annotation.Autowired", "boolean required() default true;")
        annotation("org.springframework.beans.factory.annotation.Qualifier", "String value() default \"\";")
        annotation("org.springframework.boot.context.properties.ConfigurationProperties", "String value() default \"\"; String prefix() default \"\";")
        annotation("org.springframework.validation.annotation.Validated")
        annotation("jakarta.validation.constraints.NotBlank")
        annotation("jakarta.validation.constraints.NotNull")
        annotation("org.springframework.context.annotation.Bean", "String[] value() default {};")
        annotation("org.springframework.context.annotation.Configuration")
        annotation("org.springframework.context.annotation.Primary")
        annotation("org.springframework.context.annotation.Lazy")
        annotation(
            "org.springframework.cloud.openfeign.FeignClient",
            "String name() default \"\"; String value() default \"\"; String serviceId() default \"\"; " +
                "String contextId() default \"\"; String url() default \"\"; String path() default \"\";",
        )
    }

    protected fun yaml(name: String, text: String) = myFixture.addFileToProject(name, text.trimIndent())
}
