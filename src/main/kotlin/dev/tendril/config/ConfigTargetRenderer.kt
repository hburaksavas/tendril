package dev.tendril.config

import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLKeyValue

/** Popup row for a config key target: `jwt.secret: abc` , then `[profile: dev] application-dev.yml (module)`. */
class ConfigTargetRenderer : PsiTargetPresentationRenderer<PsiElement>() {

    override fun getElementText(element: PsiElement): String = when (element) {
        is YAMLKeyValue -> ConfigNavigation.yamlPath(element).let { path ->
            element.valueText.takeIf { it.isNotBlank() }?.let { "$path: ${it.take(60)}" } ?: path
        }
        is Property -> "${element.unescapedKey} = ${element.unescapedValue.orEmpty().take(60)}"
        else -> element.text.take(60)
    }

    override fun getContainerText(element: PsiElement): String? {
        val profile = ConfigProfiles.profileOf(element)?.let { "[profile: $it]" } ?: "[default]"
        val file = element.containingFile?.name
        val module = ModuleUtilCore.findModuleForPsiElement(element)?.name?.let { "($it)" }
        return listOfNotNull(profile, file, module).joinToString(" ")
    }

    override fun getIcon(element: PsiElement): javax.swing.Icon? = null
}
