package com.obsinity.collection.spring.scanner;

import com.obsinity.collection.api.annotations.EventReceiver;
import com.obsinity.collection.api.annotations.OnFlowCompleted;
import com.obsinity.collection.api.annotations.OnFlowFailure;
import com.obsinity.collection.api.annotations.OnFlowLifecycle;
import com.obsinity.collection.api.annotations.OnFlowStarted;
import com.obsinity.collection.core.receivers.TelemetryHandlerRegistry;
import com.obsinity.collection.core.receivers.TelemetryReceiver;
import com.obsinity.telemetry.model.TelemetryHolder;
import java.lang.reflect.Method;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.ReflectionUtils;

public class TelemetryHolderReceiverScanner implements BeanPostProcessor, ApplicationContextAware {
    private static final Logger log = LoggerFactory.getLogger(TelemetryHolderReceiverScanner.class);

    private final TelemetryHandlerRegistry registry;
    private ApplicationContext applicationContext;

    public TelemetryHolderReceiverScanner(TelemetryHandlerRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> type = bean.getClass();
        if (!type.isAnnotationPresent(EventReceiver.class)) return bean;
        ReflectionUtils.doWithMethods(type, m -> registerIfAnnotated(bean, m));
        return bean;
    }

    private void registerIfAnnotated(Object bean, Method m) {
        TelemetryReceiver h = createHandler(bean, m);
        if (h == null) return;
        if (m.isAnnotationPresent(OnFlowStarted.class)) {
            registry.register(filterByLifecycle(h, "STARTED"));
            log.info("Registered OnFlowStarted handler: {}#{}", bean.getClass().getSimpleName(), m.getName());
        }
        if (m.isAnnotationPresent(OnFlowCompleted.class)) {
            registry.register(filterByLifecycle(h, "COMPLETED"));
            log.info(
                    "Registered OnFlowCompleted handler: {}#{}", bean.getClass().getSimpleName(), m.getName());
        }
        if (m.isAnnotationPresent(OnFlowFailure.class)) {
            registry.register(filterByLifecycle(h, "FAILED"));
            log.info("Registered OnFlowFailure handler: {}#{}", bean.getClass().getSimpleName(), m.getName());
        }
        OnFlowLifecycle ofl = m.getAnnotation(OnFlowLifecycle.class);
        if (ofl != null) {
            switch (ofl.value()) {
                case STARTED -> registry.register(filterByLifecycle(h, "STARTED"));
                case COMPLETED -> registry.register(filterByLifecycle(h, "COMPLETED"));
                case FAILED -> registry.register(filterByLifecycle(h, "FAILED"));
            }
            log.info(
                    "Registered OnFlowLifecycle handler: {}#{} -> {}",
                    bean.getClass().getSimpleName(),
                    m.getName(),
                    ofl.value());
        }
    }

    private static TelemetryReceiver createHandler(Object bean, Method m) {
        if (m.getParameterCount() == 0) {
            return holder -> ReflectionUtils.invokeMethod(m, bean);
        }
        if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(TelemetryHolder.class)) {
            return holder -> ReflectionUtils.invokeMethod(m, bean, holder);
        }
        return null;
    }

    private static TelemetryReceiver filterByLifecycle(TelemetryReceiver delegate, String lifecycle) {
        return holder -> {
            Object lc = holder.eventContext().get("lifecycle");
            if (lifecycle.equals(lc)) delegate.handle(holder);
        };
    }
}
