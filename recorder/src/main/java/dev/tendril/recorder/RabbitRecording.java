package dev.tendril.recorder;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.AbstractRabbitListenerContainerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Records the messages @RabbitListener methods receive. The recorder is added to the advice chain of every listener
 * container factory, next to whatever advice the application configured (retry, for example).
 */
final class RabbitRecording implements BeanPostProcessor {

    private static final MethodInterceptor RECORDER = invocation -> {
        long startedAt = System.currentTimeMillis();
        long started = System.nanoTime();
        String error = null;
        try {
            return invocation.proceed();
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            throw t;
        } finally {
            try {
                for (Object argument : invocation.getArguments()) {
                    if (argument instanceof Message message) {
                        record(message, error, startedAt, (System.nanoTime() - started) / 1_000_000);
                    } else if (argument instanceof List<?> batch) {
                        for (Object item : batch) {
                            if (item instanceof Message message) {
                                record(message, error, startedAt, (System.nanoTime() - started) / 1_000_000);
                            }
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // recording must never affect the application
            }
        }
    };

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof AbstractRabbitListenerContainerFactory<?> factory) {
            try {
                Field field = AbstractRabbitListenerContainerFactory.class.getDeclaredField("adviceChain");
                field.setAccessible(true);
                Advice[] existing = (Advice[]) field.get(factory);
                List<Advice> merged = new ArrayList<>(existing == null ? List.of() : Arrays.asList(existing));
                if (!merged.contains(RECORDER)) {
                    merged.add(RECORDER);
                }
                factory.setAdviceChain(merged.toArray(new Advice[0]));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // an unexpected Spring AMQP version: record nothing rather than break the application
            }
        }
        return bean;
    }

    private static void record(Message message, String error, long startedAt, long millis) {
        MessageProperties p = message.getMessageProperties();
        List<String[]> headers = new ArrayList<>();
        for (Map.Entry<String, Object> e : p.getHeaders().entrySet()) {
            headers.add(new String[]{e.getKey(), String.valueOf(e.getValue())});
        }
        Json event = Json.object()
                .str("kind", "message")
                .str("transport", "rabbit")
                .str("project", Config.project())
                .num("time", startedAt)
                .num("durationMs", millis)
                .num("flow", 0)
                .str("destination", p.getConsumerQueue())
                .str("exchange", p.getReceivedExchange() == null ? "" : p.getReceivedExchange())
                .str("routingKey", p.getReceivedRoutingKey())
                .str("contentType", p.getContentType())
                .headers("headers", headers)
                .body("body", message.getBody(), p.getContentType())
                .bool("replayable", true);
        if (error != null) {
            event.str("error", error);
        }
        Sender.send(event.build());
    }
}
