package dev.tendril.recorder;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationContext;

import java.util.Map;

/**
 * Sends a new message, written in the IDE, through the application's RabbitTemplate. The bytes go out as they are
 * with their content type; Spring AMQP converts them to the listener's parameter type on the other side. Without an
 * exchange the default exchange delivers to the queue named by the routing key.
 */
final class RabbitSend {

    private RabbitSend() {
    }

    static Message message(Map<String, String> form) {
        MessageProperties properties = new MessageProperties();
        String contentType = form.get("contentType");
        properties.setContentType(contentType == null || contentType.isEmpty() ? MessageProperties.CONTENT_TYPE_JSON : contentType);
        properties.setContentEncoding("UTF-8");
        form.forEach((key, value) -> {
            if (key.startsWith("h.")) {
                properties.setHeader(key.substring(2), value);
            }
        });
        return new Message(ControlServer.body(form), properties);
    }

    static String send(ApplicationContext context, Map<String, String> form) {
        RabbitTemplate template = context.getBeanProvider(RabbitTemplate.class).getIfUnique();
        if (template == null) {
            throw new IllegalStateException("no single RabbitTemplate bean in the application");
        }
        String exchange = form.getOrDefault("exchange", "");
        String routingKey = form.getOrDefault("routingKey", "");
        if (exchange.isEmpty() && routingKey.isEmpty()) {
            throw new IllegalArgumentException("no queue or routing key");
        }
        template.send(exchange, routingKey, message(form));
        return exchange.isEmpty() ? "Sent to the queue " + routingKey : "Sent to the exchange " + exchange + " with routing key " + routingKey;
    }
}
