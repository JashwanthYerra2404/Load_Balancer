package com.loadbalancer.config;

import java.util.List;
import java.util.Set;

/**
 * Top-level application configuration.
 *
 * <p>Equivalent to Go's Config struct. Holds server settings, backend list,
 * the load balancing algorithm name, health check configuration, and retry policy.
 *
 * @param server      Server listening configuration
 * @param backends    List of upstream backend servers
 * @param algorithm   Load balancing algorithm name
 * @param healthCheck Health check configuration
 * @param retry       Retry mechanism configuration
 */
public record AppConfig(
        ServerConfig server,
        List<BackendConfig> backends,
        String algorithm,
        HealthCheckConfig healthCheck,
        RetryConfig retry
) {
    /** Supported algorithm names — equivalent to Go's validAlgorithms map. */
    public static final String ALGORITHM_ROUND_ROBIN = "round_robin";
    public static final String ALGORITHM_LEAST_CONNECTIONS = "least_connections";
    public static final String ALGORITHM_WEIGHTED_ROUND_ROBIN = "weighted_round_robin";
    public static final String ALGORITHM_IP_HASH = "ip_hash";
    public static final String ALGORITHM_RANDOM = "random";

    public static final String DEFAULT_ALGORITHM = ALGORITHM_ROUND_ROBIN;

    public static final Set<String> VALID_ALGORITHMS = Set.of(
            ALGORITHM_ROUND_ROBIN,
            ALGORITHM_LEAST_CONNECTIONS,
            ALGORITHM_WEIGHTED_ROUND_ROBIN,
            ALGORITHM_IP_HASH,
            ALGORITHM_RANDOM
    );
}
