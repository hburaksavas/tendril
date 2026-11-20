package dev.tendril

import com.intellij.ui.EditorTextField
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/** Paints a component off screen into a PNG under build/ui-preview, to look at a layout without starting the IDE. */
object UiSnapshot {
    private val dir = File("build/ui-preview").apply { mkdirs() }

    fun save(component: JComponent, name: String, width: Int = 900, height: Int = 420) {
        UIUtil.invokeAndWaitIfNeeded<Unit> {
            component.setSize(width, height)
            attach(component, true) // editors are created when a component is shown
            try {
                layoutAll(component)
                layoutAll(component)
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                g.color = component.background ?: Color.WHITE
                g.fillRect(0, 0, width, height)
                component.paint(g)
                g.dispose()
                ImageIO.write(image, "png", File(dir, "$name.png"))
            } finally {
                attach(component, false)
            }
        }
    }

    private fun attach(c: Component, show: Boolean) {
        if (c is EditorTextField) if (show) c.addNotify() else c.removeNotify()
        if (c is Container) c.components.forEach { attach(it, show) }
    }

    private fun layoutAll(c: Component) {
        c.doLayout()
        if (c is Container) c.components.forEach(::layoutAll)
        c.validate()
    }
}
