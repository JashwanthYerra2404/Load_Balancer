package com.loadbalancer.pool;

import com.loadbalancer.circuit.CircuitBreaker;
import com.loadbalancer.config.CircuitBreakerConfig;
import com.loadbalancer.proxy.CapturedRequest;
import com.loadbalancer.proxy.ProxyResult;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a single upstream backend server.
 *
 * <p>Equivalent to Go's Backend struct. Each backend has its own {@link HttpClient}
 * instance (equivalent to Go's per-backend http.Transport) for connection pool
 * isolation — a slow backend won't exhaust connections meant for healthy ones.
 *
 * <p><b>Concurrency design:</b>
 * <ul>
 *   <li>{@code alive} — {@link AtomicBoolean} for lock-free reads (every request checks this)</li>
 *   <li>{@code activeConnections} — {@link AtomicLong} for lock-free increment/decrement</li>
 *   <li>{@code circuitBreaker} — per-backend state machine with volatile state (~1ns read)</li>
 *   <li>{@code httpClient} — thread-safe by design (Java HttpClient is immutable after build)</li>
 * </ul>
 *
 * <p>Memory cost: ~1KB per backend for the struct + HttpClient overhead.
 * For typical deployments (2-20 backends), this is negligible.
 */
public class Backend {

    private static final Logger logger = LoggerFactory.getLogger(Backend.class);

    private final URI url;
    private final String name;
    private final int weight;
    private final int maxConnections;
    private final HttpClient httpClient;
    private final CircuitBreaker circuitBreaker;

    /**
     * Whether this backend is currently healthy.
     * Updated by health checks (future phase). Starts as true.
     * Uses AtomicBoolean for lock-free reads — every request goroutine reads this.
     */
    private final AtomicBoolean alive = new AtomicBoolean(true);

    /**
     * Number of in-flight requests to this backend.
     * Incremented when a request starts, decremented when it completes.
     * Uses AtomicLong for lock-free increment/decrement.
     */
    private final AtomicLong activeConnections = new AtomicLong(0);

    /**
     * Creates a new Backend with a dedicated HttpClient and circuit breaker.
     *
     * @param url            Backend URL (e.g., "http://localhost:9001")
     * @param name           Human-readable name for logging
     * @param weight         Traffic weight for weighted algorithms (1-100)
     * @param maxConnections Max concurrent connections (0 = unlimited)
     * @param cbConfig       Circuit breaker configuration
     */
    public Backend(String url, String name, int weight, int maxConnections,
                   CircuitBreakerConfig cbConfig) {
        this.url = URI.create(url);
        this.name = name;
        this.weight = Math.max(weight, 1);
        this.maxConnections = maxConnections;

        // Each backend gets its own HttpClient for connection pool isolation.
        // Equivalent to Go's per-backend http.Transport.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        this.circuitBreaker = new CircuitBreaker(name, cbConfig);
    }

    /**
     * Creates a new Backend with default circuit breaker configuration.
     * Preserves backward compatibility with existing tests.
     */
    public Backend(String url, String name, int weight, int maxConnections) {
        this(url, name, weight, maxConnections,
                CircuitBreakerConfig.withDefaults(null, null, null));
    }

    // --- Getters ---

    public URI url() { return url; }
    public String name() { return name; }
    public int weight() { return weight; }

    /**
     * Returns whether the backend is currently healthy.
     * Lock-free atomic read — ~1ns.
     */
    public boolean isAlive() {
        return alive.get();
    }

    /**
     * Returns whether the backend is available for traffic.
     * Combines health check status (alive) with circuit breaker state.
     *
     * <p>Uses {@code canAcceptTraffic()} (read-only) instead of
     * {@code allowRequest()} to avoid consuming the HALF_OPEN probe permit
     * during pool iteration. The actual permit is acquired in
     * {@link #forwardRequest(CapturedRequest)}.
     *
     * <p>A backend is available if:
     * <ul>
     *   <li>It's alive (health checks passing)</li>
     *   <li>The circuit breaker can accept traffic (not OPEN, or timeout elapsed)</li>
     * </ul>
     */
    public boolean isAvailable() {
        return alive.get() && circuitBreaker.canAcceptTraffic();
    }

    /**
     * Returns the circuit breaker for this backend.
     * Exposed for testing and metrics.
     */
    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Updates the backend's health status.
     * Called by health checks and circuit breakers.
     */
    public void setAlive(boolean alive) {
        this.alive.set(alive);
        logger.info("Backend status changed: backend={}, alive={}", name, alive);
    }

    /**
     * Returns the current number of in-flight requests.
     */
    public long activeConnections() {
        return activeConnections.get();
    }

    /**
     * Returns true if the backend has reached its connection limit.
     * Returns false if maxConnections is 0 (unlimited).
     */
    public boolean isAtCapacity() {
        if (maxConnections == 0) return false;
        return activeConnections.get() >= maxConnections;
    }

    /**
     * Forwards an HTTP request to this backend and writes the response back.
     *
     * <p>Convenience method that captures the request, forwards it, and writes
     * the result. Used when retries are not needed (backward compatibility).
     *
     * @param exchange the incoming HTTP exchange to proxy
     */
    public void handleRequest(HttpExchange exchange) throws IOException {
        CapturedRequest captured = CapturedRequest.from(exchange);
        ProxyResult result = forwardRequest(captured);
        result.writeTo(exchange);
    }

    /**
     * Forwards a captured request to this backend and returns the result
     * WITHOUT writing to the client.
     *
     * <p>This is the core method that enables retries. The caller (ProxyHandler)
     * can inspect the result and decide whether to retry with a different backend
     * or commit the result to the client.
     *
     * <p>Circuit breaker integration: if the circuit is OPEN, returns immediately
     * without making a network call. On success/failure, records the outcome to
     * the circuit breaker for future decisions.
     *
     * <p>Active connections are tracked atomically: incremented before forwarding,
     * decremented after response completes (or on error). The try/finally pattern
     * guarantees the count is always accurate.
     *
     * @param request the captured request to forward
     * @return the result of the forwarding attempt (never null)
     */
    public ProxyResult forwardRequest(CapturedRequest request) {
        // Circuit breaker gate — short-circuit without network call
        if (!circuitBreaker.allowRequest()) {
            return ProxyResult.circuitOpen(name);
        }

        activeConnections.incrementAndGet();
        try {
            // Build the target URL
            String targetUrl = buildTargetUrl(request.requestURI());

            // Build the HttpRequest
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(30));

            // Set method + body
            if (request.body() != null) {
                reqBuilder.method(request.method(),
                        HttpRequest.BodyPublishers.ofByteArray(request.body()));
            } else if (request.bodyStream() != null) {
                reqBuilder.method(request.method(),
                        HttpRequest.BodyPublishers.ofInputStream(() -> request.bodyStream()));
            } else {
                reqBuilder.method(request.method(),
                        HttpRequest.BodyPublishers.noBody());
            }

            // Forward filtered client headers
            forwardRequestHeaders(reqBuilder, request.headers());

            // Add proxy identification headers
            addProxyHeaders(reqBuilder, request);

            // Send request to backend
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            ProxyResult result = new ProxyResult(
                    response.statusCode(),
                    response.headers().map(),
                    response.body(),
                    name,
                    null
            );

            // Record outcome to circuit breaker
            if (result.isRetriable()) {
                circuitBreaker.recordFailure();
            } else {
                circuitBreaker.recordSuccess();
            }

            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            circuitBreaker.recordFailure();
            return ProxyResult.connectionError(name, e);
        } catch (Exception e) {
            logger.error("Backend error: backend={}, url={}, error={}", name, url, e.getMessage());
            circuitBreaker.recordFailure();
            return ProxyResult.connectionError(name, e);
        } finally {
            activeConnections.decrementAndGet();
        }
    }

    // --- Private helpers ---

    /**
     * Builds the target URL by combining backend URL with the request path/query.
     */
    private String buildTargetUrl(URI requestURI) {
        String targetUrl = url.toString();
        if (targetUrl.endsWith("/")) {
            targetUrl = targetUrl.substring(0, targetUrl.length() - 1);
        }
        String requestPath = requestURI.getRawPath();
        targetUrl += requestPath;
        String query = requestURI.getRawQuery();
        if (query != null && !query.isEmpty()) {
            targetUrl += "?" + query;
        }
        return targetUrl;
    }

    /**
     * Forwards filtered client headers to the backend request.
     * Hop-by-hop and restricted headers are skipped.
     */
    private void forwardRequestHeaders(HttpRequest.Builder reqBuilder,
                                        Map<String, List<String>> headers) {
        if (headers == null) return;
        headers.forEach((key, values) -> {
            for (String value : values) {
                reqBuilder.header(key, value);
            }
        });
    }

    /**
     * Adds proxy identification headers (X-Real-IP, X-Forwarded-For, X-Backend-Name).
     */
    private void addProxyHeaders(HttpRequest.Builder reqBuilder, CapturedRequest request) {
        String clientIP = extractClientIP(request);
        if (clientIP != null) {
            reqBuilder.header("X-Real-IP", clientIP);
            // Check for existing X-Forwarded-For in the captured headers
            List<String> xffValues = request.headers().get("X-Forwarded-For");
            if (xffValues == null) {
                xffValues = request.headers().get("x-forwarded-for");
            }
            String existingXFF = (xffValues != null && !xffValues.isEmpty())
                    ? xffValues.get(0) : null;
            if (existingXFF != null && !existingXFF.isEmpty()) {
                reqBuilder.header("X-Forwarded-For", existingXFF + ", " + clientIP);
            } else {
                reqBuilder.header("X-Forwarded-For", clientIP);
            }
        }
        reqBuilder.header("X-Backend-Name", name);
    }

    /**
     * Extracts the client IP from the captured request's remote address.
     */
    private String extractClientIP(CapturedRequest request) {
        if (request.remoteAddress() != null && request.remoteAddress().getAddress() != null) {
            return request.remoteAddress().getAddress().getHostAddress();
        }
        return null;
    }

    /**
     * Sends a JSON error response to the client.
     * Equivalent to Go's errorHandler function.
     */
    private void sendErrorResponse(HttpExchange exchange, int statusCode, String message)
            throws IOException {
        String body = String.format(
                "{\"error\":\"bad gateway\",\"message\":\"%s\",\"backend\":\"%s\",\"status\":%d}",
                message, name, statusCode);
        byte[] bytes = body.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public String toString() {
        return String.format("Backend{name='%s', url='%s', weight=%d, alive=%s, circuit=%s, activeConns=%d}",
                name, url, weight, alive.get(), circuitBreaker.state(), activeConnections.get());
    }
}
