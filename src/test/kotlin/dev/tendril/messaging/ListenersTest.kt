package dev.tendril.messaging

import com.google.gson.JsonParser
import dev.tendril.KotlinSpringTestCase

/** Where a listener receives from and what it expects, read from its annotations, and the message sent to it. */
class ListenersTest : KotlinSpringTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass(
            "package org.springframework.kafka.annotation; public @interface KafkaListener { String[] topics() default {}; " +
                "String topicPattern() default \"\"; String batch() default \"\"; String groupId() default \"\"; }",
        )
        myFixture.addClass("package org.springframework.kafka.annotation; public @interface KafkaHandler { }")
        myFixture.addClass("package org.springframework.amqp.rabbit.annotation; public @interface Queue { String value() default \"\"; String name() default \"\"; }")
        myFixture.addClass("package org.springframework.amqp.rabbit.annotation; public @interface Exchange { String value() default \"\"; String name() default \"\"; }")
        myFixture.addClass(
            "package org.springframework.amqp.rabbit.annotation; public @interface QueueBinding { Queue value(); Exchange exchange(); String[] key() default {}; }",
        )
        myFixture.addClass(
            "package org.springframework.amqp.rabbit.annotation; public @interface RabbitListener { String[] queues() default {}; " +
                "Queue[] queuesToDeclare() default {}; QueueBinding[] bindings() default {}; }",
        )
        myFixture.addClass("package org.springframework.messaging.handler.annotation; public @interface Payload { }")
        myFixture.addClass("package org.springframework.messaging.handler.annotation; public @interface Header { String value() default \"\"; }")
        myFixture.addClass("package org.springframework.messaging; public interface Message<T> { T getPayload(); }")
        myFixture.addClass("package org.apache.kafka.clients.consumer; public class ConsumerRecord<K, V> { }")
        myFixture.addClass("package org.springframework.kafka.support; public interface Acknowledgment { }")
        myFixture.addClass("package app; public record OrderEvent(String id, int quantity) { }")
        yaml("application.yml", "app:\n  topics:\n    orders: orders.v1\n  queue: payments")
    }

    private fun listener(name: String) =
        myFixture.findClass("app.Listeners").findMethodsByName(name, false).single().let { Listeners.target(project, it)!! }

    private fun listeners() = myFixture.addClass(
        """
        package app;
        import org.springframework.kafka.annotation.*;
        import org.springframework.amqp.rabbit.annotation.*;
        import org.springframework.messaging.handler.annotation.*;
        import org.apache.kafka.clients.consumer.ConsumerRecord;
        public class Listeners {
            @KafkaListener(topics = "${'$'}{app.topics.orders}", groupId = "g")
            public void orders(@Header("kafka_receivedMessageKey") String key, OrderEvent event, org.springframework.kafka.support.Acknowledgment ack) { }

            @KafkaListener(topics = {"a", "b"}, batch = "true")
            public void batch(java.util.List<OrderEvent> events) { }

            @KafkaListener(topics = "records")
            public void records(java.util.List<ConsumerRecord<String, OrderEvent>> records) { }

            @KafkaListener(topicPattern = "orders\\..*")
            public void pattern(@Payload String text) { }

            @KafkaListener(topics = "${'$'}{app.missing}")
            public void missing(OrderEvent event) { }

            @RabbitListener(queues = "${'$'}{app.queue}")
            public void payments(org.springframework.messaging.Message<OrderEvent> message) { }

            @RabbitListener(bindings = @QueueBinding(value = @Queue("audit.q"), exchange = @Exchange("audit"), key = "audit.created"))
            public void audit(String text) { }
        }
        """.trimIndent(),
    )

    fun `test a Kafka topic from the config and the payload past headers and acknowledgments`() {
        listeners()
        val t = listener("orders")
        assertTrue(t.isKafka)
        assertEquals("Listeners.orders", t.label)
        assertEquals(listOf("orders.v1"), t.destinations)
        assertEquals("app.OrderEvent", t.payloadClass)
        assertFalse(t.isText)
        assertFalse(t.batch)
        assertEquals(JsonParser.parseString("""{"id":"string","quantity":0}"""), JsonParser.parseString(Listeners.samplePayload(t)))
    }

    fun `test batch listeners take one message per element`() {
        listeners()
        val batch = listener("batch")
        assertTrue(batch.batch)
        assertEquals(listOf("a", "b"), batch.destinations)
        assertEquals("app.OrderEvent", batch.payloadClass)
        assertTrue(JsonParser.parseString(Listeners.samplePayload(batch)).isJsonArray)
        val records = listener("records")
        assertTrue(records.batch)
        assertEquals("app.OrderEvent", records.payloadClass) // ConsumerRecord<String, OrderEvent> unwrapped
    }

    fun `test what cannot be read is reported`() {
        listeners()
        val pattern = listener("pattern")
        assertEquals(emptyList<String>(), pattern.destinations)
        assertTrue(pattern.unresolved.single(), pattern.unresolved.single().startsWith("the topic pattern"))
        assertTrue(pattern.isText)
        assertEquals("text", Listeners.samplePayload(pattern))
        assertTrue(listener("missing").unresolved.single().contains("app.missing"))
    }

    fun `test Rabbit queues and bindings`() {
        listeners()
        val payments = listener("payments")
        assertFalse(payments.isKafka)
        assertEquals(listOf("payments"), payments.destinations)
        assertNull(payments.exchange)
        assertEquals("app.OrderEvent", payments.payloadClass) // Message<OrderEvent> unwrapped
        val audit = listener("audit")
        assertEquals("audit", audit.exchange)
        assertEquals("audit.created", audit.routingKey)
        assertEquals(listOf("audit.q"), audit.destinations)
        assertTrue(audit.isText)
    }

    fun `test the forms sent to the recorder`() {
        listeners()
        val kafka = MessageForms.build(listener("orders"), "orders.v1", "", "42", true, "X-Trace: abc\nnot a header", """{ "id": "1" }""").single()
        assertEquals(
            listOf("transport" to "kafka", "body" to """{"id":"1"}""", "destination" to "orders.v1", "key" to "42",
                "h.__TypeId__" to "app.OrderEvent", "h.X-Trace" to "abc"),
            kafka,
        )
        val batch = MessageForms.build(listener("batch"), "a", "", "", false, "", """[{"id":"1"},{"id":"2"}]""")
        assertEquals(listOf("""{"id":"1"}""", """{"id":"2"}"""), batch.map { form -> form.first { it.first == "body" }.second })
        val rabbit = MessageForms.build(listener("audit"), "audit.created", "audit", "", false, "", "hello").single()
        assertEquals(
            listOf("transport" to "rabbit", "body" to "hello", "exchange" to "audit", "routingKey" to "audit.created", "contentType" to "text/plain"),
            rabbit,
        )
    }

    fun `test a class-level listener with handlers, and Kotlin`() {
        myFixture.addClass(
            """
            package app;
            @org.springframework.kafka.annotation.KafkaListener(topics = "multi")
            public class Multi {
                @org.springframework.kafka.annotation.KafkaHandler public void on(OrderEvent e) { }
                public void notAHandler(OrderEvent e) { }
            }
            """.trimIndent(),
        )
        val multi = myFixture.findClass("app.Multi")
        assertEquals(listOf("multi"), Listeners.target(project, multi.findMethodsByName("on", false).single())!!.destinations)
        assertNull(Listeners.target(project, multi.findMethodsByName("notAHandler", false).single()))

        val file = myFixture.configureByText(
            "Kt.kt",
            """
            package app
            import org.springframework.kafka.annotation.KafkaListener
            data class Shipped(val orderId: String, val at: java.time.Instant)
            class KtListener {
                @KafkaListener(topics = ["shipments"])
                fun on(event: Shipped) { println(<caret>event) }
            }
            """.trimIndent(),
        )
        val method = Listeners.methodAt(file, myFixture.caretOffset)!!
        val t = Listeners.target(project, method)!!
        assertEquals(listOf("shipments"), t.destinations)
        assertEquals("app.Shipped", t.payloadClass)
        assertEquals(JsonParser.parseString("""{"orderId":"string","at":"2026-01-01T12:00:00Z"}"""), JsonParser.parseString(Listeners.samplePayload(t)))
        assertNull(Listeners.methodAt(file, 0))
    }
}
