package dev.tendril

import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.io.File

/** Like [SpringTestCase], but the project also has the Kotlin standard library, so Kotlin sources resolve. */
abstract class KotlinSpringTestCase : SpringTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = KotlinDescriptor

    private object KotlinDescriptor : LightJavaCodeInsightFixtureTestCase.ProjectDescriptor(LanguageLevel.HIGHEST) {
        override fun getSdk(): Sdk? = JAVA_LATEST_WITH_LATEST_JDK.sdk

        override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
            super.configureModule(module, model, contentEntry)
            // the stdlib may come from a boot class loader without a code source: find its jar through a resource URL
            val resource = Unit::class.java.getResource("Unit.class")!!.toString() // jar:file:/.../kotlin-stdlib.jar!/kotlin/Unit.class
            val stdlib = File(java.net.URI(resource.substringAfter("jar:").substringBefore("!/")))
            val library = model.moduleLibraryTable.createLibrary("kotlin-stdlib").modifiableModel
            library.addRoot("jar://${stdlib.absolutePath.replace(File.separatorChar, '/')}!/", OrderRootType.CLASSES)
            library.commit()
        }
    }
}
