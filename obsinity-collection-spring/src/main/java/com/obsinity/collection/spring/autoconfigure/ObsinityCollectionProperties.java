package com.obsinity.collection.spring.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Obsinity collection.
 */
@ConfigurationProperties(prefix = "obsinity.collection")
public class ObsinityCollectionProperties {

    private final Validation validation = new Validation();

    public Validation getValidation() {
        return validation;
    }

    /**
     * Validation configuration for flow attributes and context.
     */
    public static class Validation {

        /**
         * Enable Hibernate entity detection in flow attributes and context.
         * When enabled, passing JPA/Hibernate entities to @PushAttribute or @PushContextValue
         * will throw an IllegalArgumentException.
         * <p>
         * Default: true
         * <p>
         * Set to false to disable validation if you need to pass entities (not recommended).
         */
        private boolean hibernateEntityCheck = true;

        public boolean isHibernateEntityCheck() {
            return hibernateEntityCheck;
        }

        public void setHibernateEntityCheck(boolean hibernateEntityCheck) {
            this.hibernateEntityCheck = hibernateEntityCheck;
        }
    }
}
