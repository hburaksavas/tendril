package dev.tendril.recordings

import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import dev.tendril.ui.Palette
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.net.URI
import java.util.Date
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/** A recording stays on one line until its own fields no longer fit the viewport. */
internal class HttpRecordingRenderer : JPanel(null), ListCellRenderer<Recording> {
    private val method = JLabel("", SwingConstants.CENTER)
    private val route = JLabel()
    private val result = JLabel()
    private val origin = JLabel()
    private val timing = JLabel()
    private var routeText = ""
    private var available = 0
    private val gap get() = JBUI.scale(8)
    private val methodWidth get() = method.getFontMetrics(method.font).stringWidth("DELETE") + gap * 2
    private val singleWidth get() = methodWidth + route.preferredSize.width + origin.preferredSize.width +
        result.preferredSize.width + timing.preferredSize.width + gap * 6
    private var compact = false
    private val lineHeight get() = maxOf(JBUI.scale(22), getFontMetrics(font).height + JBUI.scale(4))

    init {
        method.isOpaque = true
        listOf(method, route, result, origin, timing).forEach(::add)
    }

    override fun getListCellRendererComponent(list: JList<out Recording>, value: Recording, index: Int,
                                             isSelected: Boolean, cellHasFocus: Boolean): Component {
        font = list.font
        method.font = font.deriveFont(Font.BOLD, font.size2D - 1)
        route.font = font
        result.font = font.deriveFont(Font.BOLD)
        origin.font = font
        timing.font = font
        val uri = value.url?.let { runCatching { URI.create(it) }.getOrNull() }
        routeText = uri?.let { it.rawPath.orEmpty().ifEmpty { "/" } + (it.rawQuery?.let { q -> "?$q" } ?: "") }
            ?: value.url.orEmpty()
        route.text = routeText
        origin.text = if (value.kind == RecordingKind.OUTGOING) uri?.let {
            it.host.orEmpty() + if (it.port >= 0) ":${it.port}" else ""
        }.orEmpty() else value.handler.orEmpty()
        method.text = value.method ?: "GET"
        result.text = listOfNotNull(value.status?.toString() ?: value.error?.let { "Failed" },
            value.mock?.let { "mocked" }).joinToString(" · ")
        timing.text = "${RecordingText.TIME.format(Date(value.time))} · ${value.durationMs} ms"
        background = if (isSelected) list.selectionBackground else list.background
        val foreground = if (isSelected) list.selectionForeground else list.foreground
        val accent = Palette.method(value.method)
        method.foreground = if (isSelected) foreground else accent
        method.background = ColorUtil.mix(background, if (isSelected) foreground else accent, 0.12)
        route.foreground = foreground
        result.foreground = if (isSelected) foreground else Palette.status(value.status)
        origin.foreground = if (isSelected) foreground else Palette.MUTED
        timing.foreground = foreground
        available = list.fixedCellWidth.takeIf { it > 0 } ?: list.width
        compact = available < singleWidth
        getAccessibleContext().accessibleName = listOf(method.text, routeText, result.text, origin.text, timing.text).joinToString(" ")
        return this
    }

    override fun getPreferredSize() = Dimension(available.coerceAtLeast(1), if (compact) lineHeight * 2 else lineHeight + JBUI.scale(4))

    // CellRendererPane can paint a detached renderer without validating its children.
    override fun paintChildren(g: java.awt.Graphics) {
        doLayout()
        super.paintChildren(g)
    }

    override fun doLayout() {
        val rowHeight = if (compact) height / 2 else height
        val chipHeight = method.preferredSize.height + JBUI.scale(2)
        method.setBounds(gap, (rowHeight - chipHeight) / 2, methodWidth, chipHeight)
        val routeX = method.x + method.width + gap
        val resultEnd = if (compact) width - gap else width - timing.preferredSize.width - origin.preferredSize.width - 3 * gap
        val resultX = resultEnd - result.preferredSize.width
        result.setBounds(resultX, 0, result.preferredSize.width, rowHeight)
        val routeWidth = (resultX - gap - routeX).coerceAtLeast(0)
        route.text = middleEllipsis(routeText, route.getFontMetrics(route.font), routeWidth)
        route.setBounds(routeX, 0, routeWidth, rowHeight)
        val y = if (compact) rowHeight else 0
        val timeWidth = timing.preferredSize.width
        val timeX = width - gap - timeWidth
        timing.setBounds(timeX, y, timeWidth, rowHeight)
        val originX = if (compact) gap else resultEnd + gap
        origin.setBounds(originX, y, (timeX - gap - originX).coerceAtLeast(0), rowHeight)
    }

    private fun middleEllipsis(text: String, metrics: FontMetrics, width: Int): String {
        if (metrics.stringWidth(text) <= width) return text
        var low = 0
        var high = text.length
        fun shortened(n: Int) = text.take(n / 3) + "…" + text.takeLast(n - n / 3)
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (metrics.stringWidth(shortened(mid)) <= width) low = mid else high = mid - 1
        }
        return shortened(low)
    }
}
