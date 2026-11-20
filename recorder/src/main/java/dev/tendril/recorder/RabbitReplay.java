package dev.tendril.recorder;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationContext;

import java.util.Map;

/** Republishes a recorded message through the application's RabbitTemplate. */
final class RabbitReplay {

    private RabbitReplay() {
    }

    static void send(ApplicationContext context, Map<String, String> form) {
        RabbitTemplate template = context.getBeanProvider(RabbitTemplate.class).getIfUnique();
        if (template == null) {
            throw new IllegalStateException("no single RabbitTemplate bean in the application");
        }
        MessageProperties properties = new MessageProperties();
        String contentType = form.get("contentType");
        if (contentType != null && !contentType.isEmpty()) {
            properties.setContentType(contentType);
        }
        form.forEach((key, value) -> {
            if (key.startsWith("h.")) {
                properties.setHeader(key.substring(2), value);
            }
        });
        template.send(form.getOrDefault("exchange", ""), form.getOrDefault("routingKey", ""),
                new Message(ControlServer.body(form), properties));
    }
}
