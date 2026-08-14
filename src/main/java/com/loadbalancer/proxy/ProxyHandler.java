package com.loadbalancer.proxy;

import com.loadbalancer.config.RetryConfig;
import com.loadbalancer.pool.Backend;
import com.loadbalancer.pool.BackendPool;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Main HTTP handler for the load balancer — the orchestrator with retry support.
 *
 * <p>Equivalent to Go's proxy.ReverseProxy. This is the "glue" between
 * the HTTP server and the backend pool.
 *
 * <p><b>Retry flow:</b>
 * <ol>
 *   <li>Capture the incoming request (buffer body for replay)</li>
 *   <li>Ask the pool for a backend</li>
 *   <li>Forward request via backend.forwardRequest()</li>
 *   <li>If result is retriable and retries remain → backoff → retry with different backend</li>
 *   <li>Otherwise → commit result to client</li>
 * </ol>
 *
 * <p><b>Concurrency:</b> handle() is called concurrently by the HTTP server
 * (one virtual thread per request). The pool.next() call is thread-safe.
 * Each request's retry loop is independent — no shared state between requests.
 */
public class ProxyHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(ProxyHandler.class);

    private final BackendPool pool;
    private final RetryConfig retryConfig;

    public ProxyHandler(BackendPool pool, RetryConfig retryConfig) {
        this.pool = pool;
        this.retryConfig = retryConfig;
    }

    /**
     * Handles an incoming HTTP request with automatic retries.
     *
     * <p>Request flow:
     * <ol>
     *   <li>Capture the request (method, path, headers, body)</li>
     *   <li>Enter retry loop (max_retries + 1 total attempts)</li>
     *   <li>On each attempt: select backend → forward → inspect result</li>
     *   <li>If retriable and retries left → exponential backoff → retry</li>
     *   <li>Commit final result (success or last failure) to client</li>
     * </ol>
     *
     * <p><b>Why 503 (not 502)?</b>
     * <ul>
     *   <li>502 Bad Gateway: "I tried to reach a backend but it failed"</li>
     *   <li>503 Service Unavailable: "I have no backends to try"</li>
     * </ul>
     * This distinction matters for client retry logic and monitoring/alerting.
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Step 1: Capture the request upfront for replay capability
        CapturedRequest captured = CapturedRequest.from(exchange);

        // Determine effective max retries
        int maxRetries = retryConfig.maxRetries();
        if (!captured.retriable()) {
            // Large body — don't retry (but still attempt once)
            maxRetries = 0;
            logger.debug("Request body exceeds {}B, retries disabled: method={}, path={}",
                    CapturedRequest.MAX_BODY_SIZE,
                    captured.method(), captured.requestURI().getPath());
        }

        // Step 2: Retry loop
        ProxyResult lastResult = null;
        Set<String> triedBackends = new HashSet<>();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            Backend backend = pool.next(exchange);
            if (backend == null) {
                break; // No healthy backends at all
            }

            // Try to avoid the same backend on retries
            if (attempt > 0 && triedBackends.contains(backend.name())) {
                // Ask for another — but accept the same if it's the only option
                Backend alt = pool.next(exchange);
                if (alt != null && !triedBackends.contains(alt.name())) {
                    backend = alt;
                }
            }
            triedBackends.add(backend.name());

            if (attempt == 0) {
                logger.info("Proxying request: method={}, path={}, remote_addr={}, backend={}, active_conns={}",
                        captured.method(),
                        captured.requestURI().getPath(),
                        captured.remoteAddress(),
                        backend.name(),
                        backend.activeConnections());
            }

            lastResult = backend.forwardRequest(captured);

            // If not retriable or last attempt → break and commit
            if (!lastResult.isRetriable() || attempt == maxRetries) {
                break;
            }

            // Exponential backoff before retry (skip for fast local circuit-breaker rejections)
            if (lastResult.isCircuitBreakerRejection()) {
                logger.warn("Instant retry (circuit breaker open): attempt={}/{}, backend={}, path={}",
                        attempt + 1, maxRetries, backend.name(), captured.requestURI().getPath());
            } else {
                long backoffMs = calculateBackoff(attempt);
                logger.warn("Retrying request: attempt={}/{}, backend={}, reason={}, backoff={}ms, path={}",
                        attempt + 1, maxRetries,
                        backend.name(),
                        lastResult.error() != null ? lastResult.error().getMessage()
                                : "HTTP " + lastResult.statusCode(),
                        backoffMs,
                        captured.requestURI().getPath());

                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Step 3: Commit — write the best result to the client
        if (lastResult != null) {
            lastResult.writeTo(exchange);
        } else {
            sendServiceUnavailable(exchange);
        }
    }

    /**
     * Calculates exponential backoff with jitter.
     *
     * <p>Formula: {@code min(initialBackoff * 2^attempt, maxBackoff) * (0.75..1.25)}
     *
     * <p>Jitter prevents thundering herd when multiple requests retry simultaneously
     * (e.g., after a backend crash). Without jitter, all retries would hit the
     * remaining backends at exactly the same time.
     *
     * @param attempt zero-based attempt index (0 = first retry)
     * @return backoff duration in milliseconds
     */
    long calculateBackoff(int attempt) {
        long base = retryConfig.initialBackoff().toMillis() * (1L << attempt);
        long capped = Math.min(base, retryConfig.maxBackoff().toMillis());

        // Add ±25% jitter
        double jitter = 0.75 + (ThreadLocalRandom.current().nextDouble() * 0.5);
        return (long) (capped * jitter);
    }

    /**
     * Sends a 503 Service Unavailable response with JSON body.
     */
    private void sendServiceUnavailable(HttpExchange exchange) throws IOException {
        String body = "{\"error\":\"service unavailable\",\"message\":\"no healthy backends\",\"status\":503}";
        byte[] bytes = body.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(503, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
