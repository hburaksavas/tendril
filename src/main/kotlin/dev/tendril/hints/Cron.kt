package dev.tendril.hints

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * A Spring `@Scheduled(cron = ...)` expression: `second minute hour day-of-month month day-of-week`. Supports `*`, `?`,
 * lists, ranges, steps, month / day names and the `@daily` style macros. `L`, `W` and `#` are not supported (the parse
 * returns null, so no hint is shown rather than a wrong one).
 */
class CronSchedule private constructor(
    private val seconds: Set<Int>,
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val days: Set<Int>,
    private val months: Set<Int>,
    /** 1 = Monday .. 7 = Sunday, as [DayOfWeek]. */
    private val weekdays: Set<Int>,
) {
    /** The first fire time strictly after [after], or null if there is none within the next few years. */
    fun next(after: LocalDateTime): LocalDateTime? {
        var day = after.toLocalDate()
        val last = day.plusYears(8)
        while (!day.isAfter(last)) {
            if (day.monthValue in months && day.dayOfMonth in days && day.dayOfWeek.value in weekdays) {
                for (h in hours) for (m in minutes) for (s in seconds) {
                    val candidate = day.atTime(h, m, s)
                    if (candidate.isAfter(after)) return candidate
                }
            }
            day = day.plusDays(1)
        }
        return null
    }

    companion object {
        private val MACROS = mapOf(
            "@yearly" to "0 0 0 1 1 *", "@annually" to "0 0 0 1 1 *", "@monthly" to "0 0 0 1 * *",
            "@weekly" to "0 0 0 * * 0", "@daily" to "0 0 0 * * *", "@midnight" to "0 0 0 * * *", "@hourly" to "0 0 * * * *",
        )
        private val MONTH_NAMES = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        private val DAY_NAMES = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

        fun parse(expression: String): CronSchedule? {
            val text = MACROS[expression.trim().lowercase()] ?: expression.trim()
            val parts = text.split(Regex("\\s+"))
            if (parts.size != 6) return null
            val sec = field(parts[0], 0, 59) ?: return null
            val min = field(parts[1], 0, 59) ?: return null
            val hour = field(parts[2], 0, 23) ?: return null
            val dom = field(parts[3], 1, 31) ?: return null
            val month = field(parts[4], 1, 12, MONTH_NAMES, 1) ?: return null
            val dow = field(parts[5], 0, 7, DAY_NAMES, 0) ?: return null
            // 0 and 7 are both Sunday; DayOfWeek counts Monday = 1 .. Sunday = 7
            val weekdays = dow.map { if (it == 0) 7 else it }.toSet()
            return CronSchedule(sec.toSortedSet(), min.toSortedSet(), hour.toSortedSet(), dom, month, weekdays)
        }

        private fun field(text: String, min: Int, max: Int, names: List<String> = emptyList(), nameBase: Int = 0): Set<Int>? {
            fun number(s: String): Int? =
                s.toIntOrNull() ?: names.indexOf(s.uppercase()).takeIf { it >= 0 }?.plus(nameBase)

            val out = LinkedHashSet<Int>()
            for (item in text.split(',')) {
                val slash = item.split('/')
                if (slash.size > 2) return null
                val step = if (slash.size == 2) slash[1].toIntOrNull()?.takeIf { it > 0 } ?: return null else 1
                val base = slash[0]
                val range = when {
                    base == "*" || base == "?" -> min to max
                    '-' in base -> {
                        val (a, b) = base.split('-').let { it.getOrNull(0) to it.getOrNull(1) }
                        (number(a ?: return null) ?: return null) to (number(b ?: return null) ?: return null)
                    }
                    else -> {
                        val n = number(base) ?: return null
                        n to if (slash.size == 2) max else n
                    }
                }
                if (range.first < min || range.second > max || range.first > range.second) return null
                var v = range.first
                while (v <= range.second) {
                    out += v
                    v += step
                }
            }
            return out.takeIf { it.isNotEmpty() }
        }

        /** "14:30" for today, "tomorrow 02:00", otherwise "Mon 3 Nov 02:00"; seconds are shown only when they matter. */
        fun describe(next: LocalDateTime, now: LocalDateTime): String {
            val time = if (next.second == 0) "%02d:%02d".format(next.hour, next.minute)
            else "%02d:%02d:%02d".format(next.hour, next.minute, next.second)
            val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), next.toLocalDate())
            return when {
                days == 0L -> time
                days == 1L -> "tomorrow $time"
                days < 7 -> "${next.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} $time"
                else -> "${next.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${next.dayOfMonth} " +
                    "${next.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} $time"
            }
        }

        /** The "next run" text of [expression] in [zone], or null when it cannot be worked out. */
        fun nextRun(expression: String, zone: ZoneId = ZoneId.systemDefault(), now: ZonedDateTime = ZonedDateTime.now(zone)): String? {
            val local = now.withZoneSameInstant(zone).toLocalDateTime()
            val next = parse(expression)?.next(local) ?: return null
            return describe(next, local)
        }
    }
}
