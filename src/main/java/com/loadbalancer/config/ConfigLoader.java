package com.loadbalancer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Loads, parses, and validates YAML configuration files.
 *
 * <p>Equivalent to Go's config.Load() function. Uses SnakeYAML for parsing
 * (equivalent to gopkg.in/yaml.v3).
 *
 * <p>Design: SnakeYAML parses YAML into raw {@code Map<String, Object>} structures.
 * We manually extract fields and apply defaults, which gives us full control over
 * validation and error messages — important for operator experience.
 *
 * <p>Alternative considered: Jackson YAML module with auto-mapping to POJOs.
 * Rejected because it hides validation logic and produces cryptic error messages.
 * Explicit extraction is more code but produces much better error diagnostics.
 */
public final class ConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);

    private ConfigLoader() {
        // Utility class — no instantiation
    }

    /**
     * Loads configuration from a YAML file path.
     *
     * @param path Path to the YAML configuration file
     * @return Validated AppConfig
     * @throws ConfigValidationException if validation fails
     * @throws IOException               if the file cannot be read
     */
    public static AppConfig load(String path) throws IOException {
        logger.debug("Loading configuration from {}", path);

        Map<String, Object> raw;
        try (InputStream is = Files.newInputStream(Path.of(path))) {
            Yaml yaml = new Yaml();
            raw = yaml.load(is);
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            throw new ConfigValidationException("Failed to parse YAML configuration: " + e.getMessage(), e);
        }

        if (raw == null) {
            throw new ConfigValidationException("Configuration file is empty: " + path);
        }

        AppConfig config = parse(raw);
        validate(config);
        return config;
    }

    /**
     * Parses raw YAML map into an AppConfig, applying defaults for missing values.
     */
    @SuppressWarnings("unchecked")
    static AppConfig parse(Map<String, Object> raw) {
        // Parse server config
        Map<String, Object> serverMap = (Map<String, Object>) raw.getOrDefault("server", Map.of());
        ServerConfig server = ServerConfig.withDefaults(
                getInteger(serverMap, "port"),
                parseDuration(serverMap, "read_timeout"),
                parseDuration(serverMap, "write_timeout"),
                parseDuration(serverMap, "idle_timeout")
        );

        // Parse backends
        List<Map<String, Object>> backendsList =
                (List<Map<String, Object>>) raw.getOrDefault("backends", List.of());

        List<BackendConfig> backends = new ArrayList<>();
        for (Map<String, Object> bMap : backendsList) {
            backends.add(BackendConfig.withDefaults(
                    getString(bMap, "url"),
                    getString(bMap, "name"),
                    getInteger(bMap, "weight"),
                    getInteger(bMap, "max_connections")
            ));
        }

        // Parse algorithm with default
        String algorithm = getString(raw, "algorithm");
        if (algorithm == null || algorithm.isEmpty()) {
            algorithm = AppConfig.DEFAULT_ALGORITHM;
        }

        // Parse health check config
        Map<String, Object> healthMap =
                (Map<String, Object>) raw.getOrDefault("health_check", Map.of());
        HealthCheckConfig healthCheck = HealthCheckConfig.withDefaults(
                parseDuration(healthMap, "interval"),
                parseDuration(healthMap, "timeout"),
                getString(healthMap, "path"),
                getInteger(healthMap, "failure_threshold"),
                getInteger(healthMap, "success_threshold")
        );

        // Parse retry config
        Map<String, Object> retryMap =
                (Map<String, Object>) raw.getOrDefault("retry", Map.of());
        RetryConfig retry = RetryConfig.withDefaults(
                getInteger(retryMap, "max_retries"),
                parseDuration(retryMap, "initial_backoff"),
                parseDuration(retryMap, "max_backoff")
        );

        // Parse circuit breaker config
        Map<String, Object> cbMap =
                (Map<String, Object>) raw.getOrDefault("circuit_breaker", Map.of());
        CircuitBreakerConfig circuitBreaker = CircuitBreakerConfig.withDefaults(
                getInteger(cbMap, "failure_threshold"),
                parseDuration(cbMap, "sliding_window"),
                parseDuration(cbMap, "recovery_timeout")
        );

        // Parse sticky session config
        Map<String, Object> ssMap =
                (Map<String, Object>) raw.getOrDefault("sticky_session", Map.of());
        StickySessionConfig stickySession = StickySessionConfig.withDefaults(
                getBoolean(ssMap, "enabled"),
                getString(ssMap, "cookie_name"),
                parseDuration(ssMap, "ttl"),
                getBoolean(ssMap, "http_only"),
                getBoolean(ssMap, "secure")
        );

        // Parse rate limiter config
        Map<String, Object> rlMap =
                (Map<String, Object>) raw.getOrDefault("rate_limit", Map.of());
        RateLimiterConfig rateLimit = RateLimiterConfig.withDefaults(
                getBoolean(rlMap, "enabled"),
                getInteger(rlMap, "requests_per_second"),
                getInteger(rlMap, "burst")
        );

        return new AppConfig(server, List.copyOf(backends), algorithm, healthCheck, retry,
                circuitBreaker, stickySession, rateLimit);
    }

    /**
     * Validates the configuration strictly. Fails fast on invalid config rather
     * than falling back to defaults silently.
     *
     * <p>Equivalent to Go's validate() function — collects all errors and reports
     * them together.
     */
    static void validate(AppConfig config) {
        List<String> errors = new ArrayList<>();

        // Validate port range
        if (config.server().port() < 1 || config.server().port() > 65535) {
            errors.add("server.port must be between 1 and 65535, got " + config.server().port());
        }

        // Validate at least one backend
        if (config.backends().isEmpty()) {
            errors.add("at least one backend is required");
        }

        // Validate each backend and check for duplicate names and URLs
        Set<String> seenNames = new HashSet<>();
        Set<String> seenUrls = new HashSet<>();
        for (int i = 0; i < config.backends().size(); i++) {
            BackendConfig b = config.backends().get(i);
            String prefix = "backends[" + i + "]";

            // Validate URL
            if (b.url() == null || b.url().isEmpty()) {
                errors.add(prefix + ".url is required");
            } else {
                if (seenUrls.contains(b.url())) {
                    errors.add(prefix + ".url \"" + b.url() + "\" is duplicate");
                } else {
                    seenUrls.add(b.url());
                }
                
                try {
                    URI uri = URI.create(b.url());
                    String scheme = uri.getScheme();
                    if (scheme == null || (!"http".equals(scheme) && !"https".equals(scheme))) {
                        errors.add(prefix + ".url scheme must be http or https, got \"" + scheme + "\"");
                    } else if (uri.getHost() == null || uri.getHost().isEmpty()) {
                        errors.add(prefix + ".url must include a host");
                    }
                } catch (IllegalArgumentException e) {
                    errors.add(prefix + ".url is not a valid URL: " + e.getMessage());
                }
            }

            // Validate name
            if (b.name() == null || b.name().isEmpty()) {
                errors.add(prefix + ".name is required");
            } else if (seenNames.contains(b.name())) {
                errors.add(prefix + ".name \"" + b.name() + "\" is duplicate");
            } else {
                seenNames.add(b.name());
            }

            // Validate weight
            if (b.weight() < 0) {
                errors.add(prefix + ".weight must be non-negative, got " + b.weight());
            }

            // Validate max_connections
            if (b.maxConnections() < 0) {
                errors.add(prefix + ".max_connections must be non-negative, got " + b.maxConnections());
            }
        }

        // Validate algorithm
        if (!AppConfig.VALID_ALGORITHMS.contains(config.algorithm())) {
            errors.add("algorithm \"" + config.algorithm() + "\" is not supported (valid: " +
                    String.join(", ", AppConfig.VALID_ALGORITHMS) + ")");
        }

        // Validate timeouts
        if (config.server().readTimeout().isNegative()) {
            errors.add("server.read_timeout must be positive, got " + config.server().readTimeout());
        }
        if (config.server().writeTimeout().isNegative()) {
            errors.add("server.write_timeout must be positive, got " + config.server().writeTimeout());
        }
        if (config.server().idleTimeout().isNegative()) {
            errors.add("server.idle_timeout must be positive, got " + config.server().idleTimeout());
        }

        // Validate rate limiter — strict even when disabled so a bad value
        // fails at startup rather than when someone flips enabled
        RateLimiterConfig rl = config.rateLimit();
        if (rl.requestsPerSecond() < 1 || rl.requestsPerSecond() > RateLimiterConfig.MAX_RATE) {
            errors.add("rate_limit.requests_per_second must be between 1 and "
                    + RateLimiterConfig.MAX_RATE + ", got " + rl.requestsPerSecond());
        }
        if (rl.burst() < 1 || rl.burst() > RateLimiterConfig.MAX_RATE) {
            errors.add("rate_limit.burst must be between 1 and "
                    + RateLimiterConfig.MAX_RATE + ", got " + rl.burst());
        }

        if (!errors.isEmpty()) {
            throw new ConfigValidationException(
                    "Configuration validation failed:\n  - " + String.join("\n  - ", errors));
        }
    }

    // --- Helper methods for safe YAML value extraction ---

    private static String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private static Integer getInteger(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Parses a duration string from YAML. Supports Go-style durations like "15s", "5m", "1h".
     * Also supports plain integer seconds for backward compatibility.
     */
    private static Duration parseDuration(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }

        String str = value.toString().trim();
        if (str.isEmpty()) {
            return null;
        }

        // Parse Go-style duration: "15s", "5m", "1h", "500ms"
        if (str.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(str.substring(0, str.length() - 2)));
        } else if (str.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(str.substring(0, str.length() - 1)));
        } else if (str.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(str.substring(0, str.length() - 1)));
        } else if (str.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(str.substring(0, str.length() - 1)));
        }

        // Fallback: treat as seconds
        try {
            return Duration.ofSeconds(Long.parseLong(str));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean getBoolean(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return null;
    }
}
