package dev.tendril.recorder;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;

/**
 * Switched on by the agent argument {@code port=...}. Each recorder is only active when the library it hooks
 * into is on the application's class path.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tendril.recorder", name = "port")
public class RecorderAutoConfiguration {

    @Bean(destroyMethod = "stop")
    ControlServer tendrilControlServer(ApplicationContext context) throws IOException {
        return new ControlServer(context);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = {"jakarta.servlet.Filter", "org.springframework.web.filter.OncePerRequestFilter"})
    static class Web {
        @Bean
        @ConditionalOnProperty(prefix = "tendril.recorder", name = "sql", havingValue = "true")
        static BeanPostProcessor tendrilSqlRecording() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof javax.sql.DataSource) || java.lang.reflect.Modifier.isFinal(bean.getClass().getModifiers())) {
                        return bean;
                    }
                    try {
                        var factory = new org.springframework.aop.framework.ProxyFactory(bean);
                        factory.setProxyTargetClass(true);
                        factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                            Object result = invocation.proceed();
                            return invocation.getMethod().getName().equals("getConnection") && result instanceof java.sql.Connection connection
                                    ? SqlRecording.wrap(connection) : result;
                        });
                        return factory.getProxy(bean.getClass().getClassLoader());
                    } catch (org.springframework.aop.framework.AopConfigException ignored) {
                        return bean;
                    }
                }
            };
        }

        @Bean
        FilterRegistrationBean<HttpInFilter> tendrilHttpInFilter() {
            FilterRegistrationBean<HttpInFilter> registration = new FilterRegistrationBean<>(new HttpInFilter());
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return registration;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "feign.Capability")
    static class Feign {
        @Bean
        feign.Capability tendrilFeignRecording() {
            return new FeignRecording();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.amqp.rabbit.config.AbstractRabbitListenerContainerFactory")
    static class Rabbit {
        @Bean
        static BeanPostProcessor tendrilRabbitRecording() {
            return new RabbitRecording();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.kafka.config.AbstractKafkaListenerContainerFactory")
    static class Kafka {
        @Bean
        static BeanPostProcessor tendrilKafkaRecording() {
            return new KafkaRecording();
        }
    }
}
