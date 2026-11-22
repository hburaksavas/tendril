package dev.tendril.recordings

import com.google.gson.JsonParser
import junit.framework.TestCase

class WaterfallTest : TestCase() {

    private fun recording(json: String) = Recording.fromJson(JsonParser.parseString(json).asJsonObject)!!

    private val root = recording(
        """{"kind":"http_in","time":1000,"durationMs":300,"flow":7,"method":"POST","url":"http://localhost/orders","handler":"OrderController.create","status":201}""",
    )

    private fun call(time: Long, ms: Long, flow: Long = 7, extra: String = "") = recording(
        """{"kind":"http_out","time":$time,"durationMs":$ms,"flow":$flow,"method":"GET","url":"http://stock/items","client":"StockClient#items()"$extra}""",
    )

    fun `test the request comes first and calls follow in start order`() {
        val bars = Waterfall.build(root, listOf(call(1200, 50), call(1050, 100)))
        assertEquals(listOf(true, false, false), bars.map { it.root })
        assertEquals(listOf(0L, 50L, 200L), bars.map { it.startMs })
        assertEquals("OrderController.create", bars[0].label)
        assertEquals("StockClient#items()", bars[1].label)
    }

    fun `test calls of another flow are left out`() {
        val bars = Waterfall.build(root, listOf(call(1050, 100, flow = 8)))
        assertEquals(1, bars.size)
    }

    fun `test a call that started before the request is placed at zero`() {
        assertEquals(0L, Waterfall.build(root, listOf(call(900, 10)))[1].startMs)
    }

    fun `test failed calls and server errors are marked`() {
        val failing = call(1010, 20, extra = ""","error":"IOException: refused"""")
        val bars = Waterfall.build(root, listOf(failing, call(1100, 20, extra = ""","status":503""")))
        assertTrue(bars[1].failed)
        assertTrue(bars[2].failed)
        assertFalse(bars[0].failed)
    }

    fun `test the axis is as long as the longest bar`() {
        val bars = Waterfall.build(root, listOf(call(1250, 200)))
        assertEquals(450L, Waterfall.total(bars))
    }

    fun `test self time leaves out the calls and counts overlaps once`() {
        val bars = Waterfall.build(root, listOf(call(1010, 100), call(1050, 100)))
        // calls cover 10..150 = 140 ms of the 300
        assertEquals(160L, Waterfall.selfTime(bars))
    }

    fun `test there is no self time without calls`() {
        assertNull(Waterfall.selfTime(Waterfall.build(root, emptyList())))
    }
}
