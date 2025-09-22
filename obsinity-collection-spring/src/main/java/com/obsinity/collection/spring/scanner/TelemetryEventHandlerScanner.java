package com.obsinity.collection.spring.scanner;

import com.obsinity.collection.api.annotations.EventReceiver;
import com.obsinity.collection.api.annotations.OnFlowCompleted;
import com.obsinity.collection.api.annotations.OnFlowFailure;
import com.obsinity.collection.api.annotations.OnFlowLifecycle;
import com.obsinity.collection.api.annotations.OnFlowStarted;
import com.obsinity.collection.core.model.OEvent;
import com.obsinity.collection.core.receivers.EventHandler;
import com.obsinity.collection.core.receivers.HandlerRegistry;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.ReflectionUtils;

public class TelemetryEventHandlerScanner implements BeanPostProcessor, ApplicationContextAware {
    private static final Logger log = LoggerFactory.getLogger(TelemetryEventHandlerScanner.class);

    private final HandlerRegistry registry;
    private ApplicationContext applicationContext;

    public TelemetryEventHandlerScanner(HandlerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> type = bean.getClass();
        if (!type.isAnnotationPresent(EventReceiver.class)) return bean;

        ReflectionUtils.doWithMethods(type, m -> registerHandler(bean, m));
        return bean;
    }

    private void registerHandler(Object bean, Method m) {
        EventHandler h = createHandler(bean, m);
        if (h == null) return;

        if (m.isAnnotationPresent(OnFlowStarted.class)) {
            registry.register(eventWithSuffix(h, ":started"));
            log.info("Registered OnFlowStarted handler: {}#{}", bean.getClass().getSimpleName(), m.getName());
        }
        if (m.isAnnotationPresent(OnFlowCompleted.class)) {
            registry.register(eventWithSuffix(h, ":completed"));
            log.info(
                    "Registered OnFlowCompleted handler: {}#{}", bean.getClass().getSimpleName(), m.getName());
        }
        if (m.isAnnotationPresent(OnFlowFailure.class)) {
            registry.register(eventWithSuffix(h, ":failed"));
            log.info("Registered OnFlowFailure handler: {}#{}", bean.getClass().getSimpleName(), m.getName());
        }
        OnFlowLifecycle ofl = m.getAnnotation(OnFlowLifecycle.class);
        if (ofl != null) {
            switch (ofl.value()) {
                case STARTED -> registry.register(eventWithSuffix(h, ":started"));
                case COMPLETED -> registry.register(eventWithSuffix(h, ":completed"));
                case FAILED -> registry.register(eventWithSuffix(h, ":failed"));
            }
            log.info(
                    "Registered OnFlowLifecycle handler: {}#{} -> {}",
                    bean.getClass().getSimpleName(),
                    m.getName(),
                    ofl.value());
        }
    }

    private static EventHandler createHandler(Object bean, Method m) {
        // zero-arg or single OEvent arg supported in this pass
        if (m.getParameterCount() == 0) {
            return event -> ReflectionUtils.invokeMethod(m, bean);
        }
        if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(OEvent.class)) {
            return event -> ReflectionUtils.invokeMethod(m, bean, event);
        }
        return null;
    }

    private static EventHandler eventWithSuffix(EventHandler delegate, String suffix) {
        return event -> {
            String name = event.name();
            if (name != null && name.endsWith(suffix)) delegate.handle(event);
        };
    }
}
