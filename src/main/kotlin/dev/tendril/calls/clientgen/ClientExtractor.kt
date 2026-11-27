package dev.tendril.calls.clientgen

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiWildcardType
import dev.tendril.config.firstNonBlank
import dev.tendril.endpoints.EndpointScanner
import dev.tendril.feign.FeignResolver

/** What a client needs to know about an endpoint of the backend, and the backend classes it uses, copied as DTOs. */
class ClientExtractor(private val project: Project) {
    private val fileIndex = ProjectFileIndex.getInstance(project)
    private val dtos = LinkedHashMap<String, DtoSpec>()
    private val names = HashMap<String, String>()
    private val notes = mutableListOf<String>()

    /** The DTOs the endpoint needs, in the order met. */
    val dtoSpecs: List<DtoSpec> get() = dtos.values.toList()

    /** Needs a read action; null when [handler] is not a mapped controller method. */
    fun endpoint(handler: PsiMethod): EndpointSpec? {
        val target = handler.navigationElement
        val endpoint = EndpointScanner.cached(project).firstOrNull { it.method.element?.navigationElement == target } ?: return null
        fun app(key: String) = FeignResolver.appValue(project, key, handler)?.trim()?.takeIf { it.isNotEmpty() }
        val params = handler.parameterList.parameters.mapNotNull(::param)
        return EndpointSpec(
            httpMethod = endpoint.httpMethod,
            path = endpoint.path,
            contextPath = app("server.servlet.context-path")?.trimEnd('/').orEmpty(),
            methodName = handler.name,
            params = params,
            returns = returns(handler.returnType),
            service = app("spring.application.name") ?: project.name,
            port = app("server.port")?.takeIf { it.all(Char::isDigit) } ?: "8080",
            notes = notes.toList(),
        )
    }

    private fun param(p: PsiParameter): ParamSpec? {
        fun ann(name: String): PsiAnnotation? = p.getAnnotation(WEB + name)
        fun httpName(a: PsiAnnotation) = a.firstNonBlank("value", "name") ?: p.name
        fun required(a: PsiAnnotation) = a.findDeclaredAttributeValue("required")?.text != "false" &&
            a.findDeclaredAttributeValue("defaultValue") == null
        val type = qualifiedName(p.type)
        ann("PathVariable")?.let { return ParamSpec(ParamSpec.Role.PATH, p.name, httpName(it), ref(p.type)) }
        ann("RequestParam")?.let {
            if (type == MULTIPART) return null.also { notes += "${p.name}: a multipart file needs a form encoder in Feign, left out" }
            return ParamSpec(ParamSpec.Role.QUERY, p.name, httpName(it), ref(p.type), required(it))
        }
        ann("RequestHeader")?.let { return ParamSpec(ParamSpec.Role.HEADER, p.name, httpName(it), ref(p.type), required(it)) }
        ann("RequestBody")?.let { return ParamSpec(ParamSpec.Role.BODY, p.name, p.name, ref(p.type), true) }
        ann("RequestPart")?.let { return null.also { notes += "${p.name}: a multipart part needs a form encoder in Feign, left out" } }
        if (p.annotations.any { it.qualifiedName in SERVER_SIDE_ANNOTATIONS }) return null
        if (type != null && (type in SERVER_SIDE || type.startsWith("jakarta.servlet.") || type.startsWith("javax.servlet."))) return null
        if (type == "org.springframework.data.domain.Pageable" || type == "org.springframework.data.domain.Sort") {
            return ParamSpec(ParamSpec.Role.OTHER, p.name, p.name, ref(p.type)) // Spring Cloud OpenFeign writes them as page/size/sort
        }
        // without an annotation, Spring binds a simple value from a request parameter and an object from all of them
        return if (isSimple(p.type)) ParamSpec(ParamSpec.Role.QUERY, p.name, p.name, ref(p.type), false)
        else ParamSpec(ParamSpec.Role.QUERY_MAP, p.name, p.name, ref(p.type))
    }

    /** What the client receives: wrappers of the controller (`ResponseEntity`, `Mono`, futures) are not on the wire. */
    private fun returns(type: PsiType?): TypeRef? {
        if (type == null || type == PsiTypes.voidType()) return null
        val name = qualifiedName(type)
        val inner = (type as? PsiClassType)?.parameters?.firstOrNull()
        return when {
            name == "java.lang.Void" || name == "kotlin.Unit" -> null
            name in UNWRAPPED -> inner?.let(::returns)
            name == "reactor.core.publisher.Flux" -> inner?.let { TypeRef.cls("java.util.List", ref(it)) }
            name in BINARY -> TypeRef(TypeRef.Kind.ARRAY, "", listOf(TypeRef.primitive("byte")))
            else -> ref(type)
        }
    }

    /** [type] for the client, copying the backend's own classes as DTOs. */
    fun ref(type: PsiType): TypeRef = when (type) {
        is PsiPrimitiveType -> TypeRef.primitive(type.canonicalText)
        is PsiArrayType -> TypeRef(TypeRef.Kind.ARRAY, "", listOf(ref(type.componentType)))
        is PsiWildcardType -> TypeRef(TypeRef.Kind.WILDCARD, "", listOfNotNull(type.extendsBound.takeIf { type.isExtends }?.let(::ref)))
        is PsiClassType -> {
            val cls = type.resolve()
            val args = type.parameters.map(::ref)
            when {
                cls is PsiTypeParameter -> TypeRef(TypeRef.Kind.TYPE_PARAMETER, cls.name ?: "T")
                cls == null -> TypeRef.cls("java.lang.Object")
                isOwn(cls) -> TypeRef(TypeRef.Kind.DTO, copy(cls), args)
                else -> TypeRef(TypeRef.Kind.CLASS, cls.qualifiedName ?: "java.lang.Object", args)
            }
        }
        else -> TypeRef.cls("java.lang.Object")
    }

    private fun isOwn(cls: PsiClass): Boolean {
        val vf = (cls.navigationElement as? PsiClass ?: cls).containingFile?.virtualFile ?: return false
        return fileIndex.isInSourceContent(vf) && !fileIndex.isInTestSourceContent(vf)
    }

    /** Copies [cls] as a DTO (once) and gives its name; two classes with one simple name get distinct names. */
    private fun copy(cls: PsiClass): String {
        val qualified = cls.qualifiedName ?: cls.name ?: "Dto"
        names[qualified]?.let { return it }
        var name = cls.name ?: "Dto"
        var n = 2
        while (names.containsValue(name)) name = "${cls.name}${n++}"
        names[qualified] = name
        if (dtos.size >= MAX_DTOS) {
            notes += "More than $MAX_DTOS classes are reachable: $name and further ones are not copied"
            return name
        }
        dtos[qualified] = DtoSpec(name, qualified, emptyList(), emptyList()) // reserved: a cycle refers to it by name
        dtos[qualified] = if (cls.isEnum) {
            DtoSpec(name, qualified, emptyList(), emptyList(), cls.fields.filterIsInstance<PsiEnumConstant>().map { it.name })
        } else {
            DtoSpec(name, qualified, cls.typeParameters.mapNotNull { it.name }, fieldsOf(cls))
        }
        return name
    }

    private fun fieldsOf(cls: PsiClass): List<DtoSpec.Field> {
        if (cls.isRecord) return cls.recordComponents.filter { !it.hasAnnotation(JSON_IGNORE) }
            .map { DtoSpec.Field(it.name, jsonName(it.getAnnotation(JSON_PROPERTY), it.name), ref(it.type)) }
        // fields of the backend's own superclasses too, as Jackson writes them
        val chain = generateSequence(cls) { it.superClass }.takeWhile { isOwn(it) }.toList().asReversed()
        return chain.flatMap { c ->
            c.fields.filter {
                it !is PsiEnumConstant && !it.hasModifierProperty(PsiModifier.STATIC) &&
                    !it.hasModifierProperty(PsiModifier.TRANSIENT) && !it.hasAnnotation(JSON_IGNORE)
            }.map { DtoSpec.Field(it.name, jsonName(it.getAnnotation(JSON_PROPERTY), it.name), ref(it.type)) }
        }.distinctBy { it.name }
    }

    private fun jsonName(property: PsiAnnotation?, name: String): String? =
        property?.firstNonBlank("value")?.takeIf { it != name }

    private fun isSimple(type: PsiType): Boolean {
        if (type is PsiPrimitiveType) return true
        if (type is PsiArrayType) return isSimple(type.componentType)
        val cls = (type as? PsiClassType)?.resolve() ?: return false
        val name = cls.qualifiedName ?: return false
        if (cls.isEnum) return true
        if (name.startsWith("java.util.") && name != "java.util.Map") return type.parameters.all(::isSimple)
        return name.startsWith("java.lang.") || name.startsWith("java.time.") || name.startsWith("java.math.") ||
            name == "java.util.UUID" || name == "java.util.Date" || name == "java.net.URI"
    }

    private fun qualifiedName(type: PsiType): String? = (type as? PsiClassType)?.resolve()?.qualifiedName

    private companion object {
        const val WEB = "org.springframework.web.bind.annotation."
        const val MULTIPART = "org.springframework.web.multipart.MultipartFile"
        const val JSON_IGNORE = "com.fasterxml.jackson.annotation.JsonIgnore"
        const val JSON_PROPERTY = "com.fasterxml.jackson.annotation.JsonProperty"
        const val MAX_DTOS = 40
        val UNWRAPPED = setOf(
            "org.springframework.http.ResponseEntity", "org.springframework.http.HttpEntity", "java.util.Optional",
            "reactor.core.publisher.Mono", "java.util.concurrent.CompletableFuture", "java.util.concurrent.CompletionStage",
            "java.util.concurrent.Callable", "org.springframework.web.context.request.async.DeferredResult",
            "org.springframework.web.context.request.async.WebAsyncTask",
        )
        val BINARY = setOf(
            "org.springframework.core.io.Resource", "org.springframework.core.io.InputStreamResource",
            "org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody",
        )
        val SERVER_SIDE = setOf(
            "java.security.Principal", "org.springframework.security.core.Authentication", "org.springframework.ui.Model",
            "org.springframework.ui.ModelMap", "org.springframework.validation.BindingResult", "org.springframework.validation.Errors",
            "java.util.Locale", "org.springframework.web.context.request.WebRequest", "org.springframework.web.util.UriComponentsBuilder",
            "org.springframework.http.HttpHeaders", "org.springframework.web.server.ServerWebExchange", "kotlin.coroutines.Continuation",
            "org.springframework.web.bind.support.SessionStatus", "java.util.TimeZone", "java.time.ZoneId",
        )
        val SERVER_SIDE_ANNOTATIONS = setOf(
            "org.springframework.security.core.annotation.AuthenticationPrincipal", WEB + "CookieValue", WEB + "SessionAttribute",
            WEB + "RequestAttribute", WEB + "MatrixVariable",
        )
    }
}
