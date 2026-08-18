package com.loadbalancer.config;

import java.time.Duration;

/**
 * Configuration for cookie-based sticky sessions.
 *
 * <p>When enabled, the load balancer injects a cookie that pins a client
 * to a specific backend. Subsequent requests with that cookie bypass the
 * load balancing algorithm and go directly to the pinned backend.
 *
 * <p>The cookie TTL acts as a sliding window — it's refreshed on every
 * response, so the cookie only expires after {@code ttl} of complete inactivity.
 *
 * @param enabled    Whether sticky sessions are active (default: false)
 * @param cookieName Name of the affinity cookie (default: "LB_BACKEND")
 * @param ttl        Cookie max-age; Duration.ZERO = session cookie (default: 1h)
 * @param httpOnly   HttpOnly flag — prevents JavaScript access (default: true)
 * @param secure     Secure flag — cookie only sent over HTTPS (default: false)
 */
public record StickySessionConfig(
        boolean enabled,
        String cookieName,
        Duration ttl,
        boolean httpOnly,
        boolean secure
) {
    public static final boolean DEFAULT_ENABLED = false;
    public static final String DEFAULT_COOKIE_NAME = "LB_BACKEND";
    public static final Duration DEFAULT_TTL = Duration.ofHours(1);
    public static final boolean DEFAULT_HTTP_ONLY = true;
    public static final boolean DEFAULT_SECURE = false;

    /**
     * Creates a StickySessionConfig with defaults applied for any null fields.
     */
    public static StickySessionConfig withDefaults(Boolean enabled,
                                                    String cookieName,
                                                    Duration ttl,
                                                    Boolean httpOnly,
                                                    Boolean secure) {
        return new StickySessionConfig(
                enabled != null ? enabled : DEFAULT_ENABLED,
                cookieName != null && !cookieName.isEmpty() ? cookieName : DEFAULT_COOKIE_NAME,
                ttl != null ? ttl : DEFAULT_TTL,
                httpOnly != null ? httpOnly : DEFAULT_HTTP_ONLY,
                secure != null ? secure : DEFAULT_SECURE
        );
    }

    /**
     * Builds the Set-Cookie header value for the given backend name.
     *
     * <p>Format: {@code LB_BACKEND=backend-1; Path=/; Max-Age=3600; HttpOnly}
     *
     * @param backendName the backend to pin the client to
     * @return the complete Set-Cookie header value
     */
    public String buildCookieHeader(String backendName) {
        StringBuilder sb = new StringBuilder();
        sb.append(cookieName).append('=').append(backendName);
        sb.append("; Path=/");

        if (!ttl.isZero()) {
            sb.append("; Max-Age=").append(ttl.toSeconds());
        }

        if (httpOnly) {
            sb.append("; HttpOnly");
        }

        if (secure) {
            sb.append("; Secure");
        }

        return sb.toString();
    }
}
