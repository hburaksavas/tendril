package dev.tendril.config

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.psi.PsiAnnotation

/**
 * The first of [attributes] that has a non-blank string value. Spring declares aliases such as `value` / `name` /
 * `prefix` with an empty default, and the PSI reports that default, so a plain `?:` chain never reaches the alias
 * the code actually used.
 */
internal fun PsiAnnotation.firstNonBlank(vararg attributes: String): String? =
    attributes.firstNotNullOfOrNull { AnnotationUtil.getStringAttributeValue(this, it)?.takeIf(String::isNotBlank) }
