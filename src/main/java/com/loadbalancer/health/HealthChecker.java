package com.loadbalancer.health;

import com.loadbalancer.config.HealthCheckConfig;
import com.loadbalancer.pool.Backend;
import com.loadbalancer.pool.BackendPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Background health checker that periodically probes each backend.
 *
 * <p>Equivalent to Go's health checker pattern using time.Ticker + goroutine.
 * Uses {@link ScheduledExecutorService} for periodic execution.
 *
 * <p><b>Algorithm:</b>
 * <ol>
 *   <li>Every {@code interval}, send HTTP GET to each backend's health path</li>
 *   <li>If response is 2xx → increment success counter, reset failure counter</li>
 *   <li>If connection fails or non-2xx → increment failure counter, reset success counter</li>
 *   <li>When failure counter reaches {@code failureThreshold} → mark backend dead</li>
 *   <li>When success counter reaches {@code successThreshold} → mark backend alive</li>
 * </ol>
 *
 * <p><b>Why thresholds?</b> A single failed health check could be a network blip.
 * Requiring multiple consecutive failures before marking dead prevents flapping.
 * Similarly, requiring multiple successes before marking alive prevents premature
 * traffic routing to a backend that's still warming up.
 *
 * <p><b>Concurrency:</b> The health checker runs on its own scheduled thread.
 * Backend.setAlive() is atomic, so there's no contention with request threads.
 * Per-backend failure/success counters use ConcurrentHashMap.
 */
public class HealthChecker {

    private static final Logger logger = LoggerFactory.getLogger(HealthChecker.class);

    private final BackendPool pool;
    private final HealthCheckConfig config;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    // Per-backend consecutive failure/success counters
    private final Map<String, Integer> failureCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> successCounts = new ConcurrentHashMap<>();

    /**
     * Creates a new HealthChecker.
     *
     * @param pool   The backend pool to health-check
     * @param config Health check configuration
     */
    public HealthChecker(BackendPool pool, HealthCheckConfig config) {
        this.pool = pool;
        this.config = config;

        // Dedicated HttpClient for health checks — isolated from proxy traffic.
        // Short connect timeout since health checks should be fast.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-checker");
            t.setDaemon(true); // Don't prevent JVM shutdown
            return t;
        });
    }

    /**
     * Starts the periodic health checking.
     *
     * <p>The first check runs immediately, then repeats at the configured interval.
     */
    public void start() {
        logger.info("Health checker starting: interval={}, timeout={}, path={}, " +
                        "failure_threshold={}, success_threshold={}",
                config.interval(), config.timeout(), config.path(),
                config.failureThreshold(), config.successThreshold());

        scheduler.scheduleAtFixedRate(
                this::checkAll,
                0, // initial delay: check immediately
                config.interval().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Stops the health checker gracefully.
     */
    public void stop() {
        logger.info("Health checker stopping");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        logger.info("Health checker stopped");
    }

    /**
     * Checks all backends in the pool.
     *
     * <p>This method is called periodically by the scheduler.
     * Each backend is checked independently — a slow backend
     * doesn't delay checks for other backends.
     */
    void checkAll() {
        for (Backend backend : pool.backends()) {
            checkBackend(backend);
        }
    }

    /**
     * Checks a single backend's health.
     *
     * <p>Sends HTTP GET to the health check path and evaluates the response.
     * Updates failure/success counters and toggles alive status based on thresholds.
     */
    CompletableFuture<Void> checkBackend(Backend backend) {
        String backendName = backend.name();

        try {
            // Build health check URL
            String baseUrl = backend.url().toString();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            String healthUrl = baseUrl + config.path();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(config.timeout())
                    .GET()
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            onSuccess(backend);
                        } else {
                            onFailure(backend, "HTTP " + response.statusCode());
                        }
                    })
                    .exceptionally(e -> {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        onFailure(backend, cause.getClass().getSimpleName() + ": " + cause.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            onFailure(backend, e.getClass().getSimpleName() + ": " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Handles a successful health check.
     *
     * <p>Increments success counter, resets failure counter.
     * If success counter reaches threshold and backend was dead → mark alive.
     */
    private void onSuccess(Backend backend) {
        String name = backend.name();
        failureCounts.put(name, 0);
        int successes = successCounts.merge(name, 1, Integer::sum);

        if (!backend.isAlive() && successes >= config.successThreshold()) {
            logger.info("Backend recovered: backend={}, consecutive_successes={}",
                    name, successes);
            backend.setAlive(true);
            successCounts.put(name, 0);
        }
    }

    /**
     * Handles a failed health check.
     *
     * <p>Increments failure counter, resets success counter.
     * If failure counter reaches threshold and backend was alive → mark dead.
     */
    private void onFailure(Backend backend, String reason) {
        String name = backend.name();
        successCounts.put(name, 0);
        int failures = failureCounts.merge(name, 1, Integer::sum);

        if (backend.isAlive() && failures >= config.failureThreshold()) {
            logger.warn("Backend marked dead: backend={}, reason={}, consecutive_failures={}",
                    name, reason, failures);
            backend.setAlive(false);
            failureCounts.put(name, 0);
        } else {
            logger.debug("Health check failed: backend={}, reason={}, failures={}/{}",
                    name, reason, failures, config.failureThreshold());
        }
    }
}
