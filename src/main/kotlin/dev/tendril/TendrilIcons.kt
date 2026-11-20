package dev.tendril

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object TendrilIcons {
    /** Green leaf with a blue arrow: config key -> Java code that uses it. */
    @JvmField
    val ConfigToCode: Icon = IconLoader.getIcon("/icons/configToCode.svg", TendrilIcons::class.java)

    /** Green leaf with an orange arrow: Java code -> config key it reads. */
    @JvmField
    val CodeToConfig: Icon = IconLoader.getIcon("/icons/codeToConfig.svg", TendrilIcons::class.java)

    /** Green leaf with a purple up arrow: bean definition -> places it is injected into. */
    @JvmField
    val BeanUsages: Icon = IconLoader.getIcon("/icons/beanUsages.svg", TendrilIcons::class.java)
}
