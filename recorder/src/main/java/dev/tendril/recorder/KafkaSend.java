package dev.tendril.recorder;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Sends a new record, written in the IDE, to a topic. The bytes go out as they are: a producer with byte-array
 * serializers is built from the application's own settings (bootstrap servers, security), so a JSON payload is
 * not encoded a second time by a JsonSerializer.
 */
final class KafkaSend {

    private KafkaSend() {
    }

    /** Settings of the application's producer, else the connection settings of its consumer. */
    static Map<String, Object> connection(ApplicationContext context) {
        ProducerFactory<?, ?> producer = context.getBeanProvider(ProducerFactory.class).getIfUnique();
        if (producer != null) {
            return producerSettings(producer.getConfigurationProperties());
        }
        ConsumerFactory<?, ?> consumer = context.getBeanProvider(ConsumerFactory.class).getIfUnique();
        if (consumer != null) {
            return producerSettings(consumer.getConfigurationProperties());
        }
        throw new IllegalStateException("no single Kafka producer or consumer factory in the application");
    }

    /** What a producer can take over from [settings]: the connection, not the (de)serializers. */
    static Map<String, Object> producerSettings(Map<String, Object> settings) {
        Map<String, Object> out = new HashMap<>();
        settings.forEach((key, value) -> {
            if (key.startsWith("bootstrap.") || key.startsWith("security.") || key.startsWith("sasl.") || key.startsWith("ssl.")
                    || key.equals("client.dns.lookup")) {
                out.put(key, value);
            }
        });
        out.put("client.id", "tendril-test-message");
        return out;
    }

    static List<Header> headers(Map<String, String> form) {
        List<Header> headers = new ArrayList<>();
        form.forEach((key, value) -> {
            if (key.startsWith("h.")) {
                headers.add(new RecordHeader(key.substring(2), value.getBytes(StandardCharsets.UTF_8)));
            }
        });
        return headers;
    }

    static String send(ApplicationContext context, Map<String, String> form) throws Exception {
        String topic = form.getOrDefault("destination", "");
        if (topic.isEmpty()) {
            throw new IllegalArgumentException("no topic");
        }
        String key = form.get("key");
        DefaultKafkaProducerFactory<byte[], byte[]> factory =
                new DefaultKafkaProducerFactory<>(connection(context), new ByteArraySerializer(), new ByteArraySerializer());
        try {
            KafkaTemplate<byte[], byte[]> template = new KafkaTemplate<>(factory);
            ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, null,
                    key == null || key.isEmpty() ? null : key.getBytes(StandardCharsets.UTF_8), ControlServer.body(form), headers(form));
            RecordMetadata sent = template.send(record).get(15, TimeUnit.SECONDS).getRecordMetadata();
            return "Sent to " + sent.topic() + "-" + sent.partition() + " at offset " + sent.offset();
        } finally {
            factory.destroy();
        }
    }
}
