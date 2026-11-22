package dev.tendril.recordings

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel

/** One bar of the timeline: something that happened while the request was served. */
class WaterfallBar(
    val label: String,
    val badge: String,
    /** Milliseconds after the request began. */
    val startMs: Long,
    val durationMs: Long,
    val failed: Boolean,
    val root: Boolean,
) {
    val endMs get() = startMs + durationMs
}

/** The recordings that belong to one request, laid out on a common time axis. */
object Waterfall {

    /**
     * The request [root] and every outgoing call recorded in the same flow (the recorder ties calls made while serving
     * a request to that request). Calls are placed by their start time relative to the request, in start order.
     */
    fun build(root: Recording, outgoing: List<Recording>): List<WaterfallBar> {
        val bars = mutableListOf(
            WaterfallBar(
                label = root.handler ?: RecordingText.shortUrl(root, RecordingKind.INCOMING),
                badge = root.method ?: "GET",
                startMs = 0,
                durationMs = root.durationMs,
                failed = (root.status ?: 0) >= 500,
                root = true,
            ),
        )
        if (root.flow > 0) {
            outgoing.filter { it.flow == root.flow }.sortedBy { it.time }.forEach {
                bars += WaterfallBar(
                    label = it.client ?: RecordingText.shortUrl(it, RecordingKind.OUTGOING),
                    badge = it.method ?: "GET",
                    startMs = (it.time - root.time).coerceAtLeast(0),
                    durationMs = it.durationMs,
                    failed = it.error != null || (it.status ?: 0) >= 400,
                    root = false,
                )
            }
        }
        return bars
    }

    /** The length of the axis: the longest end, so that no bar leaves the chart. */
    fun total(bars: List<WaterfallBar>): Long = bars.maxOfOrNull { it.endMs }?.coerceAtLeast(1) ?: 1

    /** The part of the request the calls do not account for, or null when there are no calls. Overlaps count once. */
    fun selfTime(bars: List<WaterfallBar>): Long? {
        val root = bars.firstOrNull { it.root } ?: return null
        val calls = bars.filter { !it.root }.sortedBy { it.startMs }
        if (calls.isEmpty()) return null
        var covered = 0L
        var end = 0L
        for (c in calls) {
            val from = maxOf(c.startMs, end)
            val to = minOf(c.endMs, root.durationMs)
            if (to > from) covered += to - from
            end = maxOf(end, c.endMs)
        }
        return (root.durationMs - covered).coerceAtLeast(0)
    }
}

/** Paints the bars of a [Waterfall]: label on the left, a bar on a shared millisecond scale on the right. */
internal class WaterfallPanel : JPanel() {
    private var bars: List<WaterfallBar> = emptyList()

    init {
        isOpaque = true
        border = JBUI.Borders.empty(8, 10)
    }

    fun show(bars: List<WaterfallBar>) {
        this.bars = bars
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(480), HEADER + bars.size * ROW + JBUI.scale(40))

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        g.color = UIUtil.getPanelBackground()
        g.fillRect(0, 0, width, height)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = UIUtil.getLabelFont()
        val fm = g2.fontMetrics

        if (bars.isEmpty()) {
            g2.color = JBColor.GRAY
            g2.drawString("Select an incoming request to see where its time went", JBUI.scale(10), JBUI.scale(24))
            return
        }
        val total = Waterfall.total(bars)
        val left = JBUI.scale(10)
        val labelWidth = minOf(JBUI.scale(260), width / 3)
        val chartX = left + labelWidth + JBUI.scale(8)
        val chartW = (width - chartX - JBUI.scale(70)).coerceAtLeast(JBUI.scale(40))

        // axis: four ticks
        g2.color = JBColor.border()
        for (i in 0..4) {
            val x = chartX + chartW * i / 4
            g2.drawLine(x, HEADER - JBUI.scale(4), x, HEADER + bars.size * ROW)
            val text = "${total * i / 4} ms"
            g2.color = JBColor.GRAY
            g2.drawString(text, (x - fm.stringWidth(text) / 2).coerceAtLeast(chartX - fm.stringWidth(text) / 2), HEADER - JBUI.scale(8))
            g2.color = JBColor.border()
        }

        bars.forEachIndexed { i, bar ->
            val y = HEADER + i * ROW
            val baseline = y + (ROW + fm.ascent - fm.descent) / 2
            g2.color = UIUtil.getLabelForeground()
            val indent = if (bar.root) 0 else JBUI.scale(12)
            val label = clip("${bar.badge}  ${bar.label}", labelWidth - indent, fm)
            g2.drawString(label, left + indent, baseline)

            val x = chartX + (chartW * bar.startMs / total).toInt()
            val w = maxOf(JBUI.scale(2), (chartW * bar.durationMs / total).toInt())
            g2.color = when {
                bar.failed -> FAILED
                bar.root -> ROOT
                else -> CALL
            }
            g2.fillRoundRect(x, y + JBUI.scale(4), w, ROW - JBUI.scale(8), JBUI.scale(4), JBUI.scale(4))
            g2.color = JBColor.GRAY
            g2.drawString("${bar.durationMs} ms", x + w + JBUI.scale(6), baseline)
        }

        Waterfall.selfTime(bars)?.let {
            g2.color = JBColor.GRAY
            g2.drawString("In the application itself: $it ms", left, HEADER + bars.size * ROW + JBUI.scale(24))
        }
    }

    private fun clip(text: String, max: Int, fm: java.awt.FontMetrics): String {
        if (fm.stringWidth(text) <= max) return text
        var end = text.length
        while (end > 1 && fm.stringWidth(text.substring(0, end) + "…") > max) end--
        return text.substring(0, end) + "…"
    }

    private companion object {
        val HEADER = JBUI.scale(30)
        val ROW = JBUI.scale(26)
        val ROOT = JBColor(0x5B8DEF, 0x5B8DEF)
        val CALL = JBColor(0x8E6FD8, 0x9B7FE0)
        val FAILED = JBColor(0xD64545, 0xE06C6C)
    }
}
