package dev.tendril

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiElement
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.toUElementOfExpectedTypes

/**
 * The Java view of a declaration. The bean and config models are built from Java PSI, which for Kotlin means the light
 * classes; this maps a declaration in either language to the element the models know (Java source: itself; Kotlin:
 * the light class, method, field or parameter). A Kotlin constructor property is reported as its parameter.
 */
internal fun javaViewOf(declaration: PsiElement): PsiElement? =
    if (declaration.language == JavaLanguage.INSTANCE) declaration
    else declaration.toUElementOfExpectedTypes(
        UParameter::class.java, UField::class.java, UClass::class.java, UMethod::class.java,
    )?.javaPsi
