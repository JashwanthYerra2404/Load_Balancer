package com.loadbalancer.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TokenBucketRateLimiter} using a controllable fake clock.
 *
 * <p>The fake clock advances in explicit steps so refill behavior is
 * deterministic — no sleeps, no flakiness.
 */
class TokenBucketRateLimiterTest {

    /** Fake monotonic clock — callers advance time explicitly. */
    private static final class FakeClock implements java.util.function.LongSupplier {
        final AtomicLong nanos = new AtomicLong();

        @Override
        public long getAsLong() {
            return nanos.get();
        }

        /** Advances the clock by the given number of 100ms ticks. */
        void advanceTicks(long ticks) {
            nanos.addAndGet(ticks * TokenBucketRateLimiter.TICK_NANOS);
        }

        void advanceSeconds(long seconds) {
            advanceTicks(seconds * 10);
        }
    }

    private FakeClock clock;
    private TokenBucketRateLimiter limiter;

    private void createLimiter(int ratePerSecond, int burst) {
        clock = new FakeClock();
        limiter = new TokenBucketRateLimiter(ratePerSecond, burst, clock);
    }

    @Test
    void initialBurstAllowedUpToCapacity() {
        createLimiter(10, 5);

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("1.2.3.4"), "Request " + i + " within burst should pass");
        }
        assertFalse(limiter.tryAcquire("1.2.3.4"), "6th immediate request exceeds burst");
    }

    @Test
    void tokensRefillOverTime() {
        createLimiter(10, 5);

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("client"));
        }
        assertFalse(limiter.tryAcquire("client"));

        // Advance 1 second → 10 tokens accrue, capped at capacity 5
        clock.advanceSeconds(1);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("client"));
        }
        assertFalse(limiter.tryAcquire("client"));
    }

    @Test
    void refillIsCappedAtCapacity() {
        createLimiter(5, 3);

        // Drain the bucket
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("c"));
        }

        // Idle for an hour — bucket must not exceed capacity
        clock.advanceSeconds(3600);
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("c"));
        }
        assertFalse(limiter.tryAcquire("c"));
    }

    @Test
    void fractionalRefillAccruesForSlowRates() {
        // Rate 1/s refills 0.1 token per 100ms tick — after 1s exactly 1 token
        createLimiter(1, 1);

        assertTrue(limiter.tryAcquire("slow"));
        assertFalse(limiter.tryAcquire("slow"));

        // Half a second — not yet a full token
        clock.advanceTicks(5);
        assertFalse(limiter.tryAcquire("slow"), "0.5s at 1/s should not yet yield a token");

        // A full second — one token
        clock.advanceTicks(5);
        assertTrue(limiter.tryAcquire("slow"));
    }

    @Test
    void denyDoesNotStopRefillAccrual() {
        // The deny path must not write state — otherwise denied clients
        // would lose refill time accrued while being limited
        createLimiter(10, 2);

        assertTrue(limiter.tryAcquire("c"));
        assertTrue(limiter.tryAcquire("c"));
        assertFalse(limiter.tryAcquire("c")); // empty — deny leaves state untouched

        clock.advanceSeconds(1); // → 2 tokens refilled (capped at capacity)

        assertTrue(limiter.tryAcquire("c"));
        assertTrue(limiter.tryAcquire("c"));
        assertFalse(limiter.tryAcquire("c"));
    }

    @Test
    void clientsAreIsolated() {
        createLimiter(10, 3);

        // Drain client A
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("10.0.0.1"));
        }
        assertFalse(limiter.tryAcquire("10.0.0.1"));

        // Client B unaffected
        assertTrue(limiter.tryAcquire("10.0.0.2"));
        assertEquals(2, limiter.trackedClients());
    }

    @Test
    void sustainedRateAdmitsOverTime() {
        // A client sending exactly at the sustained rate should never
        // be limited: spend 1, wait long enough for 1 token back
        createLimiter(10, 2);

        assertTrue(limiter.tryAcquire("steady"));
        for (int round = 0; round < 20; round++) {
            clock.advanceTicks(1); // 100ms → 1 token at 10/s
            assertTrue(limiter.tryAcquire("steady"), "Round " + round);
        }
    }

    @Test
    void retryAfterMillisPositiveWhenLimited() {
        createLimiter(10, 1);

        assertTrue(limiter.tryAcquire("c"));
        assertFalse(limiter.tryAcquire("c"));

        long retry = limiter.retryAfterMillis("c");
        assertTrue(retry >= 100, "Retry-After should be at least one tick, got " + retry);

        // Unknown client is not limited
        assertEquals(0, limiter.retryAfterMillis("nobody"));

        // After refill, no wait needed
        clock.advanceSeconds(1);
        assertEquals(0, limiter.retryAfterMillis("c"));
    }

    @Test
    void retryAfterMillisAlignedToTicks() {
        createLimiter(100, 1);

        assertTrue(limiter.tryAcquire("c"));
        assertFalse(limiter.tryAcquire("c"));

        // At 100/s the exact wait is 10ms; must round up to a whole tick (100ms)
        assertEquals(100, limiter.retryAfterMillis("c"));
    }

    @Test
    void burstIndependentOfRate() {
        // Big burst, low rate
        createLimiter(1, 10);

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("c"));
        }
        assertFalse(limiter.tryAcquire("c"));
    }

    @Test
    void invalidConfigRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(2_000_000, 10));
    }

    @Test
    void concurrentAcquiresNeverExceedCapacityPlusRefill() throws Exception {
        createLimiter(1000, 50);

        int threads = 8;
        int perThread = 1000;
        AtomicInteger admitted = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        if (limiter.tryAcquire("shared")) {
                            admitted.incrementAndGet();
                        }
                    }
                });
            }
            start.countDown();

            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        // No time advances during the run: admissions must equal capacity exactly.
        // If the CAS packing were racy, over-admission would show up here.
        assertEquals(50, admitted.get(), "Concurrent admits must equal burst capacity");
    }
}
