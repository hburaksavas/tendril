package dev.tendril.recorder;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.listener.RecordInterceptor;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Records the records @KafkaListener methods receive, keeping an interceptor the application already set. */
@SuppressWarnings({"rawtypes", "unchecked"})
final class KafkaRecording implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof AbstractKafkaListenerContainerFactory factory) {
            try {
                Field field = AbstractKafkaListenerContainerFactory.class.getDeclaredField("recordInterceptor");
                field.setAccessible(true);
                RecordInterceptor existing = (RecordInterceptor) field.get(factory);
                if (!(existing instanceof Recorder)) {
                    factory.setRecordInterceptor(new Recorder(existing));
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // an unexpected Spring Kafka version: record nothing rather than break the application
            }
        }
        return bean;
    }

    private static final class Recorder implements RecordInterceptor<Object, Object> {
        private final RecordInterceptor<Object, Object> next;

        Recorder(RecordInterceptor<Object, Object> next) {
            this.next = next;
        }

        @Override
        public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                        Consumer<Object, Object> consumer) {
            try {
                record(record);
            } catch (RuntimeException ignored) {
                // recording must never affect the application
            }
            return next == null ? record : next.intercept(record, consumer);
        }

        private static void record(ConsumerRecord<Object, Object> r) {
            List<String[]> headers = new ArrayList<>();
            for (Header h : r.headers()) {
                headers.add(new String[]{h.key(), h.value() == null ? "" : new String(h.value(), StandardCharsets.UTF_8)});
            }
            Object value = r.value();
            Json event = Json.object()
                    .str("kind", "message")
                    .str("transport", "kafka")
                    .str("project", Config.project())
                    .num("time", r.timestamp() > 0 ? r.timestamp() : System.currentTimeMillis())
                    .num("durationMs", 0)
                    .num("flow", 0)
                    .str("destination", r.topic())
                    .str("routingKey", r.key() == null ? null : String.valueOf(r.key()))
                    .num("partition", r.partition())
                    .num("offset", r.offset())
                    .headers("headers", headers);
            if (value instanceof byte[] bytes) {
                event.body("body", bytes, null).bool("replayable", true);
            } else if (value instanceof String text) {
                event.body("body", text.getBytes(StandardCharsets.UTF_8), "text/plain").bool("replayable", true);
            } else if (value != null) {
                // an object produced by a deserializer: shown for reference, but it cannot be republished faithfully
                event.str("body", String.valueOf(value)).str("bodyEncoding", "text")
                        .str("valueType", value.getClass().getName()).bool("replayable", false);
            }
            Sender.send(event.build());
        }
    }
}
