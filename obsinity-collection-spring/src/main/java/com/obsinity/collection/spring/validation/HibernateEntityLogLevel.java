package com.obsinity.collection.spring.validation;

/**
 * Log level used when reporting Hibernate entity detection.
 *
 * <ul>
 *   <li>{@link #ERROR} - log at ERROR and throw an IllegalArgumentException</li>
 *   <li>{@link #WARN} - log at WARN and continue</li>
 *   <li>{@link #INFO} - log at INFO and continue</li>
 * </ul>
 */
public enum HibernateEntityLogLevel {
    ERROR,
    WARN,
    INFO
}
