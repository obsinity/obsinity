package com.obsinity.collection.spring.processor;

import com.obsinity.collection.api.annotations.PushAttribute;
import com.obsinity.collection.api.annotations.PushContextValue;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

public final class AttributeParamExtractor {
    public record AttrCtx(Map<String, Object> attributes, Map<String, Object> context) {}

    private AttributeParamExtractor() {}

    public static AttrCtx extract(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method m = sig.getMethod();
        Object[] args = pjp.getArgs();

        Map<String, Object> attrs = new LinkedHashMap<>();
        Map<String, Object> ctx = new LinkedHashMap<>();

        Annotation[][] anns = m.getParameterAnnotations();
        int n = Math.min(anns.length, args == null ? 0 : args.length);
        for (int i = 0; i < n; i++) {
            Object arg = args[i];
            for (Annotation a : anns[i]) {
                if (a instanceof PushAttribute pa) {
                    String key = pa.value();
                    if (key != null && !key.isBlank()) attrs.put(key, arg);
                } else if (a instanceof PushContextValue pc) {
                    String key = pc.value();
                    if (key != null && !key.isBlank()) ctx.put(key, arg);
                }
            }
        }
        return new AttrCtx(attrs, ctx);
    }
}
