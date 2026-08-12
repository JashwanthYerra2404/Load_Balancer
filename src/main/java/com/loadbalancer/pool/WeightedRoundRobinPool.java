package com.loadbalancer.pool;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Distributes requests proportionally to backend weights using the
 * Smooth Weighted Round Robin (SWRR) algorithm.
 *
 * <p>Equivalent to Go's WeightedRoundRobinPool. Same algorithm used by Nginx,
 * HAProxy, and Envoy.
 *
 * <p><b>Why "smooth"?</b> With backends weighted 5:1:1, a naive approach would
 * send 5 requests to A, then 1 to B, then 1 to C — a "burst" on A. SWRR
 * interleaves: A, A, B, A, C, A, A — spreading load evenly over time.
 *
 * <p>Algorithm (on each next() call):
 * <ol>
 *   <li>For each healthy backend i: currentWeight[i] += effectiveWeight[i]</li>
 *   <li>Select the backend with the highest currentWeight</li>
 *   <li>Subtract totalWeight from the selected backend's currentWeight</li>
 * </ol>
 *
 * <p><b>Concurrency:</b> Uses {@link ReentrantLock} (not ReadWriteLock) because
 * every next() call writes to currentWeights. A plain lock has ~20ns overhead
 * vs ~30ns for RWLock write-lock.
 *
 * <p>Time complexity: O(n) per call. Space complexity: O(n) for currentWeights.
 */
public class WeightedRoundRobinPool implements BackendPool {

    private static final Logger logger = LoggerFactory.getLogger(WeightedRoundRobinPool.class);

    private final List<Backend> backends = new ArrayList<>();
    private final List<Integer> currentWeights = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public Backend next(HttpExchange exchange) {
        lock.lock();
        try {
            int count = backends.size();
            if (count == 0) return null;

            int totalWeight = 0;
            int bestIdx = -1;
            int bestWeight = Integer.MIN_VALUE;

            for (int i = 0; i < count; i++) {
                Backend b = backends.get(i);
                if (!b.isAlive() || b.isAtCapacity()) {
                    currentWeights.set(i, 0);
                    continue;
                }

                int weight = b.weight();
                totalWeight += weight;

                // Increment current weight by effective weight
                currentWeights.set(i, currentWeights.get(i) + weight);

                // Track the backend with highest current weight
                if (currentWeights.get(i) > bestWeight) {
                    bestWeight = currentWeights.get(i);
                    bestIdx = i;
                }
            }

            if (bestIdx == -1) {
                logger.warn("No healthy backends available, total_backends={}", count);
                return null;
            }

            // Subtract totalWeight from selected backend's current weight
            currentWeights.set(bestIdx, currentWeights.get(bestIdx) - totalWeight);

            return backends.get(bestIdx);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Backend> backends() {
        lock.lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(backends));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addBackend(Backend backend) {
        lock.lock();
        try {
            backends.add(backend);
            currentWeights.add(0);
            logger.info("Backend added to weighted round-robin pool: backend={}, url={}, weight={}, pool_size={}",
                    backend.name(), backend.url(), backend.weight(), backends.size());
        } finally {
            lock.unlock();
        }
    }
}
