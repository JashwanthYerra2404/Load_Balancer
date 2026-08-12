package com.loadbalancer.server;

import com.loadbalancer.config.ServerConfig;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * HTTP server lifecycle management for the load balancer.
 *
 * <p>Equivalent to Go's server.Server. Wraps {@link HttpServer} with:
 * <ul>
 *   <li>Virtual threads for request handling (equivalent to Go's goroutine-per-request)</li>
 *   <li>Graceful shutdown via shutdown hook (equivalent to Go's signal handling)</li>
 *   <li>Configurable timeouts from {@link ServerConfig}</li>
 * </ul>
 *
 * <p><b>Why com.sun.net.httpserver.HttpServer?</b>
 * <ul>
 *   <li>JDK built-in — no external dependencies (Netty, Jetty, etc.)</li>
 *   <li>Sufficient for a reverse proxy that delegates actual work to HttpClient</li>
 *   <li>Supports virtual threads natively via custom Executor</li>
 * </ul>
 *
 * <p><b>Why virtual threads?</b> Each request gets its own virtual thread,
 * just like Go's goroutine model. Virtual threads are cheap (~1KB stack) and
 * managed by the JVM runtime, making them ideal for I/O-bound proxy workloads.
 */
public class LoadBalancerServer {

    private static final Logger logger = LoggerFactory.getLogger(LoadBalancerServer.class);
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final HttpServer httpServer;
    private final ExecutorService executor;

    /**
     * Creates a new LoadBalancerServer.
     *
     * @param config  Server configuration (port, timeouts)
     * @param handler The HTTP handler (typically ProxyHandler)
     * @throws IOException if the server socket cannot be created
     */
    public LoadBalancerServer(ServerConfig config, HttpHandler handler) throws IOException {
        this.httpServer = HttpServer.create(
                new InetSocketAddress(config.port()),
                0 // backlog: 0 = system default
        );

        // Use virtual threads — equivalent to Go's goroutine-per-request model.
        // Each request gets its own lightweight virtual thread.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpServer.setExecutor(executor);

        // Register the handler for all paths
        this.httpServer.createContext("/", handler);
    }

    /**
     * Starts the HTTP server and blocks until shutdown.
     *
     * <p>Registers a shutdown hook for graceful shutdown:
     * <ol>
     *   <li>Stop accepting new connections</li>
     *   <li>Wait for in-flight requests to complete (up to 30s)</li>
     *   <li>Force-close remaining connections</li>
     * </ol>
     *
     * <p>This is equivalent to Go's signal.Notify + httpServer.Shutdown pattern.
     */
    public void start() {
        // Register shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received, stopping server...");
            shutdown();
        }, "shutdown-hook"));

        logger.info("Server starting on port {}", httpServer.getAddress().getPort());
        httpServer.start();
        logger.info("Server started on {}", httpServer.getAddress());

        // Block the main thread. The server runs in background threads.
        // We use a simple wait mechanism that can be interrupted by shutdown.
        synchronized (this) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("Main thread interrupted, shutting down");
            }
        }
    }

    /**
     * Gracefully shuts down the server.
     *
     * <p>Stops accepting new connections, waits for in-flight requests,
     * then shuts down the executor.
     */
    public void shutdown() {
        logger.info("Shutting down server, timeout={}s", SHUTDOWN_TIMEOUT_SECONDS);

        // Stop the HTTP server — stops accepting new connections.
        // The delay parameter gives in-flight requests time to complete.
        httpServer.stop((int) SHUTDOWN_TIMEOUT_SECONDS);

        // Shut down the executor
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("Executor did not terminate in time, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }

        // Wake up the main thread
        synchronized (this) {
            this.notifyAll();
        }

        logger.info("Server stopped gracefully");
    }
}
