package com.obsinity.collection.spring.processor;

import com.obsinity.collection.api.annotations.PushAttribute;
import com.obsinity.collection.api.annotations.PushContextValue;
import com.obsinity.collection.spring.validation.HibernateEntityDetector;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

/**
 * Extracts flow attributes and context values from method parameters annotated with
 * {@link PushAttribute} and {@link PushContextValue}.
 *
 * <p>This extractor is used by the {@link com.obsinity.collection.spring.aspect.FlowAspect}
 * to automatically capture parameter values and include them in flow telemetry events.
 *
 * <h2>Supported Annotations</h2>
 * <ul>
 *   <li>{@link PushAttribute} - Marks parameters to be included as flow attributes</li>
 *   <li>{@link PushContextValue} - Marks parameters to be included as flow context values</li>
 * </ul>
 *
 * <h2>Validation</h2>
 * <p>All extracted values are validated to prevent Hibernate/JPA entities from being stored
 * in flow context, which can cause:
 * <ul>
 *   <li>LazyInitializationException errors</li>
 *   <li>Memory leaks from retained object graphs</li>
 *   <li>Serialization failures</li>
 *   <li>ThreadLocal leaks via EntityManager references</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * @Flow(name = "user.update")
 * public void updateUser(
 *     @PushAttribute("user.id") Long userId,        // Included in attributes
 *     @PushContextValue("tenant.id") String tenant, // Included in context
 *     String otherParam                              // Not captured
 * ) {
 *     // Method implementation
 * }
 * }</pre>
 *
 * @see PushAttribute
 * @see PushContextValue
 * @see HibernateEntityDetector
 */
public final class AttributeParamExtractor {

    /**
     * Container for extracted attributes and context values.
     *
     * @param attributes Map of attribute keys to values (from {@link PushAttribute})
     * @param context Map of context keys to values (from {@link PushContextValue})
     */
    public record AttrCtx(Map<String, Object> attributes, Map<String, Object> context) {}

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private AttributeParamExtractor() {}

    /**
     * Extracts attributes and context values from the intercepted method's parameters.
     *
     * <p>This method scans all method parameters for {@link PushAttribute} and
     * {@link PushContextValue} annotations, validates the parameter values, and
     * collects them into separate maps.
     *
     * <h3>Processing Steps:</h3>
     * <ol>
     *   <li>Retrieve method arguments from the join point</li>
     *   <li>Return empty maps if no arguments present</li>
     *   <li>Extract method metadata (signature, annotations)</li>
     *   <li>Iterate through parameters and process annotations</li>
     *   <li>Validate values to prevent entity contamination</li>
     *   <li>Return collected attributes and context</li>
     * </ol>
     *
     * <h3>Thread Safety:</h3>
     * <p>This method is thread-safe as it creates new map instances for each invocation
     * and doesn't maintain any shared state.
     *
     * @param pjp the AspectJ proceeding join point containing method execution context
     * @return {@link AttrCtx} containing extracted attributes and context values (never null)
     * @throws IllegalArgumentException if any parameter value is a Hibernate/JPA entity
     *
     * @see #extractParameters(Object[], Annotation[][], Map, Map)
     */
    public static AttrCtx extract(ProceedingJoinPoint pjp) {
        // Initialize empty maps for attributes and context
        Map<String, Object> attrs = new LinkedHashMap<>();
        Map<String, Object> ctx = new LinkedHashMap<>();

        // Retrieve method arguments from the join point
        Object[] args = pjp.getArgs();

        // Early return if method has no arguments
        if (args == null || args.length == 0) {
            return new AttrCtx(attrs, ctx);
        }

        // Extract method metadata from the signature
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();

        // Process each parameter to extract annotated values
        extractParameters(args, paramAnnotations, attrs, ctx);

        return new AttrCtx(attrs, ctx);
    }

    /**
     * Iterates through method parameters and extracts annotated values.
     *
     * <p>This method coordinates the extraction process by iterating over all parameters
     * and delegating annotation processing to {@link #processParameter}.
     *
     * <h3>Parameter Matching:</h3>
     * <p>Uses {@code Math.min} to handle edge cases where the number of annotations
     * doesn't match the number of arguments (e.g., synthetic parameters, compiler-generated code).
     *
     * @param args the actual parameter values passed to the method
     * @param paramAnnotations 2D array of annotations for each parameter (outer: parameters, inner: annotations)
     * @param attrs mutable map to populate with extracted attributes
     * @param ctx mutable map to populate with extracted context values
     *
     * @see #processParameter(Object, Annotation[], Map, Map)
     */
    private static void extractParameters(
            Object[] args, Annotation[][] paramAnnotations, Map<String, Object> attrs, Map<String, Object> ctx) {

        // Safely determine the number of parameters to process
        // (handles mismatches between args and annotations)
        int paramCount = Math.min(paramAnnotations.length, args.length);

        // Process each parameter with its annotations
        for (int i = 0; i < paramCount; i++) {
            processParameter(args[i], paramAnnotations[i], attrs, ctx);
        }
    }

    /**
     * Processes annotations for a single parameter and extracts relevant values.
     *
     * <p>This method examines all annotations on a parameter, looking for
     * {@link PushAttribute} and {@link PushContextValue}. When found, it extracts
     * the annotation's key and delegates to {@link #addToMapIfValid} for validation
     * and storage.
     *
     * <h3>Annotation Handling:</h3>
     * <ul>
     *   <li>{@link PushAttribute} - Adds to attributes map</li>
     *   <li>{@link PushContextValue} - Adds to context map</li>
     *   <li>Other annotations - Ignored</li>
     * </ul>
     *
     * <h3>Pattern Matching:</h3>
     * <p>Uses Java's pattern matching for instanceof (JEP 394) to simplify
     * annotation type checking and extraction.
     *
     * @param arg the actual parameter value
     * @param annotations array of annotations present on this parameter
     * @param attrs mutable map to populate with attributes
     * @param ctx mutable map to populate with context values
     *
     * @see #addToMapIfValid(String, Object, Map)
     */
    private static void processParameter(
            Object arg, Annotation[] annotations, Map<String, Object> attrs, Map<String, Object> ctx) {

        // Examine each annotation on this parameter
        for (Annotation annotation : annotations) {
            // Check if annotation is @PushAttribute and extract the key
            if (annotation instanceof PushAttribute pa) {
                addToMapIfValid(pa.value(), arg, attrs);
            }
            // Check if annotation is @PushContextValue and extract the key
            else if (annotation instanceof PushContextValue pc) {
                addToMapIfValid(pc.value(), arg, ctx);
            }
            // Other annotations are silently ignored
        }
    }

    /**
     * Validates and adds a key-value pair to the target map if the key is valid.
     *
     * <p>This method performs three critical operations:
     * <ol>
     *   <li>Key validation - Ensures key is not null or blank</li>
     *   <li>Entity detection - Prevents Hibernate/JPA entities from being stored</li>
     *   <li>Storage - Adds the validated pair to the target map</li>
     * </ol>
     *
     * <h3>Key Validation:</h3>
     * <p>Keys must be non-null and non-blank (after trimming). This prevents:
     * <ul>
     *   <li>Null pointer exceptions in downstream processing</li>
     *   <li>Empty keys cluttering telemetry data</li>
     *   <li>Whitespace-only keys causing confusion</li>
     * </ul>
     *
     * <h3>Entity Detection:</h3>
     * <p>The {@link HibernateEntityDetector} checks for:
     * <ul>
     *   <li>Hibernate proxy classes (javassist, cglib patterns)</li>
     *   <li>JPA {@code @Entity} annotations (jakarta.persistence, javax.persistence)</li>
     *   <li>Hibernate-specific {@code @Entity} annotations</li>
     * </ul>
     *
     * <h3>Behavior:</h3>
     * <ul>
     *   <li>Valid key + Valid value → Added to map</li>
     *   <li>Invalid key (null/blank) → Silently ignored</li>
     *   <li>Valid key + Entity value → {@link IllegalArgumentException} thrown</li>
     * </ul>
     *
     * @param key the attribute or context key (from annotation value)
     * @param value the parameter value to validate and store
     * @param target the map to add the key-value pair to (attributes or context)
     * @throws IllegalArgumentException if value is a Hibernate/JPA entity
     *
     * @see HibernateEntityDetector#checkNotHibernateEntity(String, Object)
     */
    private static void addToMapIfValid(String key, Object value, Map<String, Object> target) {
        // Only process if key is valid (non-null and non-blank)
        if (key != null && !key.isBlank()) {
            // Validate that value is not a Hibernate/JPA entity
            // Throws IllegalArgumentException if entity detected
            HibernateEntityDetector.checkNotHibernateEntity(key, value);

            // Store the validated key-value pair
            target.put(key, value);
        }
        // Note: Invalid keys (null or blank) are silently ignored
    }
}
