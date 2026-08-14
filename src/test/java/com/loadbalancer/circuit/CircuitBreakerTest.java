package com.loadbalancer.circuit;

import com.loadbalancer.config.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CircuitBreaker} state machine.
 *
 * <p>Uses short durations (ms) to keep tests fast.
 */
class CircuitBreakerTest {

    /** Fast config: trips after 3 failures in 1s, recovers after 50ms. */
    private static final CircuitBreakerConfig FAST_CONFIG = new CircuitBreakerConfig(
            3, Duration.ofSeconds(1), Duration.ofMillis(50)
    );

    /** Aggressive config: trips after 2 failures in 500ms. */
    private static final CircuitBreakerConfig AGGRESSIVE_CONFIG = new CircuitBreakerConfig(
            2, Duration.ofMillis(500), Duration.ofMillis(30)
    );

    @Test
    void startsInClosedState() {
        CircuitBreaker cb = new CircuitBreaker("test", FAST_CONFIG);
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowRequest(), "CLOSED circuit should allow requests");
    }

    @Test
    void allowsRequestsInClosedState() {
        CircuitBreaker cb = new CircuitBreaker("test", FAST_CONFIG);

        // Should allow many requests in CLOSED state
        for (int i = 0; i < 100; i++) {
            assertTrue(cb.allowRequest());
        }
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void doesNotTripBelowThreshold() {
        CircuitBreaker cb = new CircuitBreaker("test", FAST_CONFIG);

        // 2 failures (threshold is 3) — should stay CLOSED
        cb.recordFailure();
        cb.recordFailure();

        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowRequest());
    }

    @Test
    void tripsAfterThresholdReached() {
        CircuitBreaker cb = new CircuitBreaker("test", FAST_CONFIG);

        // Record exactly threshold failures
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();

        assertEquals(CircuitBreaker.State.OPEN, cb.state());
    }

    @Test
    void rejectsRequestsWhenOpen() {
        CircuitBreaker cb = new CircuitBreaker("test", FAST_CONFIG);

        // Trip the circuit
        for (int i = 0; i < 3; i++) cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());

        // All requests should be rejected
        assertFalse(cb.allowRequest());
        assertFalse(cb.allowRequest());
        assertFalse(cb.allowRequest());
    }

    @Test
    void transitionsToHalfOpenAfterRecoveryTimeout() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker("test", FAST_CONFIG);

        // Trip the circuit
        for (int i = 0; i < 3; i++) cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());

        // Wait for recovery timeout (50ms + buffer)
        Thread.sleep(80);

        // Next allowRequest() should transition to HALF_OPEN and allow a probe
        assertTrue(cb.allowRequest());
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
    }

    @Test
    void closesOnHalfOpenSuccess() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker("test", FAST_CONFIG);

        // Trip → OPEN → wait → HALF_OPEN
        for (int i = 0; i < 3; i++) cb.recordFailure();
        Thread.sleep(80);
        assertTrue(cb.allowRequest()); // transitions to HALF_OPEN

        // Probe succeeds → should close
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowRequest());
    }

    @Test
    void reopensOnHalfOpenFailure() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker("test", FAST_CONFIG);

        // Trip → OPEN → wait → HALF_OPEN
        for (int i = 0; i < 3; i++) cb.recordFailure();
        Thread.sleep(80);
        assertTrue(cb.allowRequest()); // transitions to HALF_OPEN

        // Probe fails → should reopen
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.allowRequest()); // still OPEN, timeout not elapsed
    }

    @Test
    void onlyOneProbeAllowedInHalfOpen() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker("test", FAST_CONFIG);

        // Trip → OPEN → wait → HALF_OPEN
        for (int i = 0; i < 3; i++) cb.recordFailure();
        Thread.sleep(80);

        // First call gets the probe permit
        assertTrue(cb.allowRequest());
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

        // Subsequent calls should be rejected (probe already in flight)
        assertFalse(cb.allowRequest());
        assertFalse(cb.allowRequest());
    }

    @Test
    void concurrentProbePermitIsExclusive() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker("test", FAST_CONFIG);

        // Trip the circuit
        for (int i = 0; i < 3; i++) cb.recordFailure();
        Thread.sleep(80);

        // Race N threads to get the probe permit
        int threadCount = 20;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        AtomicInteger allowed = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await(); // all start at the same time
                    if (cb.allowRequest()) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown(); // release all threads
        doneGate.await();
        executor.shutdown();

        // Exactly one thread should have gotten the probe permit
        assertEquals(1, allowed.get(),
                "Only 1 thread should win the probe permit via CAS");
    }

    @Test
    void slidingWindowExpiresOldFailures() throws InterruptedException {
        // Window is 500ms — failures older than that don't count
        CircuitBreaker cb = new CircuitBreaker("test", AGGRESSIVE_CONFIG);

        // Record 1 failure
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());

        // Wait for the failure to expire out of the window
        Thread.sleep(600);

        // Record 1 more failure — total in window is 1 (not 2), should NOT trip
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state(),
                "Old failure should have expired from the sliding window");
    }

    @Test
    void resetClearsState() {
        CircuitBreaker cb = new CircuitBreaker("test", FAST_CONFIG);

        // Trip the circuit
        for (int i = 0; i < 3; i++) cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());

        // Reset
        cb.reset();

        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowRequest());
    }

    @Test
    void successInClosedStateDoesNothing() {
        CircuitBreaker cb = new CircuitBreaker("test", FAST_CONFIG);

        // Multiple successes in CLOSED state — should be a no-op
        cb.recordSuccess();
        cb.recordSuccess();
        cb.recordSuccess();

        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowRequest());
    }

    @Test
    void failureAfterRecoveryTripsAgain() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker("test", FAST_CONFIG);

        // Trip → recover → trip again
        for (int i = 0; i < 3; i++) cb.recordFailure();
        Thread.sleep(80);
        assertTrue(cb.allowRequest()); // HALF_OPEN
        cb.recordSuccess(); // CLOSED

        // Now fail again to trip
        for (int i = 0; i < 3; i++) cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
    }

    @Test
    void fullLifecycle() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker("test", FAST_CONFIG);

        // 1. Start CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());

        // 2. Trip to OPEN
        for (int i = 0; i < 3; i++) cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.allowRequest());

        // 3. Wait for recovery timeout → HALF_OPEN
        Thread.sleep(80);
        assertTrue(cb.allowRequest());
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

        // 4. Probe fails → back to OPEN
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());

        // 5. Wait again → HALF_OPEN
        Thread.sleep(80);
        assertTrue(cb.allowRequest());
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

        // 6. Probe succeeds → CLOSED
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowRequest());
    }
}
