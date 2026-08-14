package com.loadbalancer.circuit;

import com.loadbalancer.config.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-backend circuit breaker using a sliding window of failure timestamps.
 *
 * <p><b>State machine:</b>
 * <ul>
 *   <li><b>CLOSED</b> (normal): All requests flow through. Failures tracked in sliding window.</li>
 *   <li><b>OPEN</b> (tripped): All requests immediately rejected. After {@code recoveryTimeout},
 *       transitions to HALF_OPEN.</li>
 *   <li><b>HALF_OPEN</b> (probing): Exactly one probe request allowed through.
 *       If it succeeds → CLOSED. If it fails → OPEN.</li>
 * </ul>
 *
 * <p><b>Sliding window:</b> Uses a fixed-size ring buffer of {@code long} timestamps.
 * Only the most recent {@code failureThreshold} failures are stored. To check if the
 * threshold is reached, we count how many timestamps fall within the window.
 * No unbounded memory growth, no periodic resets needed.
 *
 * <p><b>Concurrency design:</b>
 * <ul>
 *   <li>{@code state} — {@code volatile} for cross-thread visibility (~1ns read)</li>
 *   <li>{@code failureTimestamps} — ring buffer indexed by {@link AtomicInteger}</li>
 *   <li>{@code probeInFlight} — {@link AtomicBoolean} CAS ensures exactly one probe</li>
 *   <li>{@code openedAt} — {@code volatile long}, set on trip, read on OPEN check</li>
 * </ul>
 *
 * <p><b>Happy path cost (CLOSED, no failures):</b> {@code allowRequest()} = 1 volatile read (~1ns).
 */
public class CircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

    /** Circuit breaker states. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String backendName;
    private final CircuitBreakerConfig config;

    // State machine — volatile for visibility across threads.
    // State transitions are effectively single-writer (only one thread "wins" the trip/reset).
    private volatile State state = State.CLOSED;

    // Sliding window: ring buffer of failure timestamps (epoch millis).
    // Fixed size = failureThreshold. No allocation after construction.
    private final long[] failureTimestamps;
    private final AtomicInteger failureIndex = new AtomicInteger(0);

    // When the circuit opened — used to calculate recovery timeout expiry.
    private volatile long openedAt = 0;

    // Half-open probe permit — only one request gets through in HALF_OPEN.
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);

    /**
     * Creates a new CircuitBreaker for the given backend.
     *
     * @param backendName Human-readable backend name (for logging)
     * @param config      Circuit breaker configuration
     */
    public CircuitBreaker(String backendName, CircuitBreakerConfig config) {
        this.backendName = backendName;
        this.config = config;
        this.failureTimestamps = new long[config.failureThreshold()];
    }

    /**
     * Returns the current circuit breaker state.
     */
    public State state() {
        return state;
    }

    /**
     * Read-only check: can this backend potentially accept requests?
     *
     * <p>Used by pool selection ({@code isAvailable()}) to decide whether a backend
     * is a candidate. Does NOT acquire the probe permit in HALF_OPEN — that's
     * done by {@link #allowRequest()} inside {@code forwardRequest()}.
     *
     * <p>This separation prevents the double-call bug: pool.next() calls
     * canAcceptTraffic() (no side effects), then forwardRequest() calls
     * allowRequest() (CAS for probe permit).
     *
     * @return true if the backend is a viable candidate for request routing
     */
    public boolean canAcceptTraffic() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> {
                // Check if recovery timeout has elapsed — if so, it's a candidate
                yield System.currentTimeMillis() - openedAt >= config.recoveryTimeout().toMillis();
            }
            case HALF_OPEN -> {
                // Only a candidate if no probe is already in flight
                yield !probeInFlight.get();
            }
        };
    }

    /**
     * Attempts to acquire permission to send a request through the circuit breaker.
     *
     * <p>Called inside {@code forwardRequest()}. In HALF_OPEN, uses CAS to ensure
     * exactly one thread gets the probe permit.
     *
     * <p>Cost by state:
     * <ul>
     *   <li>CLOSED: 1 volatile read (~1ns)</li>
     *   <li>OPEN: 1 volatile read + 1 timestamp comparison + state transition</li>
     *   <li>HALF_OPEN: 1 CAS (~5ns)</li>
     * </ul>
     *
     * @return true if the request should proceed, false if short-circuited
     */
    public boolean allowRequest() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> {
                // Check if recovery timeout has elapsed
                if (System.currentTimeMillis() - openedAt >= config.recoveryTimeout().toMillis()) {
                    state = State.HALF_OPEN;
                    logger.info("Circuit breaker HALF_OPEN: backend={}, allowing probe request",
                            backendName);
                    // Grant probe permit via CAS — exactly one thread wins
                    yield probeInFlight.compareAndSet(false, true);
                }
                yield false;
            }
            case HALF_OPEN -> {
                // Only one probe in flight at a time
                yield probeInFlight.compareAndSet(false, true);
            }
        };
    }

    /**
     * Records a successful request outcome.
     *
     * <p>In HALF_OPEN: probe succeeded → circuit closes.
     * In CLOSED: no action needed (we only track failures).
     */
    public void recordSuccess() {
        if (state == State.HALF_OPEN) {
            reset();
            logger.info("Circuit breaker CLOSED: backend={}, probe succeeded", backendName);
        }
    }

    /**
     * Records a failed request outcome (connection error or 5xx).
     *
     * <p>In HALF_OPEN: probe failed → circuit re-opens.
     * In CLOSED: add failure timestamp to sliding window, check threshold.
     */
    public void recordFailure() {
        if (state == State.HALF_OPEN) {
            trip();
            logger.warn("Circuit breaker re-OPENED: backend={}, probe failed", backendName);
            return;
        }

        if (state == State.CLOSED) {
            addFailureTimestamp(System.currentTimeMillis());
            if (countRecentFailures() >= config.failureThreshold()) {
                trip();
                logger.warn("Circuit breaker OPENED: backend={}, failures={} in {}s window",
                        backendName, config.failureThreshold(),
                        config.slidingWindow().toSeconds());
            }
        }
    }

    /**
     * Trips the circuit breaker to OPEN state.
     */
    private void trip() {
        openedAt = System.currentTimeMillis();
        probeInFlight.set(false);
        state = State.OPEN;
    }

    /**
     * Resets the circuit breaker to CLOSED state with an empty window.
     */
    void reset() {
        // Clear the failure window
        for (int i = 0; i < failureTimestamps.length; i++) {
            failureTimestamps[i] = 0;
        }
        failureIndex.set(0);
        probeInFlight.set(false);
        state = State.CLOSED;
    }

    /**
     * Adds a failure timestamp to the ring buffer.
     */
    private void addFailureTimestamp(long timestamp) {
        int idx = Math.floorMod(failureIndex.getAndIncrement(), failureTimestamps.length);
        failureTimestamps[idx] = timestamp;
    }

    /**
     * Counts failures within the sliding window.
     *
     * <p>Scans the ring buffer and counts timestamps that fall within
     * {@code [now - slidingWindow, now]}. Old failures outside the window
     * are naturally ignored — no periodic cleanup needed.
     */
    private int countRecentFailures() {
        long cutoff = System.currentTimeMillis() - config.slidingWindow().toMillis();
        int count = 0;
        for (long ts : failureTimestamps) {
            if (ts >= cutoff) {
                count++;
            }
        }
        return count;
    }
}
