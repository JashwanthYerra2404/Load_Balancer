package com.loadbalancer.config;

import java.time.Duration;

/**
 * Configuration for the per-backend circuit breaker.
 *
 * <p>The circuit breaker monitors real-time request failures to a backend.
 * When failures exceed a threshold within a sliding time window, the circuit
 * "opens" and all requests are immediately rejected without attempting to
 * connect. After a recovery timeout, one probe request is allowed through.
 *
 * @param failureThreshold Number of failures in the sliding window to trip the circuit (default: 5)
 * @param slidingWindow    Time window for failure counting (default: 60s)
 * @param recoveryTimeout  Time in OPEN state before transitioning to HALF_OPEN (default: 30s)
 */
public record CircuitBreakerConfig(
        int failureThreshold,
        Duration slidingWindow,
        Duration recoveryTimeout
) {
    public static final int DEFAULT_FAILURE_THRESHOLD = 5;
    public static final Duration DEFAULT_SLIDING_WINDOW = Duration.ofSeconds(60);
    public static final Duration DEFAULT_RECOVERY_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Creates a CircuitBreakerConfig with defaults applied for any null/zero fields.
     */
    public static CircuitBreakerConfig withDefaults(Integer failureThreshold,
                                                     Duration slidingWindow,
                                                     Duration recoveryTimeout) {
        return new CircuitBreakerConfig(
                failureThreshold != null && failureThreshold > 0
                        ? failureThreshold : DEFAULT_FAILURE_THRESHOLD,
                slidingWindow != null ? slidingWindow : DEFAULT_SLIDING_WINDOW,
                recoveryTimeout != null ? recoveryTimeout : DEFAULT_RECOVERY_TIMEOUT
        );
    }
}
