package dev.tendril.messaging

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiUtil
import dev.tendril.endpoints.EndpointScanner
import dev.tendril.feign.FeignResolver
import dev.tendril.mocks.SampleJson
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getUastParentOfType

/** Where a listener receives from and what it expects, read from its annotations. */
data class ListenerTarget(
    /** "kafka" or "rabbit". */
    val transport: String,
    /** `OrderListener.onOrder`. */
    val label: String,
    /** Kafka topics, or Rabbit queues; placeholders resolved. */
    val destinations: List<String>,
    /** Rabbit: the exchange and key of a `@QueueBinding`; null to use the default exchange. */
    val exchange: String?,
    val routingKey: String?,
    /** What could not be read and must be filled in (SpEL, a topic pattern, a placeholder no config defines). */
    val unresolved: List<String>,
    val payloadType: PsiType?,
    /** A batch listener: each element of a JSON array is sent as its own message. */
    val batch: Boolean,
) {
    val isKafka get() = transport == KAFKA

    /** The class name for Kafka's `__TypeId__` header; null for text payloads. */
    val payloadClass: String? get() = (payloadType as? PsiClassType)?.resolve()?.qualifiedName?.takeUnless { it in TEXT_TYPES }

    /** True when the payload is sent as plain text rather than JSON. */
    val isText: Boolean
        get() = payloadType == null || payloadType is PsiArrayType && (payloadType as PsiArrayType).componentType == PsiTypes.byteType() ||
            (payloadType as? PsiClassType)?.resolve()?.qualifiedName in TEXT_TYPES

    companion object {
        const val KAFKA = "kafka"
        const val RABBIT = "rabbit"
        private val TEXT_TYPES = setOf("java.lang.String", "java.lang.Object", "org.springframework.amqp.core.Message")
    }
}

object Listeners {
    private const val KAFKA_LISTENER = "org.springframework.kafka.annotation.KafkaListener"
    private const val KAFKA_HANDLER = "org.springframework.kafka.annotation.KafkaHandler"
    private const val RABBIT_LISTENER = "org.springframework.amqp.rabbit.annotation.RabbitListener"
    private const val RABBIT_HANDLER = "org.springframework.amqp.rabbit.annotation.RabbitHandler"
    private const val PAYLOAD = "org.springframework.messaging.handler.annotation.Payload"
    private val HEADERS = setOf("org.springframework.messaging.handler.annotation.Header", "org.springframework.messaging.handler.annotation.Headers")
    private val NOT_PAYLOAD = setOf(
        "org.springframework.kafka.support.Acknowledgment", "org.apache.kafka.clients.consumer.Consumer",
        "com.rabbitmq.client.Channel", "org.springframework.messaging.MessageHeaders",
        "org.springframework.kafka.listener.adapter.ConsumerRecordMetadata", "kotlin.coroutines.Continuation",
        "org.springframework.amqp.core.MessageProperties",
    )
    private const val MESSAGING_MESSAGE = "org.springframework.messaging.Message"
    private const val CONSUMER_RECORD = "org.apache.kafka.clients.consumer.ConsumerRecord"
    private const val CONSUMER_RECORDS = "org.apache.kafka.clients.consumer.ConsumerRecords"

    /** The listener method around [offset], or null. Needs a read action. */
    fun methodAt(file: PsiFile, offset: Int): PsiMethod? {
        val element = file.findElementAt(offset)?.takeIf { it !is PsiWhiteSpace }
            ?: file.findElementAt(maxOf(0, offset - 1)) ?: return null
        val method = element.getUastParentOfType<UMethod>()?.javaPsi ?: return null
        return method.takeIf { listenerAnnotation(it) != null }
    }

    /** The `@KafkaListener` / `@RabbitListener` that configures [method]: its own, or its class's for a handler. */
    private fun listenerAnnotation(method: PsiMethod): PsiAnnotation? {
        method.getAnnotation(KAFKA_LISTENER)?.let { return it }
        method.getAnnotation(RABBIT_LISTENER)?.let { return it }
        val cls = method.containingClass ?: return null
        if (method.hasAnnotation(KAFKA_HANDLER)) return cls.getAnnotation(KAFKA_LISTENER)
        if (method.hasAnnotation(RABBIT_HANDLER)) return cls.getAnnotation(RABBIT_LISTENER)
        return null
    }

    /** Needs a read action. */
    fun target(project: Project, method: PsiMethod): ListenerTarget? {
        val ann = listenerAnnotation(method) ?: return null
        val kafka = ann.qualifiedName == KAFKA_LISTENER
        val profile = FeignResolver.selectedProfile(project)
        val unresolved = mutableListOf<String>()
        // a placeholder may hold a comma-separated list, as Spring reads it
        fun resolve(values: List<String>): List<String> = values.flatMap { raw ->
            val (value, missing) = FeignResolver.substitute(project, raw, profile)
            when {
                value.contains("#{") -> emptyList<String>().also { unresolved += "the expression $raw" }
                missing.isNotEmpty() -> emptyList<String>().also { unresolved += "the placeholder $raw" }
                else -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
        }

        var exchange: String? = null
        var routingKey: String? = null
        val destinations = if (kafka) {
            EndpointScanner.attrValues(ann, "topicPattern").firstOrNull()?.let { unresolved += "the topic pattern $it" }
            resolve(EndpointScanner.attrValues(ann, "topics"))
        } else {
            val queues = resolve(EndpointScanner.attrValues(ann, "queues")).toMutableList()
            for (q in nested(ann, "queuesToDeclare")) queues += resolve(nameOf(q))
            for (binding in nested(ann, "bindings")) {
                nested(binding, "value").firstOrNull()?.let { queues += resolve(nameOf(it)) }
                if (exchange == null) {
                    exchange = nested(binding, "exchange").firstOrNull()?.let { resolve(nameOf(it)).firstOrNull() }
                    routingKey = resolve(EndpointScanner.attrValues(binding, "key")).firstOrNull()
                }
            }
            queues
        }

        val (payload, batchByType) = payloadOf(method)
        val batch = batchByType || EndpointScanner.attrValues(ann, "batch").firstOrNull()?.trim('"') == "true"
        val element = if (batch && payload != null) elementOf(payload) ?: payload else payload
        return ListenerTarget(
            transport = if (kafka) ListenerTarget.KAFKA else ListenerTarget.RABBIT,
            label = "${method.containingClass?.name}.${method.name}",
            destinations = destinations.distinct(), exchange = exchange, routingKey = routingKey,
            unresolved = unresolved, payloadType = element?.let(::unwrap), batch = batch,
        )
    }

    /** A sample payload to start from: JSON from the type, or text. Needs a read action. */
    fun samplePayload(target: ListenerTarget): String {
        if (target.isText) return if (target.batch) "[\"text\"]" else "text"
        val sample = SampleJson.of(target.payloadType) ?: com.google.gson.JsonObject()
        val value = if (target.batch) com.google.gson.JsonArray().apply { add(sample) } else sample
        return com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(value)
    }

    /** The payload parameter's type, and whether it is a batch by type (`List<ConsumerRecord<..>>`, `ConsumerRecords`). */
    private fun payloadOf(method: PsiMethod): Pair<PsiType?, Boolean> {
        val params = method.parameterList.parameters
        val param: PsiParameter = params.firstOrNull { it.hasAnnotation(PAYLOAD) }
            ?: params.firstOrNull { p -> HEADERS.none { p.hasAnnotation(it) } && qualifiedName(p.type) !in NOT_PAYLOAD }
            ?: return null to false
        val type = param.type
        if (qualifiedName(type) == CONSUMER_RECORDS) return (type as PsiClassType).parameters.getOrNull(1) to true
        val element = elementOf(type)
        val batch = element != null && qualifiedName(element) in setOf(CONSUMER_RECORD, MESSAGING_MESSAGE)
        return type to batch
    }

    /** `Message<T>` and `ConsumerRecord<K, V>` carry the payload inside. */
    private fun unwrap(type: PsiType): PsiType = when (qualifiedName(type)) {
        MESSAGING_MESSAGE -> (type as PsiClassType).parameters.firstOrNull() ?: type
        CONSUMER_RECORD -> (type as PsiClassType).parameters.getOrNull(1) ?: type
        else -> type
    }

    private fun elementOf(type: PsiType): PsiType? =
        if (type is PsiArrayType && type.componentType != PsiTypes.byteType()) type.componentType
        else PsiUtil.substituteTypeParameter(type, "java.util.Collection", 0, false)

    private fun qualifiedName(type: PsiType): String? = (type as? PsiClassType)?.resolve()?.qualifiedName

    private fun nameOf(ann: PsiAnnotation): List<String> =
        EndpointScanner.attrValues(ann, "value").ifEmpty { EndpointScanner.attrValues(ann, "name") }

    private fun nested(ann: PsiAnnotation, name: String): List<PsiAnnotation> = when (val value = ann.findDeclaredAttributeValue(name)) {
        is PsiAnnotation -> listOf(value)
        is PsiArrayInitializerMemberValue -> value.initializers.filterIsInstance<PsiAnnotation>()
        else -> emptyList()
    }
}
