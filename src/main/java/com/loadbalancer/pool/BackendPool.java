package com.loadbalancer.pool;

import com.sun.net.httpserver.HttpExchange;

import java.util.List;

/**
 * Strategy interface for backend selection algorithms.
 *
 * <p>Equivalent to Go's BackendPool interface. This follows the Strategy pattern:
 * the algorithm is encapsulated behind an interface and can be swapped at
 * configuration time without changing the proxy's code.
 *
 * <p><b>Concurrency contract:</b> All methods must be safe for concurrent use
 * from multiple threads. The HTTP server creates a new virtual thread for each
 * request, and all of them call {@link #next(HttpExchange)} concurrently.
 *
 * <p>Implementations: RoundRobinPool, LeastConnectionsPool, WeightedRoundRobinPool,
 * RandomPool, IPHashPool.
 */
public interface BackendPool {

    /**
     * Selects the next backend for the given request.
     *
     * @param exchange the HTTP exchange (passed for algorithms like IPHash
     *                 that need client IP for consistent routing)
     * @return the selected backend, or null if no healthy backends are available
     */
    Backend next(HttpExchange exchange);

    /**
     * Returns all backends in the pool (healthy and unhealthy).
     * Used by health checkers and metrics reporters.
     *
     * @return unmodifiable list of all backends
     */
    List<Backend> backends();

    /**
     * Adds a backend to the pool. Called during startup and config reload.
     *
     * @param backend the backend to add
     */
    void addBackend(Backend backend);
}
