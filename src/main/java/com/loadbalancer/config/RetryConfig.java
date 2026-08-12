package com.loadbalancer.config;

import java.time.Duration;

/**
 * Configuration for the retry mechanism.
 *
 * <p>When a request to a backend fails (connection error or 5xx response),
 * the proxy automatically retries with a different backend. This configuration
 * controls the retry behavior.
 *
 * <p><b>Backoff formula:</b> {@code min(initialBackoff * 2^attempt, maxBackoff)}
 * with ±25% jitter to prevent thundering herd on retries.
 *
 * @param maxRetries     Max additional attempts after first failure (default: 2, total = 3)
 * @param initialBackoff Wait time before first retry (default: 100ms)
 * @param maxBackoff     Cap on exponential backoff (default: 1s)
 */
public record RetryConfig(
        int maxRetries,
        Duration initialBackoff,
        Duration maxBackoff
) {
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(100);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(1);

    /**
     * Creates a RetryConfig with defaults applied for any null/zero fields.
     */
    public static RetryConfig withDefaults(Integer maxRetries, Duration initialBackoff,
                                            Duration maxBackoff) {
        return new RetryConfig(
                maxRetries != null && maxRetries >= 0 ? maxRetries : DEFAULT_MAX_RETRIES,
                initialBackoff != null ? initialBackoff : DEFAULT_INITIAL_BACKOFF,
                maxBackoff != null ? maxBackoff : DEFAULT_MAX_BACKOFF
        );
    }
}
