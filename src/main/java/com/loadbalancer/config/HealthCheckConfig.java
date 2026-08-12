package com.loadbalancer.config;

import java.time.Duration;

/**
 * Configuration for the background health checker.
 *
 * <p>The health checker periodically probes each backend to determine
 * if it's healthy. This configuration controls the probe behavior.
 *
 * @param interval          Time between health check probes (default: 10s)
 * @param timeout           Max time to wait for a health check response (default: 5s)
 * @param path              HTTP path to probe (default: "/health")
 * @param failureThreshold  Consecutive failures before marking dead (default: 3)
 * @param successThreshold  Consecutive successes before marking alive (default: 1)
 */
public record HealthCheckConfig(
        Duration interval,
        Duration timeout,
        String path,
        int failureThreshold,
        int successThreshold
) {
    /** Default values for health checking. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(10);
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    public static final String DEFAULT_PATH = "/health";
    public static final int DEFAULT_FAILURE_THRESHOLD = 3;
    public static final int DEFAULT_SUCCESS_THRESHOLD = 1;

    /**
     * Creates a HealthCheckConfig with defaults applied for any null/zero fields.
     */
    public static HealthCheckConfig withDefaults(Duration interval, Duration timeout,
                                                  String path, Integer failureThreshold,
                                                  Integer successThreshold) {
        return new HealthCheckConfig(
                interval != null ? interval : DEFAULT_INTERVAL,
                timeout != null ? timeout : DEFAULT_TIMEOUT,
                path != null && !path.isEmpty() ? path : DEFAULT_PATH,
                failureThreshold != null && failureThreshold > 0 ? failureThreshold : DEFAULT_FAILURE_THRESHOLD,
                successThreshold != null && successThreshold > 0 ? successThreshold : DEFAULT_SUCCESS_THRESHOLD
        );
    }
}
