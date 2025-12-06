package com.obsinity.collection.spring.validation;

import com.obsinity.flow.validation.FlowAttributeValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Spring-managed component that detects Hibernate entities in flow attributes/context
 * to prevent serialization issues and memory leaks.
 *
 * <p>Hibernate entities should not be stored in flow context as they:
 * <ul>
 *   <li>May cause lazy loading exceptions when serialized</li>
 *   <li>Can hold references to large object graphs</li>
 *   <li>May keep database connections/sessions alive</li>
 *   <li>Can cause ThreadLocal leaks via entity manager references</li>
 * </ul>
 *
 * <p>This validator is automatically registered as a Spring bean when the property
 * {@code obsinity.collection.validation.hibernate-entity-check} is {@code true} (default).
 * When disabled, {@link LoggingEntityDetector} is used instead, which logs errors
 * rather than throwing exceptions.
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
 *       hibernate-entity-check: true  # Default: throws exception on entity detection
 * }</pre>
 *
 * @see FlowAttributeValidator
 * @see LoggingEntityDetector
 * @see org.springframework.stereotype.Component
 * @see org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
 */
@Component
@ConditionalOnProperty(
        prefix = "obsinity.collection.validation",
        name = "hibernate-entity-check",
        havingValue = "true",
        matchIfMissing = true)
public class HibernateEntityDetector implements FlowAttributeValidator {

    /**
     * Default constructor for Spring dependency injection.
     * Empty by design - no initialization needed.
     */
    public HibernateEntityDetector() {
        // Empty constructor for Spring component scanning and dependency injection
    }

    @Override
    public void validate(String key, Object value) {
        checkNotHibernateEntity(key, value);
    }

    /**
     * Check if value is a Hibernate entity and throw exception if so.
     *
     * @param key the attribute/context key
     * @param value the value to check
     * @throws IllegalArgumentException if value is a Hibernate entity
     */
    public static void checkNotHibernateEntity(String key, Object value) {
        if (value == null) {
            return;
        }

        Class<?> clazz = value.getClass();

        // Check for Hibernate proxy
        if (clazz.getName().contains("$$_javassist_")
                || clazz.getName().contains("_$$_jvst")
                || clazz.getName().contains("$HibernateProxy$")) {
            throw new IllegalArgumentException(String.format(
                    "Hibernate proxy detected in flow attribute '%s'. "
                            + "Hibernate entities must not be stored in flow context. "
                            + "Extract primitive values or DTOs instead. Class: %s",
                    key, clazz.getName()));
        }

        // Check for common Hibernate entity annotations via reflection
        // This catches non-proxied entities
        if (hasHibernateEntityAnnotation(clazz)) {
            throw new IllegalArgumentException(String.format(
                    "Hibernate entity detected in flow attribute '%s'. "
                            + "Hibernate entities must not be stored in flow context. "
                            + "Extract primitive values or DTOs instead. Class: %s",
                    key, clazz.getName()));
        }

        // Check for JPA entity annotations
        if (hasJpaEntityAnnotation(clazz)) {
            throw new IllegalArgumentException(String.format(
                    "JPA entity detected in flow attribute '%s'. " + "JPA entities must not be stored in flow context. "
                            + "Extract primitive values or DTOs instead. Class: %s",
                    key, clazz.getName()));
        }
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
}
