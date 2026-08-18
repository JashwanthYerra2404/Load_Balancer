package com.loadbalancer.ratelimit;

/**
 * A rate limiter that decides whether a client's request should be
 * admitted or rejected ({@code 429 Too Many Requests}).
 */
public interface RateLimiter {

    /**
     * Attempts to admit one request for the given client.
     *
     * @param clientId stable client identifier (typically the client IP)
     * @return true if the request is admitted, false if the client's
     *         limit is exhausted
     */
    boolean tryAcquire(String clientId);

    /**
     * Estimates how long the given client should wait before retrying.
     *
     * @param clientId the client identifier
     * @return wait time in milliseconds (always &gt;= 1 when limited)
     */
    long retryAfterMillis(String clientId);
}
