package com.obsinity.collection.spring.validation;

import com.obsinity.flow.validation.FlowAttributeValidator;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Modifier;
import java.time.temporal.TemporalAccessor;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects Hibernate entities in flow attributes/context to prevent serialization issues and memory leaks.
 *
 * <p>Hibernate entities should not be stored in flow context as they:
 * <ul>
 *   <li>May cause lazy loading exceptions when serialized</li>
 *   <li>Can hold references to large object graphs</li>
 *   <li>May keep database connections/sessions alive</li>
 *   <li>Can cause ThreadLocal leaks via entity manager references</li>
 * </ul>
 *
 * <p>The detector checks for:
 * <ul>
 *   <li>Hibernate proxy patterns (javassist, cglib, HibernateProxy)</li>
 *   <li>JPA {@code @Entity} annotations (jakarta.persistence, javax.persistence)</li>
 *   <li>Hibernate-specific {@code @Entity} annotations</li>
 * </ul>
 *
 * <h3>Configuration:</h3>
 * <pre>{@code
 * # application.yml
 * obsinity:
 *   collection:
 *     validation:
 *       hibernate-entity-check:
 *         enabled: true    # Enable/disable validation
 *         log-level: ERROR # ERROR (throws), WARN, INFO
 * }</pre>
 *
 * @see FlowAttributeValidator
 */
public class HibernateEntityDetector implements FlowAttributeValidator {

    private static final Logger log = LoggerFactory.getLogger(HibernateEntityDetector.class);
    private static final Set<Class<?>> SIMPLE_TYPES = Set.of(
            Boolean.class,
            Byte.class,
            Short.class,
            Integer.class,
            Long.class,
            Float.class,
            Double.class,
            Character.class);
    private final HibernateEntityLogLevel logLevel;

    public HibernateEntityDetector(HibernateEntityLogLevel logLevel) {
        this.logLevel = logLevel == null ? HibernateEntityLogLevel.ERROR : logLevel;
    }

    @Override
    public void validate(String key, Object value) {
        checkNotHibernateEntity(key, value, logLevel);
    }

    /**
     * Check if value (or nested value) is a Hibernate entity and handle according to log level.
     *
     * @param key the attribute/context key
     * @param value the value to check (including nested collections/fields)
     * @param level log level to use; ERROR will throw
     * @throws IllegalArgumentException if value is a Hibernate entity and level is ERROR
     */
    public static void checkNotHibernateEntity(String key, Object value, HibernateEntityLogLevel level) {
        if (value == null) return;
        detect(key, key, value, new IdentityHashMap<>(), level == null ? HibernateEntityLogLevel.ERROR : level);
    }

    private static void detect(
            String root, String path, Object value, Map<Object, Boolean> visited, HibernateEntityLogLevel level) {
        if (value == null) return;
        if (visited.containsKey(value)) return;

        Class<?> clazz = value.getClass();

        if (isHibernateEntity(clazz)) {
            String message = String.format(
                    "Hibernate entity detected in flow attribute '%s' at path '%s'. "
                            + "Hibernate entities must not be stored in flow context. "
                            + "Extract primitive values or DTOs instead. Class: %s",
                    root, path, clazz.getName());
            if (level == HibernateEntityLogLevel.ERROR) {
                throw new IllegalArgumentException(message);
            }
            if (level == HibernateEntityLogLevel.WARN) {
                log.warn(message);
            } else {
                log.info(message);
            }
            return;
        }

        if (isTerminalValue(clazz)) return;

        visited.put(value, Boolean.TRUE);

        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                detect(root, path + "[" + String.valueOf(entry.getKey()) + "]", entry.getValue(), visited, level);
            }
            return;
        }

        if (value instanceof Iterable<?> iterable) {
            int idx = 0;
            for (Object element : iterable) {
                detect(root, path + "[" + idx + "]", element, visited, level);
                idx++;
            }
            return;
        }

        if (clazz.isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                detect(root, path + "[" + i + "]", java.lang.reflect.Array.get(value, i), visited, level);
            }
            return;
        }

        if (value instanceof Optional<?> optional) {
            optional.ifPresent(nested -> detect(root, path + ".value", nested, visited, level));
            return;
        }

        for (Field field : getAllFields(clazz)) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            try {
                if (!field.canAccess(value)) {
                    field.setAccessible(true);
                }
                Object fieldValue = field.get(value);
                detect(root, path + "." + field.getName(), fieldValue, visited, level);
            } catch (IllegalAccessException | InaccessibleObjectException ignored) {
                // Skip fields we cannot access
            }
        }
    }

    private static boolean isTerminalValue(Class<?> clazz) {
        return clazz.isPrimitive()
                || SIMPLE_TYPES.contains(clazz)
                || CharSequence.class.isAssignableFrom(clazz)
                || Number.class.isAssignableFrom(clazz)
                || Enum.class.isAssignableFrom(clazz)
                || UUID.class.isAssignableFrom(clazz)
                || Date.class.isAssignableFrom(clazz)
                || TemporalAccessor.class.isAssignableFrom(clazz)
                || Class.class.isAssignableFrom(clazz);
    }

    private static boolean hasHibernateEntityAnnotation(Class<?> clazz) {
        try {
            // Check for @Entity annotation from Hibernate
            for (java.lang.annotation.Annotation ann : clazz.getAnnotations()) {
                String annName = ann.annotationType().getName();
                if ("org.hibernate.annotations.Entity".equals(annName)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Ignore - class might not have annotation metadata
        }
        return false;
    }

    private static boolean hasJpaEntityAnnotation(Class<?> clazz) {
        try {
            // Check for @Entity annotation from JPA (jakarta.persistence or javax.persistence)
            for (java.lang.annotation.Annotation ann : clazz.getAnnotations()) {
                String annName = ann.annotationType().getName();
                if ("jakarta.persistence.Entity".equals(annName) || "javax.persistence.Entity".equals(annName)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Ignore - class might not have annotation metadata
        }
        return false;
    }

    private static boolean isHibernateEntity(Class<?> clazz) {
        return clazz.getName().contains("$$_javassist_")
                || clazz.getName().contains("_$$_jvst")
                || clazz.getName().contains("$HibernateProxy$")
                || hasHibernateEntityAnnotation(clazz)
                || hasJpaEntityAnnotation(clazz);
    }

    private static Iterable<Field> getAllFields(Class<?> type) {
        if (type == null || Object.class.equals(type)) {
            return Collections.emptyList();
        }
        Deque<Field> fields = new LinkedList<>();
        Class<?> current = type;
        while (current != null && !Object.class.equals(current)) {
            Collections.addAll(fields, current.getDeclaredFields());
            current = current.getSuperclass();
        }
        return fields;
    }
}
