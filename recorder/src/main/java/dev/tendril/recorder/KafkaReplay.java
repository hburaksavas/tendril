package dev.tendril.recorder;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Republishes a recorded record through the application's KafkaTemplate (String and byte[] values only). */
@SuppressWarnings({"rawtypes", "unchecked"})
final class KafkaReplay {

    private KafkaReplay() {
    }

    static void send(ApplicationContext context, Map<String, String> form) throws Exception {
        KafkaTemplate template = context.getBeanProvider(KafkaTemplate.class).getIfUnique();
        if (template == null) {
            throw new IllegalStateException("no single KafkaTemplate bean in the application");
        }
        List<org.apache.kafka.common.header.Header> headers = new ArrayList<>();
        form.forEach((key, value) -> {
            if (key.startsWith("h.")) {
                headers.add(new RecordHeader(key.substring(2), value.getBytes(StandardCharsets.UTF_8)));
            }
        });
        byte[] body = ControlServer.body(form);
        // a String value for the common StringSerializer, raw bytes when the record was binary
        Object value = "base64".equals(form.get("bodyEncoding")) ? body : new String(body, StandardCharsets.UTF_8);
        String key = form.get("routingKey");
        template.send(new ProducerRecord<Object, Object>(form.get("destination"), (Integer) null, (Object) key, value, headers)).get();
    }
}
