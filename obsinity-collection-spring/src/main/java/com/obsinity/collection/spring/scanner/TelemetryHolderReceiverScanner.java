package com.obsinity.collection.spring.scanner;

import com.obsinity.collection.api.annotations.EventReceiver;
import com.obsinity.collection.api.annotations.OnFlowCompleted;
import com.obsinity.collection.api.annotations.OnFlowFailure;
import com.obsinity.collection.api.annotations.OnFlowLifecycle;
import com.obsinity.collection.api.annotations.OnFlowStarted;
import com.obsinity.collection.api.annotations.RequiredAttributes;
import com.obsinity.collection.api.annotations.FlowException;
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
        boolean hasLifecycleAnnotation = m.isAnnotationPresent(OnFlowStarted.class)
                || m.isAnnotationPresent(OnFlowCompleted.class)
                || m.isAnnotationPresent(OnFlowFailure.class)
                || m.isAnnotationPresent(OnFlowLifecycle.class);
        if (!hasLifecycleAnnotation) return;

        RequiredAttributes ra = m.getAnnotation(RequiredAttributes.class);

        if (m.isAnnotationPresent(OnFlowStarted.class)) {
            TelemetryReceiver h = createHandler(bean, m, false);
            if (h != null) {
                if (ra != null) h = filterByRequiredAttributes(h, ra.value());
                registry.register(filterByLifecycle(h, "STARTED"));
                log.info(
                        "Registered OnFlowStarted handler: {}#{}",
                        bean.getClass().getSimpleName(),
                        m.getName());
            }
        }
        if (m.isAnnotationPresent(OnFlowCompleted.class)) {
            TelemetryReceiver h = createHandler(bean, m, false);
            if (h != null) {
                if (ra != null) h = filterByRequiredAttributes(h, ra.value());
                registry.register(filterByLifecycle(h, "COMPLETED"));
                log.info(
                        "Registered OnFlowCompleted handler: {}#{}",
                        bean.getClass().getSimpleName(),
                        m.getName());
            }
        }
        if (m.isAnnotationPresent(OnFlowFailure.class)) {
            TelemetryReceiver h = createHandler(bean, m, true);
            if (h != null) {
                if (ra != null) h = filterByRequiredAttributes(h, ra.value());
                registry.register(filterByLifecycle(h, "FAILED"));
                log.info(
                        "Registered OnFlowFailure handler: {}#{}",
                        bean.getClass().getSimpleName(),
                        m.getName());
            }
        }
        OnFlowLifecycle ofl = m.getAnnotation(OnFlowLifecycle.class);
        if (ofl != null) {
            switch (ofl.value()) {
                case STARTED -> {
                    TelemetryReceiver h = createHandler(bean, m, false);
                    if (h != null) {
                        if (ra != null) h = filterByRequiredAttributes(h, ra.value());
                        registry.register(filterByLifecycle(h, "STARTED"));
                    }
                }
                case COMPLETED -> {
                    TelemetryReceiver h = createHandler(bean, m, false);
                    if (h != null) {
                        if (ra != null) h = filterByRequiredAttributes(h, ra.value());
                        registry.register(filterByLifecycle(h, "COMPLETED"));
                    }
                }
                case FAILED -> {
                    TelemetryReceiver h = createHandler(bean, m, true);
                    if (h != null) {
                        if (ra != null) h = filterByRequiredAttributes(h, ra.value());
                        registry.register(filterByLifecycle(h, "FAILED"));
                    }
                }
            }
            log.info(
                    "Registered OnFlowLifecycle handler: {}#{} -> {}",
                    bean.getClass().getSimpleName(),
                    m.getName(),
                    ofl.value());
        }
    }

    private static TelemetryReceiver createHandler(Object bean, Method m, boolean allowThrowableParam) {
        ReflectionUtils.makeAccessible(m);
        Class<?>[] p = m.getParameterTypes();
        java.lang.annotation.Annotation[][] ann = m.getParameterAnnotations();
        if (p.length == 0) {
            return holder -> ReflectionUtils.invokeMethod(m, bean);
        }
        if (p.length == 1) {
            Class<?> a0 = p[0];
            if (a0.isAssignableFrom(TelemetryHolder.class)) {
                return holder -> ReflectionUtils.invokeMethod(m, bean, holder);
            }
            if (allowThrowableParam && Throwable.class.isAssignableFrom(a0)) {
                final boolean root = isRootRequested(ann[0]);
                return holder -> ReflectionUtils.invokeMethod(m, bean, chooseThrowable(holder, root));
            }
            return null;
        }
        if (p.length == 2) {
            Class<?> a0 = p[0], a1 = p[1];
            boolean firstIsHolder = a0.isAssignableFrom(TelemetryHolder.class);
            boolean secondIsHolder = a1.isAssignableFrom(TelemetryHolder.class);
            boolean firstIsThrowable = Throwable.class.isAssignableFrom(a0);
            boolean secondIsThrowable = Throwable.class.isAssignableFrom(a1);
            if (allowThrowableParam && firstIsHolder && secondIsThrowable) {
                final boolean root = isRootRequested(ann[1]);
                return holder -> ReflectionUtils.invokeMethod(m, bean, holder, chooseThrowable(holder, root));
            }
            if (allowThrowableParam && firstIsThrowable && secondIsHolder) {
                final boolean root = isRootRequested(ann[0]);
                return holder -> ReflectionUtils.invokeMethod(m, bean, chooseThrowable(holder, root), holder);
            }
        }
        return null;
    }

    private static boolean isRootRequested(java.lang.annotation.Annotation[] anns) {
        if (anns == null) return false;
        for (var a : anns) {
            if (a instanceof FlowException ffc && ffc.value() == FlowException.Source.ROOT) return true;
        }
        return false;
    }

    private static Throwable chooseThrowable(TelemetryHolder holder, boolean root) {
        Throwable t = holder != null ? holder.throwable() : null;
        if (!root || t == null) return t;
        while (t.getCause() != null) t = t.getCause();
        return t;
    }

    private static TelemetryReceiver filterByLifecycle(TelemetryReceiver delegate, String lifecycle) {
        return holder -> {
            Object lc = holder.eventContext().get("lifecycle");
            if (lifecycle.equals(lc)) delegate.handle(holder);
        };
    }

    private static TelemetryReceiver filterByRequiredAttributes(TelemetryReceiver delegate, String[] required) {
        return holder -> {
            if (required == null || required.length == 0) {
                delegate.handle(holder);
                return;
            }
            var attrs = holder.attributes() != null ? holder.attributes().map() : null;
            if (attrs == null) return; // nothing to check against
            for (String key : required) {
                if (key == null || key.isBlank()) return; // invalid key => skip
                if (!attrs.containsKey(key)) return; // missing required attribute
            }
            delegate.handle(holder);
        };
    }
}
