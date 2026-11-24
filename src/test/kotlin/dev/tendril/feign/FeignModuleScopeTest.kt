package dev.tendril.feign

import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase
import dev.tendril.config.ConfigNavigation
import java.io.File

/** Two unrelated modules in one project (light tests cannot add modules): each client reads its own module's config. */
class FeignModuleScopeTest : JavaCodeInsightFixtureTestCase() {

    fun `test the config of an unrelated module is not read`() {
        myFixture.addClass(
            "package org.springframework.cloud.openfeign;\n" +
                "public @interface FeignClient { String name() default \"\"; String value() default \"\"; " +
                "String url() default \"\"; String path() default \"\"; }",
        )
        myFixture.addClass(
            "package app;\n" +
                "@org.springframework.cloud.openfeign.FeignClient(name = \"inventory\", url = \"\${clients.inventory.url}\")\n" +
                "public interface ApiClient {}",
        )
        myFixture.addFileToProject("application.yml", "clients:\n  inventory:\n    url: http://own-module\n")

        val dir = FileUtil.createTempDirectory("other-service", null)
        File(dir, "application.yml").writeText(
            "clients:\n  inventory:\n    url: http://other-module\nspring:\n  profiles:\n    active: x\n",
        )
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        val other = PsiTestUtil.addModule(project, JavaModuleType.getModuleType(), "other-service", root)
        PsiTestUtil.addSourceRoot(other, root)

        // the project sees both files, so only the module scope keeps the other one out
        assertEquals(2, ConfigNavigation.allConfigElements(project, "clients.inventory.url").size)
        val info = FeignResolver.resolve(project, FeignScanner.scan(project).single(), null)
        assertEquals("http://own-module", info.resolution.url)
        assertEquals("url attribute \${clients.inventory.url}", info.resolution.source)
    }
}
