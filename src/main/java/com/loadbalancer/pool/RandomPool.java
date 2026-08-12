package com.loadbalancer.pool;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Selects a random healthy backend for each request.
 *
 * <p>Equivalent to Go's RandomPool. Provides a statistical approximation of
 * round-robin without maintaining a shared counter.
 *
 * <p>Uses {@link ThreadLocalRandom} — Java's concurrent-safe random number
 * generator (equivalent to Go's rand/v2). No explicit seeding needed.
 *
 * <p><b>When to use:</b>
 * <ul>
 *   <li>When simplicity is more important than perfect distribution</li>
 *   <li>As a baseline for benchmarking other algorithms</li>
 * </ul>
 *
 * <p><b>Concurrency:</b> ThreadLocalRandom is per-thread (zero contention).
 * Backend list is protected by {@link CopyOnWriteArrayList}.
 *
 * <p>Time complexity: O(n) worst case. Space complexity: O(1).
 */
public class RandomPool implements BackendPool {

    private static final Logger logger = LoggerFactory.getLogger(RandomPool.class);

    private final List<Backend> backends = new CopyOnWriteArrayList<>();

    @Override
    public Backend next(HttpExchange exchange) {
        int count = backends.size();
        if (count == 0) return null;

        // Pick a random starting index
        int start = ThreadLocalRandom.current().nextInt(count);

        // Scan forward from random start to find a healthy backend
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % count;
            Backend b = backends.get(idx);
            if (b.isAlive() && !b.isAtCapacity()) {
                return b;
            }
        }

        logger.warn("No healthy backends available, total_backends={}", count);
        return null;
    }

    @Override
    public List<Backend> backends() {
        return Collections.unmodifiableList(backends);
    }

    @Override
    public void addBackend(Backend backend) {
        backends.add(backend);
        logger.info("Backend added to random pool: backend={}, url={}, pool_size={}",
                backend.name(), backend.url(), backends.size());
    }
}
