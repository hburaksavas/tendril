package dev.tendril.startup

/**
 * What a piece of Spring Boot's "APPLICATION FAILED TO START" report (or of the exception that caused it) is about.
 * Produced from one console line by [StartupReport.links]; resolved against the project only when clicked.
 */
sealed interface StartupTarget {
    /** A class named in the report; [missingBean] when the report says no bean of it could be found. */
    data class Type(val fqn: String, val missingBean: Boolean = false) : StartupTarget

    /** The class that asked for a bean, and where in it: a field, a constructor or method parameter. */
    data class InjectionPoint(val fqn: String, val member: Member) : StartupTarget

    /**
     * A bean listed as one of several candidates or as part of a cycle: its name and where Spring says it is
     * defined, as class names to try ([classes], most specific first) and the `@Bean` method if there is one.
     */
    data class BeanDefinition(val name: String, val classes: List<String>, val method: String?) : StartupTarget

    /** A configuration key: one that does not bind, is missing, or is used by an unresolved placeholder. */
    data class ConfigKey(val key: String) : StartupTarget

    /** A position in a configuration file, from a binding error's `Origin:` line. [resource] is a path or file name. */
    data class Origin(val resource: String, val line: Int, val column: Int) : StartupTarget

    /** The web server could not listen on this port. */
    data class Port(val port: Int) : StartupTarget
}

sealed interface Member {
    data object None : Member
    data class Field(val name: String) : Member
    data class ConstructorParameter(val index: Int) : Member
    data class MethodParameter(val method: String, val index: Int) : Member
    data class Method(val name: String) : Member
}

/** A part of a console line ([start] until [end], line-relative) that links to [target]. */
data class ReportLink(val start: Int, val end: Int, val target: StartupTarget)

/**
 * Reads the failure analyzer report line by line, as the console prints it. Only the text a report uses is matched,
 * so ordinary log lines cost a few `contains` checks.
 */
object StartupReport {

    private const val FQN = "[\\w$]+(?:\\.[\\w$]+)+"

    // "Parameter 0 of constructor in app.OrderService required ...", "Field client in app.OrderService required ..."
    private val INJECTION = Regex(
        "(?:Parameter (\\d+) of (?:constructor|method (\\w+))|Field (\\w+)|Method (\\w+)) in ($FQN) required",
    )
    private val BEAN_TYPE = Regex("(?:required a bean of type|Consider defining a bean of type) '($FQN)(?:\\[])*'")

    // "\t- stripe: defined in file [/x/target/classes/app/Stripe.class]"
    // "\t- paypal: defined by method 'paypal' in class path resource [app/PaymentConfig.class]"
    private val CANDIDATE = Regex("^\\s*- ([\\w.$#-]+): (?:defined|a programmatically)")

    // "|  orderService defined in file [...]", "|  paymentService (field private app.OrderService app.PaymentService.orders)"
    private val CYCLE = Regex("^\\s*[|│]\\s+([\\w.$#-]+)(?= defined | \\(|\\s*$)")
    private val CYCLE_FIELD = Regex("\\(field .*? ($FQN)\\.([\\w$]+)\\)")

    private val DEFINED_BY_METHOD = Regex("defined by method '(\\w+)' in (?:class path resource|file|URL) \\[([^]]+)]|defined by method '(\\w+)' in ($FQN)")
    private val DEFINED_IN = Regex("defined in (?:file|class path resource|URL) \\[([^]]+)]|defined in ($FQN)")

    private val BIND_UNDER = Regex("Failed to bind properties under '([^']+)' to ($FQN)")
    private val PROPERTY = Regex("^\\s*Property: (\\S+)")
    private val ORIGIN = Regex("^\\s*Origin: (?:class path resource|URL|file) \\[([^]]+)](?: from \\S+)? - (\\d+):(\\d+)")
    private val PORT = Regex("Port (\\d+) (?:was|is) already in use|listening on port (\\d+)")
    private val PLACEHOLDER = Regex("Could not resolve placeholder '([^']+)' in")
    private val DATASOURCE_URL = Regex("Failed to configure a DataSource: ('url' attribute)")

    private val KEYWORDS = listOf(
        " required", "bean of type", "defined", "programmatically", "(field ", "Failed to bind", "Property:",
        "Origin:", "already in use", "listening on port", "Could not resolve placeholder", "Failed to configure a DataSource",
    )

    fun links(line: String): List<ReportLink> {
        if (KEYWORDS.none { line.contains(it) }) return emptyList()
        val links = ArrayList<ReportLink>()

        INJECTION.find(line)?.let { m ->
            val (param, method, field, setter) = m.destructured
            val member = when {
                field.isNotEmpty() -> Member.Field(field)
                setter.isNotEmpty() -> Member.Method(setter)
                method.isNotEmpty() -> Member.MethodParameter(method, param.toInt())
                param.isNotEmpty() -> Member.ConstructorParameter(param.toInt())
                else -> Member.None
            }
            links += link(m.groups[5]!!, StartupTarget.InjectionPoint(m.groupValues[5], member))
        }
        BEAN_TYPE.find(line)?.let { links += link(it.groups[1]!!, StartupTarget.Type(it.groupValues[1], missingBean = true)) }

        val candidate = CANDIDATE.find(line) ?: CYCLE.find(line)
        if (candidate != null) {
            val name = candidate.groups[1]!!
            CYCLE_FIELD.find(line)?.let {
                // the bean is the class that declares the field; the field is what closes the cycle
                links += link(name, StartupTarget.BeanDefinition(name.value, listOf(it.groupValues[1]), null))
                links += ReportLink(it.range.first, it.range.last + 1, StartupTarget.InjectionPoint(it.groupValues[1], Member.Field(it.groupValues[2])))
            } ?: links.add(link(name, definition(name.value, line)))
        }

        BIND_UNDER.find(line)?.let {
            links += link(it.groups[1]!!, StartupTarget.ConfigKey(it.groupValues[1]))
            links += link(it.groups[2]!!, StartupTarget.Type(it.groupValues[2]))
        }
        PROPERTY.find(line)?.let { links += link(it.groups[1]!!, StartupTarget.ConfigKey(it.groupValues[1])) }
        ORIGIN.find(line)?.let {
            val resource = it.groups[1]!!
            links += ReportLink(resource.range.first, it.range.last + 1, StartupTarget.Origin(resource.value, it.groupValues[2].toInt(), it.groupValues[3].toInt()))
        }
        PORT.find(line)?.let { m ->
            val port = (m.groups[1] ?: m.groups[2])!!
            links += ReportLink(m.range.first, m.range.last + 1, StartupTarget.Port(port.value.toInt()))
        }
        PLACEHOLDER.find(line)?.let { links += link(it.groups[1]!!, StartupTarget.ConfigKey(it.groupValues[1])) }
        DATASOURCE_URL.find(line)?.let { links += link(it.groups[1]!!, StartupTarget.ConfigKey("spring.datasource.url")) }

        return links.sortedBy { it.start }.fold(ArrayList()) { kept, l ->
            if (kept.none { l.start < it.end && it.start < l.end }) kept += l
            kept
        }
    }

    private fun link(group: MatchGroup, target: StartupTarget) = ReportLink(group.range.first, group.range.last + 1, target)

    private fun definition(name: String, line: String): StartupTarget.BeanDefinition {
        DEFINED_BY_METHOD.find(line)?.let { m ->
            val (method, resource, method2, fqn) = m.destructured
            return if (method.isNotEmpty()) StartupTarget.BeanDefinition(name, classNames(resource), method)
            else StartupTarget.BeanDefinition(name, listOf(fqn), method2)
        }
        DEFINED_IN.find(line)?.let { m ->
            val (resource, fqn) = m.destructured
            return StartupTarget.BeanDefinition(name, if (resource.isNotEmpty()) classNames(resource) else listOf(fqn), null)
        }
        return StartupTarget.BeanDefinition(name, emptyList(), null)
    }

    /**
     * Class names a `.class` path may stand for, longest first: `/x/target/classes/app/Stripe.class` gives
     * `x.target.classes.app.Stripe`, ..., `app.Stripe`, `Stripe`. A path that is not a class file gives nothing.
     */
    fun classNames(resource: String): List<String> {
        if (!resource.endsWith(".class")) return emptyList()
        val segments = resource.removeSuffix(".class").split('/', '\\').filter { it.isNotEmpty() && !it.contains(':') }
        // a nested class file is Outer$Inner; the class name in PSI uses a dot
        return segments.indices.map { segments.drop(it).joinToString(".").replace('$', '.') }
    }
}
