package com.loadbalancer.config;

/**
 * Thrown when configuration validation fails.
 *
 * <p>Equivalent to Go's error wrapping with fmt.Errorf.
 * Contains a descriptive message listing all validation failures.
 */
public class ConfigValidationException extends RuntimeException {

    public ConfigValidationException(String message) {
        super(message);
    }

    public ConfigValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
