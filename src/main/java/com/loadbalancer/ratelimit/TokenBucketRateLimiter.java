package com.loadbalancer.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-client token bucket rate limiter — lock-free, O(1) admit, O(1) memory per client.
 *
 * <p><b>Why a token bucket?</b> It is the only classic algorithm that offers all
 * three properties a proxy hot path needs: constant time/memory, controlled burst
 * tolerance, and a smooth average rate. See docs/phase9.md for the full comparison
 * against fixed window, sliding window log/counter, and leaky bucket.
 *
 * <h2>Design: packed-state lock-free bucket</h2>
 *
 * Each client's bucket is a single {@link AtomicLong} packing two fields:
 *
 * <pre>{@code
 * 63                    32 31                     0
 * +-----------------------+-----------------------+
 * |  tokens (milliTokens) |  last refill tick     |
 * +-----------------------+-----------------------+
 * }</pre>
 *
 * Packing both fields into one word means a refill + consume is a single
 * compare-and-set — no lock, no two-phase update that could race, and no
 * object allocation on the steady-state path.
 *
 * <ul>
 *   <li><b>Tokens are stored as milliTokens</b> (tokens × 1000) so fractional
 *       refill accrues without floating point in the CAS loop.</li>
 *   <li><b>Refill is lazy.</b> No background thread refills buckets; the refill
 *       math runs on read, from the elapsed tick delta. Buckets for idle clients
 *       cost nothing.</li>
 *   <li><b>Ticks are 100ms</b> ({@link #TICK_NANOS}), computed from a
 *       {@link LongSupplier} clock (injectable for tests). Tick deltas use
 *       unsigned 32-bit arithmetic, so the tick counter is wrap-safe.</li>
 * </ul>
 *
 * <h2>Deny path</h2>
 *
 * When the bucket is empty the state is left untouched — the refill continues
 * accruing from the original tick, so a denied client's bucket still fills up.
 * Denying is therefore a lock-free read with zero writes (no CAS contention
 * against clients that are being limited).
 *
 * <h2>Memory: lazy eviction</h2>
 *
 * Client buckets could otherwise grow unboundedly (DDoS with spoofed IPs).
 * Every {@link #CLEANUP_INTERVAL} operations, buckets idle for more than
 * {@link #MAX_IDLE_TICKS} ticks are evicted during the next sweep. A swept
 * client simply starts fresh with a full bucket — safe, because eviction only
 * removes state, never admits anything extra mid-window.
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    /** 100ms per tick — 32 bits of ticks covers 13.6 years of uptime. */
    static final long TICK_NANOS = 100_000_000L;

    /** One full token in milliToken units. */
    static final long MILLI_PER_TOKEN = 1000;

    private static final long TICK_MASK = 0xFFFFFFFFL;
    private static final int CLEANUP_INTERVAL = 1024;

    /** Idle threshold before a bucket is evicted: ~10 minutes. */
    private static final long MAX_IDLE_TICKS = 6000;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final long capacityMilli;
    private final long refillPerTickMilli;
    private final long refillPerSecondMilli;
    private final long startNanos;
    private final LongSupplier clock;

    private final AtomicLong opCounter = new AtomicLong();

    /**
     * Creates a limiter using the system monotonic clock.
     *
     * @param requestsPerSecond sustained rate per client
     * @param burst             bucket capacity (max back-to-back burst)
     */
    public TokenBucketRateLimiter(int requestsPerSecond, int burst) {
        this(requestsPerSecond, burst, System::nanoTime);
    }

    /**
     * Creates a limiter with an injectable clock (for deterministic tests).
     *
     * @param requestsPerSecond sustained rate per client
     * @param burst             bucket capacity (max back-to-back burst)
     * @param clock             monotonic nanosecond source
     */
    TokenBucketRateLimiter(int requestsPerSecond, int burst, LongSupplier clock) {
        if (requestsPerSecond < 1 || requestsPerSecond > 1_000_000) {
            throw new IllegalArgumentException("requestsPerSecond must be 1..1,000,000, got " + requestsPerSecond);
        }
        if (burst < 1 || burst > 1_000_000) {
            throw new IllegalArgumentException("burst must be 1..1,000,000, got " + burst);
        }
        this.capacityMilli = burst * MILLI_PER_TOKEN;
        this.refillPerSecondMilli = requestsPerSecond * MILLI_PER_TOKEN;
        // Refill per 100ms tick, rounded up so slow rates (e.g. 1/s) still accrue
        this.refillPerTickMilli = (refillPerSecondMilli + 9) / 10;
        this.clock = clock;
        this.startNanos = clock.getAsLong();
    }

    @Override
    public boolean tryAcquire(String clientId) {
        long tick = currentTick();

        Bucket bucket = buckets.computeIfAbsent(clientId, k -> new Bucket(capacityMilli, tick));

        maybeCleanup(tick);

        return bucket.tryAcquire(tick, capacityMilli, refillPerTickMilli);
    }

    @Override
    public long retryAfterMillis(String clientId) {
        Bucket bucket = buckets.get(clientId);
        if (bucket == null) {
            return 0; // Unknown client is not limited
        }
        long tick = currentTick();
        long tokens = bucket.currentTokens(tick, capacityMilli, refillPerTickMilli);
        if (tokens >= MILLI_PER_TOKEN) {
            return 0;
        }
        long neededMilli = MILLI_PER_TOKEN - tokens;
        // milliTokens / (milliTokens per second) = seconds; round up to the
        // next whole tick so we never advertise a retry that arrives too early
        long exactMillis = (neededMilli * 1000 + refillPerSecondMilli - 1) / refillPerSecondMilli;
        long alignedMillis = ((exactMillis + 99) / 100) * 100;
        return Math.max(100, alignedMillis);
    }

    /**
     * Number of currently tracked clients (for tests and future metrics).
     */
    public int trackedClients() {
        return buckets.size();
    }

    private long currentTick() {
        long elapsed = clock.getAsLong() - startNanos;
        if (elapsed <= 0) return 0;
        return (elapsed / TICK_NANOS) & TICK_MASK;
    }

    /**
     * Amortized eviction: every CLEANUP_INTERVAL operations, drop buckets
     * that have been idle beyond MAX_IDLE_TICKS. Runs inline (no extra
     * thread) and costs O(1) amortized per acquisition.
     */
    private void maybeCleanup(long tick) {
        if (opCounter.incrementAndGet() % CLEANUP_INTERVAL != 0) {
            return;
        }
        int before = buckets.size();
        if (before <= 1024) {
            return; // Small maps are fine; don't pay iteration cost
        }
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Bucket> e = it.next();
            long lastTick = e.getValue().lastTick();
            // Unsigned delta handles tick wraparound
            if (((tick - lastTick) & TICK_MASK) > MAX_IDLE_TICKS) {
                it.remove();
            }
        }
        int removed = before - buckets.size();
        if (removed > 0) {
            logger.debug("Rate limiter evicted idle buckets: removed={}, remaining={}",
                    removed, buckets.size());
        }
    }

    /**
     * A single client's bucket — one AtomicLong holding packed state.
     *
     * <p>Visibility of the methods is package-private so tests can
     * inspect state transitions directly.
     */
    static final class Bucket {
        private final AtomicLong state;

        Bucket(long initialTokensMilli, long tick) {
            this.state = new AtomicLong(pack(initialTokensMilli, tick));
        }

        boolean tryAcquire(long tick, long capacityMilli, long refillPerTickMilli) {
            while (true) {
                long current = state.get();
                long lastTick = current & TICK_MASK;
                long tokens = current >>> 32;

                // Lazy refill — accrue from the unsigned tick delta
                long delta = (tick - lastTick) & TICK_MASK;
                if (delta > 0) {
                    tokens = Math.min(capacityMilli, tokens + delta * refillPerTickMilli);
                }

                if (tokens >= MILLI_PER_TOKEN) {
                    long newState = pack(tokens - MILLI_PER_TOKEN, tick);
                    if (state.compareAndSet(current, newState)) {
                        return true; // Admitted
                    }
                    // Lost the race — retry with fresh state
                } else {
                    // Empty: leave state untouched so refill keeps accruing
                    return false;
                }
            }
        }

        long currentTokens(long tick, long capacityMilli, long refillPerTickMilli) {
            long current = state.get();
            long lastTick = current & TICK_MASK;
            long tokens = current >>> 32;
            long delta = (tick - lastTick) & TICK_MASK;
            if (delta > 0) {
                tokens = Math.min(capacityMilli, tokens + delta * refillPerTickMilli);
            }
            return tokens;
        }

        long lastTick() {
            return state.get() & TICK_MASK;
        }

        private static long pack(long tokensMilli, long tick) {
            return (tokensMilli << 32) | (tick & TICK_MASK);
        }
    }
}
