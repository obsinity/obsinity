package com.obsinity.collection.spring.validation;

import com.obsinity.flow.validation.FlowAttributeValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Spring-managed component that logs errors instead of throwing exceptions when
 * Hibernate entities are detected in flow attributes/context.
 *
 * <p>This validator is used when {@code obsinity.collection.validation.hibernate-entity-check}
 * is set to {@code false}. It provides a migration path by detecting entities and logging
 * ERROR messages while allowing execution to continue.
 *
 * <h3>Behavior:</h3>
 * <ul>
 *   <li>Detects Hibernate/JPA entities (same logic as {@link HibernateEntityDetector})</li>
 *   <li>Logs ERROR message with full context</li>
 *   <li>Allows execution to continue (does not throw exception)</li>
 * </ul>
 *
 * <h3>Configuration:</h3>
 * <pre>{@code
 * # application.yml
 * obsinity:
 *   collection:
 *     validation:
 *       hibernate-entity-check: false  # Use logging mode instead of throwing
 * }</pre>
 *
 * @see FlowAttributeValidator
 * @see HibernateEntityDetector
 * @see org.springframework.stereotype.Component
 */
@Component
@ConditionalOnProperty(
        prefix = "obsinity.collection.validation",
        name = "hibernate-entity-check",
        havingValue = "false")
public class LoggingEntityDetector implements FlowAttributeValidator {

    private static final Logger log = LoggerFactory.getLogger(LoggingEntityDetector.class);

    /**
     * Default constructor for Spring dependency injection.
     * Empty by design - no initialization needed.
     */
    public LoggingEntityDetector() {
        // Empty constructor for Spring component scanning and dependency injection
    }

    @Override
    public void validate(String key, Object value) {
        try {
            HibernateEntityDetector.checkNotHibernateEntity(key, value);
        } catch (IllegalArgumentException e) {
            // Log error instead of throwing
            log.error(
                    "Hibernate entity detected in flow attribute '{}' but validation is disabled. "
                            + "This may cause LazyInitializationException, memory leaks, or serialization failures. {}",
                    key,
                    e.getMessage());
        }
    }
}
