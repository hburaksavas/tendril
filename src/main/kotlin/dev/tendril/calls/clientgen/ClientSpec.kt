package dev.tendril.calls.clientgen

/**
 * A type as the generated code writes it. [Kind.DTO] is a class copied from the backend ([name] is its simple name in
 * the DTO package); [Kind.CLASS] is a library or JDK class kept as it is ([name] qualified).
 */
data class TypeRef(val kind: Kind, val name: String, val args: List<TypeRef> = emptyList()) {
    enum class Kind { PRIMITIVE, CLASS, DTO, TYPE_PARAMETER, ARRAY, WILDCARD }

    val isPrimitive get() = kind == Kind.PRIMITIVE

    companion object {
        fun primitive(name: String) = TypeRef(Kind.PRIMITIVE, name)
        fun cls(name: String, vararg args: TypeRef) = TypeRef(Kind.CLASS, name, args.toList())
        fun dto(name: String, vararg args: TypeRef) = TypeRef(Kind.DTO, name, args.toList())
    }
}

/** One parameter of the client method, in the role it has for the endpoint. */
data class ParamSpec(val role: Role, val name: String, val httpName: String, val type: TypeRef, val required: Boolean = true) {
    enum class Role { PATH, QUERY, HEADER, BODY, QUERY_MAP, OTHER }
}

/** The endpoint the client method calls. */
data class EndpointSpec(
    val httpMethod: String,
    /** The endpoint's path below the application's context path. */
    val path: String,
    val contextPath: String,
    val methodName: String,
    val params: List<ParamSpec>,
    /** Null: the method returns nothing. */
    val returns: TypeRef?,
    /** The backend application: `spring.application.name` and port. */
    val service: String,
    val port: String,
    /** What could not be carried over, for the summary. */
    val notes: List<String>,
)

/** A class of the backend copied as a DTO: a record (or class) with its JSON fields, or an enum. */
data class DtoSpec(
    val name: String,
    val sourceName: String,
    val typeParameters: List<String>,
    val fields: List<Field>,
    val constants: List<String>? = null,
) {
    data class Field(val name: String, val jsonName: String?, val type: TypeRef)

    val isEnum get() = constants != null
}

/** Java or Kotlin text of the client method, a new client interface and the DTOs. */
object ClientText {
    private val KOTLIN_NAMES = mapOf(
        "java.lang.String" to "String", "java.lang.Integer" to "Int", "java.lang.Long" to "Long", "java.lang.Boolean" to "Boolean",
        "java.lang.Double" to "Double", "java.lang.Float" to "Float", "java.lang.Short" to "Short", "java.lang.Byte" to "Byte",
        "java.lang.Character" to "Char", "java.lang.Object" to "Any", "java.util.List" to "List", "java.util.Map" to "Map",
        "java.util.Set" to "Set", "java.util.Collection" to "Collection", "java.lang.Iterable" to "Iterable",
        "java.lang.Void" to "Unit",
    )
    private val KOTLIN_PRIMITIVES = mapOf(
        "int" to "Int", "long" to "Long", "boolean" to "Boolean", "double" to "Double", "float" to "Float",
        "short" to "Short", "byte" to "Byte", "char" to "Char",
    )
    private val BOXED = mapOf(
        "int" to "Integer", "long" to "Long", "boolean" to "Boolean", "double" to "Double", "float" to "Float",
        "short" to "Short", "byte" to "Byte", "char" to "Character",
    )
    private const val WEB = "org.springframework.web.bind.annotation."

    /** Where generated code lives: the file's package, and the package the DTOs go to. */
    class Context(val kotlin: Boolean, val filePackage: String, val dtoPackage: String, val imports: MutableSet<String> = sortedSetOf())

    fun type(t: TypeRef, c: Context, nullable: Boolean = false): String {
        val text = when (t.kind) {
            TypeRef.Kind.PRIMITIVE -> if (c.kotlin) KOTLIN_PRIMITIVES[t.name] ?: "Any" else if (nullable) BOXED[t.name] ?: t.name else t.name
            TypeRef.Kind.TYPE_PARAMETER -> t.name
            TypeRef.Kind.WILDCARD -> t.args.firstOrNull()?.let { (if (c.kotlin) "out " else "? extends ") + type(it, c) } ?: if (c.kotlin) "*" else "?"
            TypeRef.Kind.ARRAY -> {
                val component = t.args.single()
                if (c.kotlin) {
                    if (component.isPrimitive) "${KOTLIN_PRIMITIVES[component.name]}Array" else "Array<${type(component, c)}>"
                } else "${type(component, c)}[]"
            }
            TypeRef.Kind.DTO -> {
                if (c.dtoPackage != c.filePackage && c.dtoPackage.isNotEmpty()) c.imports += "${c.dtoPackage}.${t.name}"
                t.name + args(t, c)
            }
            TypeRef.Kind.CLASS -> {
                val simple = if (c.kotlin && t.name in KOTLIN_NAMES) KOTLIN_NAMES.getValue(t.name) else {
                    val pkg = t.name.substringBeforeLast('.', "")
                    if (pkg.isNotEmpty() && pkg != "java.lang" && pkg != c.filePackage) c.imports += t.name
                    t.name.substringAfterLast('.')
                }
                simple + args(t, c)
            }
        }
        return if (c.kotlin && nullable) "$text?" else text
    }

    private fun args(t: TypeRef, c: Context) =
        if (t.args.isEmpty()) "" else t.args.joinToString(", ", "<", ">") { type(it, c) }

    private fun mapping(e: EndpointSpec, path: String, c: Context): String {
        val p = quote(path, c)
        return when (val m = e.httpMethod.uppercase()) {
            "GET", "POST", "PUT", "PATCH", "DELETE" -> {
                val name = m.lowercase().replaceFirstChar { it.uppercase() } + "Mapping"
                c.imports += WEB + name
                "@$name($p)"
            }
            "ANY" -> "@RequestMapping($p)".also { c.imports += WEB + "RequestMapping" }
            else -> {
                c.imports += WEB + "RequestMapping"
                c.imports += WEB + "RequestMethod"
                "@RequestMapping(method = ${if (c.kotlin) "[RequestMethod.$m]" else "RequestMethod.$m"}, value = ${if (c.kotlin) "[$p]" else p})"
            }
        }
    }

    private fun quote(s: String, c: Context): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"" + (if (c.kotlin) escaped.replace("$", "\\$") else escaped) + "\""
    }

    private fun annotationOf(p: ParamSpec, c: Context): String? {
        fun use(name: String) = name.also { c.imports += WEB + it }
        val q = quote(p.httpName, c)
        return when (p.role) {
            ParamSpec.Role.PATH -> "@${use("PathVariable")}($q)"
            ParamSpec.Role.QUERY -> if (p.required) "@${use("RequestParam")}($q)" else "@${use("RequestParam")}(value = $q, required = false)"
            ParamSpec.Role.HEADER -> if (p.required) "@${use("RequestHeader")}($q)" else "@${use("RequestHeader")}(value = $q, required = false)"
            ParamSpec.Role.BODY -> "@${use("RequestBody")}"
            ParamSpec.Role.QUERY_MAP -> "@SpringQueryMap".also { c.imports += "org.springframework.cloud.openfeign.SpringQueryMap" }
            ParamSpec.Role.OTHER -> null
        }
    }

    /** The client method; [path] is below the client's own `path`. Indented for an interface body. */
    fun method(e: EndpointSpec, name: String, path: String, c: Context): String {
        val params = e.params.joinToString(", ") { p ->
            val annotation = annotationOf(p, c)?.let { "$it " }.orEmpty()
            val optional = !p.required && p.role != ParamSpec.Role.BODY
            if (c.kotlin) "$annotation${p.name}: ${type(p.type, c, nullable = optional)}"
            else "$annotation${type(p.type, c, nullable = optional)} ${p.name}"
        }
        val mapping = mapping(e, path, c)
        return if (c.kotlin) {
            val returns = e.returns?.let { ": " + type(it, c) }.orEmpty()
            "    $mapping\n    fun $name($params)$returns\n"
        } else {
            val returns = e.returns?.let { type(it, c) } ?: "void"
            "    $mapping\n    $returns $name($params);\n"
        }
    }

    /** A new `@FeignClient` interface holding [method]. */
    fun client(name: String, service: String, url: String?, clientPath: String, method: String, c: Context): String {
        c.imports += "org.springframework.cloud.openfeign.FeignClient"
        val attrs = buildList {
            add("name = ${quote(service, c)}")
            url?.let { add("url = ${quote(it, c)}") }
            if (clientPath.isNotEmpty() && clientPath != "/") add("path = ${quote(clientPath, c)}")
        }.joinToString(", ")
        val head = header(c)
        return if (c.kotlin) "$head@FeignClient($attrs)\ninterface $name {\n$method}\n"
        else "$head@FeignClient($attrs)\npublic interface $name {\n$method}\n"
    }

    /** A DTO file; Java DTOs are records when [records], else classes with public fields. */
    fun dto(d: DtoSpec, records: Boolean, c: Context): String {
        val typeParams = if (d.typeParameters.isEmpty()) "" else d.typeParameters.joinToString(", ", "<", ">")
        val source = "/** Copied by Tendril from ${d.sourceName}. */\n"
        val body = when {
            d.isEnum && c.kotlin -> "enum class ${d.name} {\n    ${d.constants!!.joinToString(",\n    ")},\n}\n"
            d.isEnum -> "public enum ${d.name} {\n    ${d.constants!!.joinToString(",\n    ")}\n}\n"
            c.kotlin -> {
                val fields = d.fields.joinToString("") { f -> "    ${json(f, c)}val ${f.name}: ${type(f.type, c, nullable = true)} = null,\n" }
                if (fields.isEmpty()) "class ${d.name}$typeParams\n" else "data class ${d.name}$typeParams(\n$fields)\n"
            }
            records -> {
                val fields = d.fields.joinToString(",\n") { f -> "    ${json(f, c)}${type(f.type, c)} ${f.name}" }
                "public record ${d.name}$typeParams(${if (fields.isEmpty()) "" else "\n$fields\n"}) {\n}\n"
            }
            else -> {
                val fields = d.fields.joinToString("") { f -> "    ${json(f, c)}public ${type(f.type, c)} ${f.name};\n" }
                "public class ${d.name}$typeParams {\n$fields}\n"
            }
        }
        return header(c) + source + body
    }

    private fun json(f: DtoSpec.Field, c: Context): String {
        val name = f.jsonName ?: return ""
        c.imports += "com.fasterxml.jackson.annotation.JsonProperty"
        return "@JsonProperty(${quote(name, c)}) "
    }

    /** Package and imports; call after the body is rendered, so that [Context.imports] is complete. */
    private fun header(c: Context): String = buildString {
        if (c.filePackage.isNotEmpty()) append("package ${c.filePackage}${if (c.kotlin) "" else ";"}\n\n")
        val imports = c.imports.filter { it.substringBeforeLast('.') != c.filePackage }
        imports.forEach { append("import $it${if (c.kotlin) "" else ";"}\n") }
        if (imports.isNotEmpty()) append("\n")
    }
}
