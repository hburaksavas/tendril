package dev.tendril.logs

/**
 * The logger column of Spring Boot's default console pattern, and how Logback shortens it.
 *
 * `2026-11-28T13:43:32.123+03:00  INFO 1234 --- [shop] [main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started`
 *
 * The pattern is `%-40.40logger{39}`: leading package segments become their first letter until the name fits in 39
 * characters, and a name that still does not fit loses characters on the left.
 */
object LoggerNames {
    private val COLUMN = Regex("\\]\\s+([\\w$]+(?:\\.[\\w$]+)+)\\s+:\\s")

    /** Where the logger name is in a console line, or null when the line is not in Spring Boot's format. */
    fun find(line: String): IntRange? {
        val marker = line.indexOf(" --- ")
        if (marker < 0) return null
        return COLUMN.find(line, marker)?.groups?.get(1)?.range
    }

    /** The class name a logger name ends with, without CGLIB suffixes: `OrderService`, `Inner` of `Outer$Inner`. */
    fun simpleName(logger: String): String = segments(logger).last()

    /** True when no segment was shortened, so [logger] is the full name Spring knows the logger by. */
    fun isComplete(logger: String): Boolean = segments(logger).dropLast(1).all { it.length > 1 }

    /**
     * True when [qualifiedName] (dots also between nested classes) is a name that Logback could shorten to [logger]:
     * each segment equal or shortened to its start, the first one possibly cut on the left.
     */
    fun matches(logger: String, qualifiedName: String): Boolean {
        val short = segments(logger)
        val full = qualifiedName.split('.')
        if (short.size > full.size) return false
        val aligned = full.takeLast(short.size)
        if (short.size < full.size && !aligned[0].endsWith(short[0])) return false
        return short.indices.all { i ->
            val s = short[i]
            val f = aligned[i]
            s == f || (i < short.lastIndex && f.startsWith(s)) || (i == 0 && f.endsWith(s))
        }
    }

    private fun segments(logger: String) = logger.substringBefore("$$").replace('$', '.').split('.').filter { it.isNotEmpty() }
}
