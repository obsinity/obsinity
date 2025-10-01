package com.obsinity.collection.spring.scanner;

import com.obsinity.collection.api.annotations.*;
import com.obsinity.collection.core.receivers.TelemetryHandlerRegistry;
import com.obsinity.telemetry.model.TelemetryEvent;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.function.Function;
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

        CompiledReceiver compiled = compileReceiver(bean);
        if (compiled.handlers.isEmpty() && compiled.fallbacks.isEmpty()) return bean;

        registry.register(holder -> compiled.dispatch(holder));
        log.info(
                "Registered EventReceiver: {} (handlers={}, fallbacks={})",
                type.getSimpleName(),
                compiled.handlers.size(),
                compiled.fallbacks.size());
        return bean;
    }

    /* ----------------- compile model ----------------- */

    private CompiledReceiver compileReceiver(Object bean) {
        Class<?> type = bean.getClass();

        // Class-level scopes
        List<String> classScopes = extractScopes(type.getAnnotations());
        Set<OnFlowLifecycle.Lifecycle> classLifecycles = extractLifecycles(type.getAnnotations());

        List<CompiledHandler> handlers = new ArrayList<>();
        List<CompiledHandler> fallbacks = new ArrayList<>();

        ReflectionUtils.doWithMethods(type, m -> {
            CompiledHandler ch = compileHandler(bean, m, classScopes, classLifecycles);
            if (ch == null) return;
            if (ch.fallback) fallbacks.add(ch);
            else handlers.add(ch);
        });

        return new CompiledReceiver(handlers, fallbacks);
    }

    private static List<String> extractScopes(Annotation[] anns) {
        List<String> out = new ArrayList<>();
        for (Annotation a : anns) {
            if (a instanceof OnEventScope s) out.add(nullToEmpty(s.value()));
            if (a instanceof OnEventScopes ss) {
                for (OnEventScope s : ss.value()) out.add(nullToEmpty(s.value()));
            }
        }
        return out;
    }

    private static Set<OnFlowLifecycle.Lifecycle> extractLifecycles(Annotation[] anns) {
        EnumSet<OnFlowLifecycle.Lifecycle> set = EnumSet.noneOf(OnFlowLifecycle.Lifecycle.class);
        boolean all = false;
        for (Annotation a : anns) {
            if (a instanceof OnAllLifecycles) all = true;
            else if (a instanceof OnFlowLifecycle ofl) set.add(ofl.value());
            else if (a instanceof OnFlowLifecycles ofls) {
                for (OnFlowLifecycle ofl2 : ofls.value()) set.add(ofl2.value());
            }
        }
        if (all) return EnumSet.allOf(OnFlowLifecycle.Lifecycle.class);
        return set;
    }

    private CompiledHandler compileHandler(
            Object bean, Method m, List<String> classScopes, Set<OnFlowLifecycle.Lifecycle> classLifecycles) {
        boolean isStarted = m.isAnnotationPresent(OnFlowStarted.class);
        boolean isCompleted = m.isAnnotationPresent(OnFlowCompleted.class);
        boolean isFailure = m.isAnnotationPresent(OnFlowFailure.class);
        boolean isSuccess = m.isAnnotationPresent(OnFlowSuccess.class);
        boolean fallback = m.isAnnotationPresent(OnFlowNotMatched.class);
        OnFlowLifecycle methodLifecycle = m.getAnnotation(OnFlowLifecycle.class);

        boolean anyLifecycleAnno =
                isStarted || isCompleted || isFailure || isSuccess || methodLifecycle != null || fallback;
        if (!anyLifecycleAnno) return null;

        List<String> methodScopes = extractScopes(m.getAnnotations());

        // Determine lifecycles set for this method (intersection if class-level specified)
        EnumSet<OnFlowLifecycle.Lifecycle> lifecycles = EnumSet.noneOf(OnFlowLifecycle.Lifecycle.class);
        if (isStarted) lifecycles.add(OnFlowLifecycle.Lifecycle.STARTED);
        if (isCompleted) {
            // Completed indicates finish handlers; include both success and failure
            lifecycles.add(OnFlowLifecycle.Lifecycle.COMPLETED);
            lifecycles.add(OnFlowLifecycle.Lifecycle.FAILED);
        }
        if (isSuccess) lifecycles.add(OnFlowLifecycle.Lifecycle.COMPLETED);
        if (isFailure) lifecycles.add(OnFlowLifecycle.Lifecycle.FAILED);
        if (methodLifecycle != null) lifecycles.add(methodLifecycle.value());
        if (lifecycles.isEmpty()) lifecycles = EnumSet.allOf(OnFlowLifecycle.Lifecycle.class);
        if (classLifecycles != null && !classLifecycles.isEmpty()) lifecycles.retainAll(classLifecycles);

        // Outcome restriction
        OnOutcome onOutcome = m.getAnnotation(OnOutcome.class);

        // Preconditions
        RequiredAttributes reqAttrs = m.getAnnotation(RequiredAttributes.class);
        RequiredEventContext reqCtx = m.getAnnotation(RequiredEventContext.class);

        // Parameter bindings
        ReflectionUtils.makeAccessible(m);
        Parameter[] params = m.getParameters();
        java.lang.annotation.Annotation[][] pann = m.getParameterAnnotations();
        List<Function<TelemetryHolder, Object>> bindings = new ArrayList<>(params.length);

        boolean allowThrowable = isFailure
                || (methodLifecycle != null && methodLifecycle.value() == OnFlowLifecycle.Lifecycle.FAILED)
                || (isCompleted && onOutcome != null && onOutcome.value() == Outcome.FAILURE);
        for (int i = 0; i < params.length; i++) {
            bindings.add(buildParamBinding(params[i], pann[i], allowThrowable));
            if (bindings.get(i) == null) return null; // unsupported signature
        }

        boolean flowFailureHandler = isFailure;
        boolean failureFinishHandler = isCompleted && onOutcome != null && onOutcome.value() == Outcome.FAILURE;

        return new CompiledHandler(
                bean,
                m,
                classScopes,
                methodScopes,
                lifecycles,
                onOutcome,
                reqAttrs,
                reqCtx,
                bindings,
                fallback,
                flowFailureHandler,
                failureFinishHandler);
    }

    private static Function<TelemetryHolder, Object> buildParamBinding(
            Parameter p, Annotation[] anns, boolean allowThrowable) {
        Class<?> type = p.getType();

        // Holder
        if (TelemetryEvent.class.isAssignableFrom(type)) return h -> h;

        // Throwable for failures
        if (allowThrowable && Throwable.class.isAssignableFrom(type)) {
            boolean root = false;
            for (Annotation a : anns)
                if (a instanceof FlowException ffc && ffc.value() == FlowException.Source.ROOT) root = true;
            final boolean rootOnly = root;
            return h -> chooseThrowable(h, rootOnly);
        }

        // Pull annotations
        for (Annotation a : anns) {
            if (a instanceof PullAllAttributes)
                return h -> h.attributes() != null ? h.attributes().map() : java.util.Map.of();
            if (a instanceof PullAllContextValues) return h -> h.eventContext();
            if (a instanceof PullAttribute pa)
                return h -> coerce(h.attributes() != null ? h.attributes().map().get(pa.value()) : null, type);
            if (a instanceof PullContextValue pcv)
                return h -> coerce(h.eventContext().get(pcv.value()), type);
        }

        // Unannotated — unsupported except for TelemetryHolder/Throwable
        return null;
    }

    /* ----------------- dispatch model ----------------- */

    private record CompiledReceiver(List<CompiledHandler> handlers, List<CompiledHandler> fallbacks) {
        void dispatch(TelemetryHolder h) throws Exception {
            boolean any = false;
            String lcStr = String.valueOf(h.eventContext().get("lifecycle"));
            boolean isFailed = "FAILED".equals(lcStr);

            boolean anyFlowFailureMatched = false;
            if (isFailed) {
                for (CompiledHandler c : handlers) {
                    if (c.flowFailure && c.matches(h)) {
                        c.invoke(h);
                        any = true;
                        anyFlowFailureMatched = true;
                    }
                }
            }

            for (CompiledHandler c : handlers) {
                if (isFailed && c.flowFailure) continue; // already handled
                if (isFailed && c.failureFinish && anyFlowFailureMatched)
                    continue; // suppress finish if failure matched
                if (c.matches(h)) {
                    c.invoke(h);
                    any = true;
                }
            }

            if (!any) {
                for (CompiledHandler f : fallbacks) f.invoke(h);
            }
        }
    }

    private static final class CompiledHandler {
        final Object bean;
        final Method method;
        final List<String> classScopes;
        final List<String> methodScopes;
        final EnumSet<OnFlowLifecycle.Lifecycle> lifecycles;
        final OnOutcome onOutcome;
        final RequiredAttributes reqAttrs;
        final RequiredEventContext reqCtx;
        final List<Function<TelemetryHolder, Object>> bindings;
        final boolean fallback;
        final boolean flowFailure;
        final boolean failureFinish;

        CompiledHandler(
                Object bean,
                Method method,
                List<String> classScopes,
                List<String> methodScopes,
                EnumSet<OnFlowLifecycle.Lifecycle> lifecycles,
                OnOutcome onOutcome,
                RequiredAttributes reqAttrs,
                RequiredEventContext reqCtx,
                List<Function<TelemetryHolder, Object>> bindings,
                boolean fallback,
                boolean flowFailure,
                boolean failureFinish) {
            this.bean = bean;
            this.method = method;
            this.classScopes = classScopes;
            this.methodScopes = methodScopes;
            this.lifecycles = lifecycles;
            this.onOutcome = onOutcome;
            this.reqAttrs = reqAttrs;
            this.reqCtx = reqCtx;
            this.bindings = bindings;
            this.fallback = fallback;
            this.flowFailure = flowFailure;
            this.failureFinish = failureFinish;
        }

        boolean matches(TelemetryHolder h) {
            String lcStr = String.valueOf(h.eventContext().get("lifecycle"));
            OnFlowLifecycle.Lifecycle lc;
            if ("STARTED".equals(lcStr)) lc = OnFlowLifecycle.Lifecycle.STARTED;
            else if ("FAILED".equals(lcStr)) lc = OnFlowLifecycle.Lifecycle.FAILED;
            else lc = OnFlowLifecycle.Lifecycle.COMPLETED;
            if (!lifecycles.contains(lc)) return false;

            if (onOutcome != null) {
                switch (onOutcome.value()) {
                    case SUCCESS -> {
                        if (lc != OnFlowLifecycle.Lifecycle.COMPLETED) return false;
                    }
                    case FAILURE -> {
                        if (lc != OnFlowLifecycle.Lifecycle.FAILED) return false;
                    }
                    default -> {
                        /* OTHER */
                    }
                }
            }

            String name = h.name();
            if (!scopeMatches(classScopes, name)) return false;
            if (!scopeMatches(methodScopes, name)) return false;

            if (reqAttrs != null && !attrsPresent(h, reqAttrs.value())) return false;
            if (reqCtx != null && !ctxPresent(h, reqCtx.value())) return false;
            return true;
        }

        void invoke(TelemetryHolder h) throws Exception {
            Object[] args = new Object[bindings.size()];
            for (int i = 0; i < bindings.size(); i++) args[i] = bindings.get(i).apply(h);
            ReflectionUtils.invokeMethod(method, bean, args);
        }
    }

    /* ----------------- helpers ----------------- */

    private static boolean attrsPresent(TelemetryHolder h, String[] required) {
        if (required == null || required.length == 0) return true;
        Map<String, Object> m = h.attributes() != null ? h.attributes().map() : null;
        if (m == null) return false;
        for (String k : required) if (k == null || k.isBlank() || !m.containsKey(k)) return false;
        return true;
    }

    private static boolean ctxPresent(TelemetryHolder h, String[] required) {
        if (required == null || required.length == 0) return true;
        Map<String, Object> m = h.eventContext();
        if (m == null) return false;
        for (String k : required) if (k == null || k.isBlank() || !m.containsKey(k)) return false;
        return true;
    }

    private static boolean scopeMatches(List<String> scopes, String name) {
        if (scopes == null || scopes.isEmpty()) return true;
        if (name == null) name = "";
        for (String s : scopes) {
            if (s == null) continue;
            s = s.trim();
            if (s.isEmpty()) return true;
            // explicit prefix if ends with '.'
            if (s.endsWith(".")) {
                if (name.startsWith(s)) return true;
            } else {
                if (name.equals(s)) return true;
                if (name.startsWith(s + ".")) return true;
                // dot-chop fallback: a.b.c -> a.b -> a -> ""
                String n = name;
                while (true) {
                    if (n.equals(s)) return true;
                    int idx = n.lastIndexOf('.');
                    if (idx < 0) break;
                    n = n.substring(0, idx);
                }
            }
        }
        return false;
    }

    private static Throwable chooseThrowable(TelemetryHolder holder, boolean root) {
        Throwable t = holder != null ? holder.throwable() : null;
        if (!root || t == null) return t;
        while (t.getCause() != null) t = t.getCause();
        return t;
    }

    private static Object coerce(Object v, Class<?> target) {
        if (v == null) return null;
        if (target.isInstance(v)) return v;
        if (target == String.class) return String.valueOf(v);
        return v; // best-effort; user can adapt types as needed
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
