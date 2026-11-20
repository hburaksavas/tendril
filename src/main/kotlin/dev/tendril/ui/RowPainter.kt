package dev.tendril.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTree
import javax.swing.ListCellRenderer
import javax.swing.tree.TreeCellRenderer

/** A piece of text in a row, with its own colour and weight. */
internal class Fragment(val text: String, val color: Color? = null, val bold: Boolean = false)

/** The colours the windows share: one hue per HTTP method, one per status class, a muted accent for variables. */
internal object Palette {
    val GET = JBColor(0x2E7D32, 0x6AAB73)
    val POST = JBColor(0xB26A00, 0xE0A44D)
    val PUT = JBColor(0x1E66F5, 0x6FA8FF)
    val PATCH = JBColor(0x7E57C2, 0xB39DDB)
    val DELETE = JBColor(0xC62828, 0xF28B82)
    val OTHER = JBColor(0x6C707E, 0xA0A4AE)
    val VARIABLE = JBColor(0x5A52B0, 0x9D96E8)
    val OK = JBColor(0x2E7D32, 0x6AAB73)
    val REDIRECT = JBColor(0xB26A00, 0xE0A44D)
    val FAILED = JBColor(0xC62828, 0xF28B82)
    val MUTED: Color get() = JBColor.GRAY

    fun method(method: String?): Color = when (method?.uppercase()) {
        "GET" -> GET
        "POST" -> POST
        "PUT" -> PUT
        "PATCH" -> PATCH
        "DELETE" -> DELETE
        else -> OTHER
    }

    private val RABBIT = JBColor(0xB26A00, 0xE0A44D)
    private val KAFKA = JBColor(0x1E66F5, 0x6FA8FF)

    fun messageTransport(transport: String?): Color = when (transport) {
        "rabbit" -> RABBIT
        "kafka" -> KAFKA
        else -> OTHER
    }

    fun status(code: Int?): Color = when (code) {
        null -> FAILED
        in 200..299 -> OK
        in 300..399 -> REDIRECT
        else -> FAILED
    }

    /** `/api/{id}/items?x=1` as fragments: variables in the accent colour, the query muted. */
    fun path(path: String): List<Fragment> {
        val query = path.indexOf('?')
        val route = if (query >= 0) path.substring(0, query) else path
        val out = mutableListOf<Fragment>()
        var last = 0
        for (m in Regex("\\{[^}]*}").findAll(route)) {
            if (m.range.first > last) out += Fragment(route.substring(last, m.range.first))
            out += Fragment(m.value, VARIABLE)
            last = m.range.last + 1
        }
        if (last < route.length) out += Fragment(route.substring(last))
        if (query >= 0) out += Fragment(path.substring(query), MUTED)
        return out
    }
}

/**
 * One row of a list or tree, painted in columns: an optional icon, a badge (the HTTP method or a status), the main
 * text, and secondary text in a column of its own so that the rows line up like a table.
 */
internal class RowPainter : JComponent() {
    var icon: Icon? = null
    var badge: String? = null
    var badgeColor: Color = Palette.OTHER

    /** Width of the badge column; every row of one window uses the same value. */
    var badgeWidth: Int = 0
    var primary: List<Fragment> = emptyList()

    /** Width of the main text column, so the secondary text starts at the same x in every row. */
    var primaryColumn: Int = 0
    var secondary: List<Fragment> = emptyList()
    var selected: Boolean = false
    var selectedForeground: Color = Color.WHITE
    var normalForeground: Color = Color.BLACK
    var fillBackground: Color? = null

    /** When set (lists), the secondary text sits at the right edge and the main text is shortened to make room. */
    var rightAlignedWidth: Int = 0

    /** A right-aligned row never asks for more width than the list gives it, so the list does not scroll sideways. */
    var rightAligned: Boolean = false

    init {
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension =
        Dimension(if (rightAligned) JBUI.scale(120) else run(null) + JBUI.scale(12), JBUI.scale(26))

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        GraphicsUtil.setupAAPainting(g2)
        fillBackground?.let {
            g2.color = it
            g2.fillRect(0, 0, width, height)
        }
        run(g2)
    }

    /** Lays the row out; paints it when [g] is given. Returns the width used. */
    private fun run(g: Graphics2D?): Int {
        val base = font ?: UIUtil.getLabelFont()
        val regular = getFontMetrics(base)
        val bold = getFontMetrics(base.deriveFont(Font.BOLD))
        val baseline = (height - regular.height) / 2 + regular.ascent
        var x = JBUI.scale(8)

        icon?.let {
            g?.let { gg -> it.paintIcon(this, gg, x, (height - it.iconHeight) / 2) }
            x += it.iconWidth + JBUI.scale(6)
        }

        badge?.let { text ->
            val chipFont = base.deriveFont(Font.BOLD, base.size2D - 1f)
            val chip = getFontMetrics(chipFont)
            val padX = JBUI.scale(6)
            val w = if (badgeWidth > 0) badgeWidth else chip.stringWidth(text) + 2 * padX
            if (g != null) {
                val h = chip.height + JBUI.scale(2)
                val y = (height - h) / 2
                g.color = if (selected) Color(255, 255, 255, 46) else ColorUtil.withAlpha(badgeColor, 0.16)
                g.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), JBUI.scale(6).toFloat(), JBUI.scale(6).toFloat()))
                g.font = chipFont
                g.color = if (selected) selectedForeground else badgeColor
                g.drawString(text, x + (w - chip.stringWidth(text)) / 2, (height - chip.height) / 2 + chip.ascent)
            }
            x += w + JBUI.scale(10)
        }

        val start = x
        if (rightAlignedWidth > 0) {
            val secondaryWidth = measure(secondary)
            val secondaryX = rightAlignedWidth - secondaryWidth - JBUI.scale(10)
            val room = secondaryX - JBUI.scale(12) - start
            draw(g, fit(primary, room, regular, bold), start, baseline, regular, bold, base)
            draw(g, secondary, secondaryX, baseline, regular, bold, base)
            return rightAlignedWidth
        }
        x = draw(g, primary, x, baseline, regular, bold, base)
        if (secondary.isNotEmpty()) {
            x = maxOf(x, start + primaryColumn) + JBUI.scale(14)
            x = draw(g, secondary, x, baseline, regular, bold, base)
        }
        return x
    }

    private fun draw(g: Graphics2D?, parts: List<Fragment>, from: Int, baseline: Int, regular: FontMetrics, bold: FontMetrics, base: Font): Int {
        var x = from
        for (f in parts) {
            val metrics = if (f.bold) bold else regular
            if (g != null) {
                g.font = if (f.bold) base.deriveFont(Font.BOLD) else base
                g.color = if (selected) selectedForeground else f.color ?: normalForeground
                g.drawString(f.text, x, baseline)
            }
            x += metrics.stringWidth(f.text)
        }
        return x
    }

    /** [parts] cut to [room] pixels, ending in an ellipsis when something had to go. */
    private fun fit(parts: List<Fragment>, room: Int, regular: FontMetrics, bold: FontMetrics): List<Fragment> {
        if (room <= 0) return emptyList()
        val out = mutableListOf<Fragment>()
        var used = 0
        for (f in parts) {
            val m = if (f.bold) bold else regular
            val w = m.stringWidth(f.text)
            if (used + w <= room) {
                out += f
                used += w
                continue
            }
            var text = f.text
            val ellipsis = m.stringWidth("…")
            while (text.isNotEmpty() && used + m.stringWidth(text) + ellipsis > room) text = text.dropLast(1)
            out += Fragment(text + "…", f.color, f.bold)
            return out
        }
        return out
    }

    /** Natural width of [parts], to size a column. */
    fun measure(parts: List<Fragment>): Int {
        val base = font ?: UIUtil.getLabelFont()
        val regular = getFontMetrics(base)
        val bold = getFontMetrics(base.deriveFont(Font.BOLD))
        return parts.sumOf { (if (it.bold) bold else regular).stringWidth(it.text) }
    }

    /** Width of a badge that fits the widest of [labels]. */
    fun badgeColumn(labels: Collection<String>): Int {
        val base = font ?: UIUtil.getLabelFont()
        val chip = getFontMetrics(base.deriveFont(Font.BOLD, base.size2D - 1f))
        return (labels.maxOfOrNull { chip.stringWidth(it) } ?: 0) + 2 * JBUI.scale(6)
    }
}

/** Tree renderer that hands the painter to [bind] with the node's user object. */
internal class PainterTreeRenderer(private val bind: (RowPainter, Any?) -> Unit) : TreeCellRenderer {
    val painter = RowPainter()

    override fun getTreeCellRendererComponent(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ): Component {
        painter.font = tree.font
        painter.icon = null
        painter.badge = null
        painter.primary = emptyList()
        painter.secondary = emptyList()
        painter.selected = selected
        painter.selectedForeground = UIUtil.getTreeSelectionForeground(hasFocus)
        painter.normalForeground = UIUtil.getTreeForeground()
        bind(painter, (value as? javax.swing.tree.DefaultMutableTreeNode)?.userObject)
        return painter
    }
}

/** List renderer; unlike a tree, a list leaves the selection background to the renderer. */
internal class PainterListRenderer<T>(
    private val rightAligned: Boolean = false,
    private val bind: (RowPainter, T) -> Unit,
) : ListCellRenderer<T> {
    val painter = RowPainter()

    override fun getListCellRendererComponent(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean): Component {
        painter.font = list.font
        painter.icon = null
        painter.badge = null
        painter.primary = emptyList()
        painter.secondary = emptyList()
        painter.selected = selected
        painter.selectedForeground = UIUtil.getListSelectionForeground(true)
        painter.normalForeground = UIUtil.getListForeground()
        painter.fillBackground = if (selected) UIUtil.getListSelectionBackground(true) else list.background
        painter.rightAligned = rightAligned
        painter.rightAlignedWidth = if (rightAligned) list.width else 0
        if (value != null) bind(painter, value)
        return painter
    }
}
