package com.obsinity.collection.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls the log level used when a {@link Step} is invoked without an active {@link Flow} and is therefore
 * auto-promoted to a Flow.
 *
 * <p>Apply to a Step method to override the default promotion log level.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OrphanAlert {

    /**
     * Log level for orphan step promotion. Kept as the primary value element for concise usage.
     */
    Level value() default Level.ERROR;

    /** Simple log level enum decoupled from specific logging frameworks. */
    enum Level {
        NONE,
        TRACE,
        ERROR,
        WARN,
        INFO,
        DEBUG
    }
}
