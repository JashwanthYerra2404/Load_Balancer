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
 * Selects the backend with the fewest active connections.
 *
 * <p>Equivalent to Go's LeastConnectionsPool. Ideal for workloads with variable
 * request durations. If some requests take 10ms and others 500ms, round-robin
 * overloads backends that get slow requests. LeastConnections naturally directs
 * traffic away from busy backends.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Scan all backends, track the one with lowest activeConnections()</li>
 *   <li>Skip dead or at-capacity backends</li>
 *   <li>On tie: use a round-robin counter as tie-breaker (prevents thundering herd)</li>
 * </ol>
 *
 * <p>Real-world usage: default in HAProxy ("leastconn"), available in Nginx, AWS ALB, Envoy.
 *
 * <p>Time complexity: O(n) per call. Space complexity: O(1).
 */
public class LeastConnectionsPool implements BackendPool {

    private static final Logger logger = LoggerFactory.getLogger(LeastConnectionsPool.class);

    private final List<Backend> backends = new CopyOnWriteArrayList<>();
    private final AtomicLong tieBreaker = new AtomicLong(0);

    @Override
    public Backend next(HttpExchange exchange) {
        int count = backends.size();
        if (count == 0) return null;

        List<Backend> tied = new ArrayList<>();
        long bestConns = Long.MAX_VALUE;

        for (Backend b : backends) {
            if (!b.isAvailable() || b.isAtCapacity()) continue;

            long conns = b.activeConnections();
            if (conns < bestConns) {
                tied.clear();
                tied.add(b);
                bestConns = conns;
            } else if (conns == bestConns) {
                tied.add(b);
            }
        }

        Backend best = null;
        if (!tied.isEmpty()) {
            if (tied.size() == 1) {
                best = tied.get(0);
            } else {
                int tieIdx = (int) Math.floorMod(tieBreaker.getAndIncrement(), (long) tied.size());
                best = tied.get(tieIdx);
            }
        }

        if (best == null) {
            logger.warn("No healthy backends available, total_backends={}", count);
        }
        return best;
    }

    @Override
    public List<Backend> backends() {
        return Collections.unmodifiableList(backends);
    }

    @Override
    public void addBackend(Backend backend) {
        backends.add(backend);
        logger.info("Backend added to least-connections pool: backend={}, url={}, pool_size={}",
                backend.name(), backend.url(), backends.size());
    }
}
