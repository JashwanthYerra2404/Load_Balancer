package com.loadbalancer.pool;

import com.loadbalancer.util.ClientIp;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Routes all requests from the same client IP to the same backend (sticky sessions).
 *
 * <p>Equivalent to Go's IPHashPool. Common use cases:
 * <ul>
 *   <li>In-memory session stores (session lives on one backend)</li>
 *   <li>Caching layers (maximize cache hit rates)</li>
 *   <li>WebSocket connections (upgrade goes to same backend)</li>
 * </ul>
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Extract client IP from {@code HttpExchange.getRemoteAddress()}</li>
 *   <li>Hash the IP using FNV-1a (fast, non-cryptographic, good distribution)</li>
 *   <li>Map hash to backend index: {@code hash % len(backends)}</li>
 *   <li>If that backend is unhealthy, scan forward (like RoundRobin fallback)</li>
 * </ol>
 *
 * <p>The forward-scan fallback means that when a backend goes down, only its
 * clients are redistributed — other clients are unaffected.
 *
 * <p><b>Why FNV-1a?</b> ~2ns per hash, good distribution for IP addresses,
 * no external dependencies. Not cryptographic, but we need speed, not security.
 *
 * <p><b>Concurrency:</b> Hash computation is per-thread (no shared state).
 * Backend list is protected by {@link ReadWriteLock}.
 *
 * <p>Time complexity: O(1) best case, O(n) worst case. Space complexity: O(1).
 */
public class IPHashPool implements BackendPool {

    private static final Logger logger = LoggerFactory.getLogger(IPHashPool.class);

    // FNV-1a constants for 32-bit hash
    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    private final List<Backend> backends = new CopyOnWriteArrayList<>();

    @Override
    public Backend next(HttpExchange exchange) {
        int count = backends.size();
        if (count == 0) return null;

        // Extract client IP
        String clientIP = extractIP(exchange);

        // Hash to get backend index
        int idx = Math.floorMod(fnv1aHash(clientIP), count);

        // Try the hashed backend first, then scan forward if unhealthy
        for (int i = 0; i < count; i++) {
            int target = (idx + i) % count;
            Backend b = backends.get(target);
            if (b.isAvailable() && !b.isAtCapacity()) {
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
        logger.info("Backend added to IP hash pool: backend={}, url={}, pool_size={}",
                backend.name(), backend.url(), backends.size());
    }

    /**
     * Extracts the client IP address from the HTTP exchange.
     * Returns "unknown" if the remote address is not available.
     * Delegates to the shared {@link ClientIp} resolver.
     */
    static String extractIP(HttpExchange exchange) {
        return ClientIp.extract(exchange);
    }

    /**
     * Computes FNV-1a 32-bit hash of the given string.
     *
     * <p>FNV-1a properties:
     * <ul>
     *   <li>Non-cryptographic (fast, ~2ns per hash)</li>
     *   <li>Good distribution for short strings like IP addresses</li>
     *   <li>Deterministic: same input always produces same output</li>
     * </ul>
     */
    static int fnv1aHash(String input) {
        int hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < input.length(); i++) {
            hash ^= input.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
