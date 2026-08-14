package com.loadbalancer.pool;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Selects backends using round-robin rotation.
 *
 * <p>Equivalent to Go's RoundRobinPool. Each backend gets requests in turn:
 * Backend 1, Backend 2, Backend 3, Backend 1, ...
 *
 * <p><b>Concurrency:</b>
 * <ul>
 *   <li>Round-robin counter: {@link AtomicLong} — lock-free, ~15ns per operation
 *       (equivalent to Go's atomic.Uint64)</li>
 *   <li>Backend list: {@link CopyOnWriteArrayList} — lock-free readers</li>
 * </ul>
 *
 * <p>Properties: O(n) worst case per call, O(1) space, deterministic, fair distribution.
 */
public class RoundRobinPool implements BackendPool {

    private static final Logger logger = LoggerFactory.getLogger(RoundRobinPool.class);

    private final List<Backend> backends = new CopyOnWriteArrayList<>();
    private final AtomicLong current = new AtomicLong(0);

    @Override
    public Backend next(HttpExchange exchange) {
        int count = backends.size();
        if (count == 0) return null;

        long next = current.getAndIncrement();

        for (int i = 0; i < count; i++) {
            int idx = (int) Math.floorMod(next + i, (long) count);
            Backend backend = backends.get(idx);
            if (backend.isAvailable() && !backend.isAtCapacity()) {
                return backend;
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
        logger.info("Backend added to round-robin pool: backend={}, url={}, pool_size={}",
                backend.name(), backend.url(), backends.size());
    }
}
