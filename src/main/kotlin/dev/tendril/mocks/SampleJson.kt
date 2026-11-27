package dev.tendril.mocks

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.psi.CommonClassNames
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.TypeConversionUtil

/**
 * A sample JSON value for a Java type, to start a mock from: the fields of a DTO, record or Kotlin data class with
 * placeholder values, one element for a collection. Wrappers (`ResponseEntity`, `Optional`, `Mono`, futures) are
 * looked through. Needs a read action.
 */
object SampleJson {
    private const val MAX_DEPTH = 5

    private val WRAPPERS = setOf(
        "org.springframework.http.ResponseEntity", "org.springframework.http.HttpEntity", "java.util.Optional",
        "reactor.core.publisher.Mono", "java.util.concurrent.CompletableFuture", "java.util.concurrent.CompletionStage",
        "java.util.concurrent.Future", "java.util.concurrent.Callable", "org.springframework.web.context.request.async.DeferredResult",
        "org.springframework.web.context.request.async.WebAsyncTask",
    )
    private val MANY = setOf("reactor.core.publisher.Flux")
    private val NUMBERS = setOf(
        "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.math.BigInteger",
        "java.util.concurrent.atomic.AtomicInteger", "java.util.concurrent.atomic.AtomicLong",
    )
    private val DECIMALS = setOf("java.lang.Double", "java.lang.Float", "java.math.BigDecimal")
    private val TEXT = mapOf(
        CommonClassNames.JAVA_LANG_STRING to "string", "java.lang.CharSequence" to "string", "java.lang.Character" to "a",
        "java.util.UUID" to "3fa85f64-5717-4562-b3fc-2c963f66afa6", "java.time.LocalDate" to "2026-01-01",
        "java.time.LocalDateTime" to "2026-01-01T12:00:00", "java.time.LocalTime" to "12:00:00",
        "java.time.Instant" to "2026-01-01T12:00:00Z", "java.time.OffsetDateTime" to "2026-01-01T12:00:00Z",
        "java.time.ZonedDateTime" to "2026-01-01T12:00:00Z", "java.util.Date" to "2026-01-01T12:00:00Z",
        "java.time.Duration" to "PT1M", "java.net.URI" to "https://example.com", "java.net.URL" to "https://example.com",
        "java.util.Locale" to "en", "java.util.Currency" to "EUR", "java.time.YearMonth" to "2026-01",
    )
    private val NOTHING = setOf("java.lang.Void", "kotlin.Unit")
    private val ANY = setOf(CommonClassNames.JAVA_LANG_OBJECT, "com.fasterxml.jackson.databind.JsonNode", "com.fasterxml.jackson.databind.node.ObjectNode")
    private val IGNORE = "com.fasterxml.jackson.annotation.JsonIgnore"
    private val PROPERTY = "com.fasterxml.jackson.annotation.JsonProperty"

    /** The sample for a method's return type; null when it returns nothing. */
    fun of(type: PsiType?): JsonElement? {
        if (type == null || type == PsiTypes.voidType()) return null
        val value = value(type, 0, HashSet())
        return value.takeUnless { it.isJsonNull }
    }

    private fun value(type: PsiType, depth: Int, seen: MutableSet<String>): JsonElement {
        val t = if (type is PsiWildcardType) type.extendsBound else type
        when (t) {
            is PsiPrimitiveType -> return primitive(t)
            is PsiArrayType -> {
                if (t.componentType == PsiTypes.byteType()) return JsonPrimitive("")
                return JsonArray().apply { add(value(t.componentType, depth + 1, seen)) }
            }
            !is PsiClassType -> return JsonObject()
        }
        val resolved = t.resolveGenerics()
        val cls = resolved.element ?: return JsonObject()
        val name = cls.qualifiedName ?: return JsonObject()
        val args = t.parameters
        when {
            name in NOTHING -> return JsonNull.INSTANCE
            name in WRAPPERS -> return args.firstOrNull()?.let { value(it, depth, seen) } ?: JsonObject()
            name in MANY -> return JsonArray().apply { args.firstOrNull()?.let { add(value(it, depth + 1, seen)) } }
            name == CommonClassNames.JAVA_LANG_BOOLEAN -> return JsonPrimitive(false)
            name in NUMBERS -> return JsonPrimitive(0)
            name in DECIMALS -> return JsonPrimitive(0.0)
            name in TEXT -> return JsonPrimitive(TEXT.getValue(name))
            name in ANY -> return JsonObject()
            cls.isEnum -> return JsonPrimitive(cls.fields.firstOrNull { it is PsiEnumConstant }?.name ?: "VALUE")
            InheritanceUtil.isInheritor(cls, CommonClassNames.JAVA_UTIL_MAP) -> return JsonObject().apply {
                val v = PsiUtil.substituteTypeParameter(t, CommonClassNames.JAVA_UTIL_MAP, 1, false)
                add("key", v?.let { value(it, depth + 1, seen) } ?: JsonPrimitive("value"))
            }
            InheritanceUtil.isInheritor(cls, CommonClassNames.JAVA_LANG_ITERABLE) -> return JsonArray().apply {
                val e = PsiUtil.substituteTypeParameter(t, CommonClassNames.JAVA_LANG_ITERABLE, 0, false)
                e?.let { add(value(it, depth + 1, seen)) }
            }
        }
        if (depth >= MAX_DEPTH || !seen.add(name)) return JsonObject()
        try {
            return objectOf(cls, resolved.substitutor, depth, seen)
        } finally {
            seen.remove(name)
        }
    }

    private fun objectOf(cls: PsiClass, substitutor: PsiSubstitutor, depth: Int, seen: MutableSet<String>): JsonObject {
        val out = JsonObject()
        if (cls.isRecord) {
            for (c in cls.recordComponents) {
                if (c.hasAnnotation(IGNORE)) continue
                out.add(jsonName(c.name, c.getAnnotation(PROPERTY)), value(substitutor.substitute(c.type), depth + 1, seen))
            }
            return out
        }
        // superclass fields first, as Jackson writes them
        val chain = generateSequence(cls) { it.superClass }.takeWhile { it.qualifiedName?.startsWith("java.") != true }.toList().asReversed()
        for (c in chain) {
            val classSubstitutor = if (c == cls) substitutor else TypeConversionUtil.getSuperClassSubstitutor(c, cls, substitutor)
            for (f in c.fields) {
                if (!included(f)) continue
                out.add(jsonName(f.name, f.getAnnotation(PROPERTY)), value(classSubstitutor.substitute(f.type), depth + 1, seen))
            }
        }
        return out
    }

    private fun included(f: PsiField): Boolean =
        f !is PsiEnumConstant && !f.hasModifierProperty(PsiModifier.STATIC) && !f.hasModifierProperty(PsiModifier.TRANSIENT) &&
            !f.hasAnnotation(IGNORE)

    private fun jsonName(name: String, property: com.intellij.psi.PsiAnnotation?): String {
        val value = property?.findDeclaredAttributeValue("value")?.let {
            com.intellij.psi.impl.JavaConstantExpressionEvaluator.computeConstantExpression(it as? com.intellij.psi.PsiExpression, false) as? String
        }
        return value?.takeIf { it.isNotEmpty() } ?: name
    }

    private fun primitive(t: PsiPrimitiveType): JsonElement = when (t) {
        PsiTypes.booleanType() -> JsonPrimitive(false)
        PsiTypes.doubleType(), PsiTypes.floatType() -> JsonPrimitive(0.0)
        PsiTypes.charType() -> JsonPrimitive("a")
        PsiTypes.voidType() -> JsonNull.INSTANCE
        else -> JsonPrimitive(0)
    }
}
