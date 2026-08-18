package com.loadbalancer.config;

/**
 * Configuration for per-client rate limiting.
 *
 * <p>When enabled, each client IP address gets a token bucket that
 * admits requests at {@code requestsPerSecond} sustained rate with a
 * burst of up to {@code burst} back-to-back requests. Requests beyond
 * the limit receive {@code 429 Too Many Requests} with a
 * {@code Retry-After} header.
 *
 * <p>Limits are enforced <b>per client</b>, not globally — one abusive
 * client cannot starve others.
 *
 * @param enabled           Whether rate limiting is active (default: false)
 * @param requestsPerSecond Sustained refill rate per client (default: 100)
 * @param burst             Bucket capacity — max back-to-back requests
 *                          a client can make after being idle (default: = rate)
 */
public record RateLimiterConfig(
        boolean enabled,
        int requestsPerSecond,
        int burst
) {
    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_REQUESTS_PER_SECOND = 100;

    /** Upper bound that keeps the packed token-bucket state within 31 bits. */
    public static final int MAX_RATE = 1_000_000;

    /**
     * Creates a RateLimiterConfig with defaults applied for any null fields.
     * The burst default equals the rate (a one-second burst window).
     */
    public static RateLimiterConfig withDefaults(Boolean enabled,
                                                 Integer requestsPerSecond,
                                                 Integer burst) {
        int rate = requestsPerSecond != null ? requestsPerSecond : DEFAULT_REQUESTS_PER_SECOND;
        return new RateLimiterConfig(
                enabled != null ? enabled : DEFAULT_ENABLED,
                rate,
                burst != null ? burst : rate
        );
    }
}
