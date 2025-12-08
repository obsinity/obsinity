package com.obsinity.collection.spring.autoconfigure;

import com.obsinity.collection.spring.validation.HibernateEntityLogLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Obsinity flow telemetry collection.
 *
 * <p>Controls global telemetry behavior including enabling/disabling collection
 * and validation settings.
 *
 * <h3>Configuration Example:</h3>
 * <pre>{@code
 * # application.yml
 * obsinity:
 *   collection:
 *     enabled: true  # Enable telemetry (default)
 *     validation:
 *       hibernate-entity-check-enabled: true   # Validate entities (default)
 *       hibernate-entity-check-log-level: ERROR # ERROR (throws), WARN, INFO
 * }</pre>
 *
 * @see org.springframework.boot.context.properties.ConfigurationProperties
 */
@ConfigurationProperties(prefix = "obsinity.collection")
public class ObsinityCollectionProperties {

    /**
     * Master switch to enable or disable all flow telemetry collection.
     *
     * <p>When disabled, {@link com.obsinity.collection.spring.aspect.FlowAspect}
     * bypasses all processing for {@code @Flow} and {@code @Step} annotations,
     * allowing methods to execute with minimal overhead.
     *
     * <h3>Behavior:</h3>
     * <ul>
     *   <li><b>Enabled (true):</b> Full telemetry collection with validation</li>
     *   <li><b>Disabled (false):</b> Methods proceed normally, zero telemetry overhead</li>
     * </ul>
     *
     * <h3>Use Cases:</h3>
     * <ul>
     *   <li>Performance testing baseline (disable telemetry)</li>
     *   <li>Emergency production kill switch</li>
     *   <li>Testing without telemetry dependencies</li>
     * </ul>
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * # Disable all telemetry
     * obsinity.collection.enabled=false
     * }</pre>
     *
     * @see com.obsinity.collection.spring.aspect.FlowAspect
     */
    private boolean enabled = true;

    private final Validation validation = new Validation();

    /**
     * Returns whether flow telemetry collection is enabled.
     *
     * @return {@code true} if telemetry is enabled (default), {@code false} otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether flow telemetry collection is enabled.
     *
     * @param enabled {@code true} to enable telemetry, {@code false} to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the validation configuration.
     *
     * @return validation settings
     */
    public Validation getValidation() {
        return validation;
    }

    /**
     * Validation configuration for flow attributes and context.
     */
    public static class Validation {

        /**
         * Enable Hibernate entity validation for flow attributes/context.
         */
        private boolean hibernateEntityCheckEnabled = true;

        /**
         * Controls how detections are reported when validation is enabled.
         * ERROR will throw, WARN/INFO log and continue.
         */
        private HibernateEntityLogLevel hibernateEntityCheckLogLevel = HibernateEntityLogLevel.ERROR;

        public boolean isHibernateEntityCheckEnabled() {
            return hibernateEntityCheckEnabled;
        }

        public void setHibernateEntityCheckEnabled(boolean hibernateEntityCheckEnabled) {
            this.hibernateEntityCheckEnabled = hibernateEntityCheckEnabled;
        }

        public HibernateEntityLogLevel getHibernateEntityCheckLogLevel() {
            return hibernateEntityCheckLogLevel;
        }

        public void setHibernateEntityCheckLogLevel(HibernateEntityLogLevel hibernateEntityCheckLogLevel) {
            this.hibernateEntityCheckLogLevel = hibernateEntityCheckLogLevel;
        }
    }
}
