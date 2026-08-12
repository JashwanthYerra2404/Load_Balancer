package com.loadbalancer.config;

/**
 * Configuration for a single upstream backend server.
 *
 * <p>Equivalent to Go's BackendConfig struct.
 * Each backend has a URL, human-readable name, traffic weight, and optional connection limit.
 *
 * @param url            Full URL including scheme (http:// or https://)
 * @param name           Unique human-readable identifier for logging and dashboards
 * @param weight         Traffic weight for weighted algorithms (default: 1, range: 1-100)
 * @param maxConnections Max concurrent connections (0 = unlimited)
 */
public record BackendConfig(
        String url,
        String name,
        int weight,
        int maxConnections
) {
    /**
     * Creates a BackendConfig with default weight if not specified.
     */
    public static BackendConfig withDefaults(String url, String name,
                                             Integer weight, Integer maxConnections) {
        return new BackendConfig(
                url,
                name,
                weight != null ? weight : 1,
                maxConnections != null ? maxConnections : 0
        );
    }
}
