package dev.tendril.hints

import junit.framework.TestCase
import java.time.LocalDateTime

class CronScheduleTest : TestCase() {

    private fun next(cron: String, from: String) = CronSchedule.parse(cron)?.next(LocalDateTime.parse(from))?.toString()

    fun `test every day at half past two`() {
        assertEquals("2026-03-10T14:30", next("0 30 14 * * *", "2026-03-10T09:00:00"))
        assertEquals("2026-03-11T14:30", next("0 30 14 * * *", "2026-03-10T14:30:00"))
    }

    fun `test steps and ranges`() {
        assertEquals("2026-03-10T09:15", next("0 */15 * * * *", "2026-03-10T09:07:00"))
        assertEquals("2026-03-10T09:00", next("0 0 9-17 * * *", "2026-03-10T08:00:00"))
        assertEquals("2026-03-10T12:00", next("0 0 9-17/3 * * *", "2026-03-10T09:30:00"))
    }

    fun `test seconds`() {
        assertEquals("2026-03-10T09:00:10", next("*/10 * * * * *", "2026-03-10T09:00:03"))
    }

    fun `test weekdays by name and number`() {
        // 2026-03-10 is a Tuesday
        assertEquals("2026-03-16T08:00", next("0 0 8 * * MON-FRI", "2026-03-13T09:00:00"))
        assertEquals("2026-03-15T08:00", next("0 0 8 * * 0", "2026-03-10T09:00:00")) // Sunday as 0
        assertEquals("2026-03-15T08:00", next("0 0 8 * * 7", "2026-03-10T09:00:00")) // and as 7
    }

    fun `test months and days of month`() {
        assertEquals("2026-04-01T00:00", next("0 0 0 1 * *", "2026-03-10T09:00:00"))
        assertEquals("2027-01-01T00:00", next("0 0 0 1 JAN *", "2026-03-10T09:00:00"))
    }

    fun `test macros`() {
        assertEquals("2026-03-11T00:00", next("@daily", "2026-03-10T09:00:00"))
        assertEquals("2026-03-10T10:00", next("@hourly", "2026-03-10T09:30:00"))
    }

    fun `test what is not supported or wrong is not guessed`() {
        assertNull(CronSchedule.parse("0 0 0 L * *"))
        assertNull(CronSchedule.parse("0 0 8 * *"))
        assertNull(CronSchedule.parse("0 61 8 * * *"))
        assertNull(CronSchedule.parse("0 0 8 * * MON#2"))
        assertNull(CronSchedule.parse("not a cron"))
    }

    fun `test an impossible date has no next run`() {
        assertNull(next("0 0 0 30 FEB *", "2026-03-10T09:00:00"))
    }

    fun `test the description names the day only when it is not today`() {
        val now = LocalDateTime.parse("2026-03-10T09:00:00")
        assertEquals("14:30", CronSchedule.describe(LocalDateTime.parse("2026-03-10T14:30:00"), now))
        assertEquals("tomorrow 02:00", CronSchedule.describe(LocalDateTime.parse("2026-03-11T02:00:00"), now))
        assertEquals("Fri 02:00", CronSchedule.describe(LocalDateTime.parse("2026-03-13T02:00:00"), now))
        assertEquals("Fri 20 Mar 02:00", CronSchedule.describe(LocalDateTime.parse("2026-03-20T02:00:00"), now))
        assertEquals("09:00:10", CronSchedule.describe(LocalDateTime.parse("2026-03-10T09:00:10"), now))
    }
}
