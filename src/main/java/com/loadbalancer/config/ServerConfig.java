package com.loadbalancer.config;

import java.time.Duration;

/**
 * Server configuration — listening address and timeout behavior.
 *
 * <p>Java records are perfect for config: immutable, auto-generated equals/hashCode/toString.
 * This is equivalent to Go's ServerConfig struct with yaml tags.
 *
 * <p>Timeout rationale (same as Go version):
 * <ul>
 *   <li>readTimeout: limits request read time (prevents slow loris attacks)</li>
 *   <li>writeTimeout: limits response write time (prevents stuck connections)</li>
 *   <li>idleTimeout: limits keep-alive idle time</li>
 * </ul>
 */
public record ServerConfig(
        int port,
        Duration readTimeout,
        Duration writeTimeout,
        Duration idleTimeout
) {
    /** Default values — conservative, suitable for development. */
    public static final int DEFAULT_PORT = 8080;
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Creates a ServerConfig with defaults applied for any null/zero fields.
     */
    public static ServerConfig withDefaults(Integer port, Duration readTimeout,
                                            Duration writeTimeout, Duration idleTimeout) {
        return new ServerConfig(
                port != null && port != 0 ? port : DEFAULT_PORT,
                readTimeout != null ? readTimeout : DEFAULT_READ_TIMEOUT,
                writeTimeout != null ? writeTimeout : DEFAULT_WRITE_TIMEOUT,
                idleTimeout != null ? idleTimeout : DEFAULT_IDLE_TIMEOUT
        );
    }
}
