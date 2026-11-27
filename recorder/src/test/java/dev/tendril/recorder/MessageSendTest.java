package dev.tendril.recorder;

import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageSendTest {

    @Test
    void aTestProducerTakesTheConnectionButNotTheSerializers() {
        Map<String, Object> settings = KafkaSend.producerSettings(Map.of(
                "bootstrap.servers", "broker:9092", "security.protocol", "SASL_SSL", "sasl.mechanism", "PLAIN",
                "ssl.truststore.location", "/x", "value.serializer", "org.springframework.kafka.support.serializer.JsonSerializer",
                "group.id", "g"));
        assertEquals("broker:9092", settings.get("bootstrap.servers"));
        assertEquals("SASL_SSL", settings.get("security.protocol"));
        assertEquals("PLAIN", settings.get("sasl.mechanism"));
        assertEquals("/x", settings.get("ssl.truststore.location"));
        assertFalse(settings.containsKey("value.serializer"));
        assertFalse(settings.containsKey("group.id"));
    }

    @Test
    void kafkaHeadersComeFromTheForm() {
        List<Header> headers = KafkaSend.headers(Map.of("h.__TypeId__", "app.OrderEvent", "destination", "orders"));
        assertEquals(1, headers.size());
        assertEquals("__TypeId__", headers.get(0).key());
        assertEquals("app.OrderEvent", new String(headers.get(0).value(), StandardCharsets.UTF_8));
    }

    @Test
    void aRabbitMessageIsJsonUnlessToldOtherwise() {
        Message json = RabbitSend.message(Map.of("body", "{\"id\":1}", "h.x-tenant", "acme"));
        assertEquals("application/json", json.getMessageProperties().getContentType());
        assertEquals("acme", json.getMessageProperties().getHeader("x-tenant"));
        assertEquals("{\"id\":1}", new String(json.getBody(), StandardCharsets.UTF_8));
        assertEquals("text/plain", RabbitSend.message(Map.of("body", "hi", "contentType", "text/plain")).getMessageProperties().getContentType());
    }
}
