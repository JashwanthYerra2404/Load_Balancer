package com.loadbalancer.health;

import com.loadbalancer.config.HealthCheckConfig;
import com.loadbalancer.pool.Backend;
import com.loadbalancer.pool.BackendPool;
import com.loadbalancer.pool.RoundRobinPool;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckerTest {

    private HttpServer healthyServer;
    private HttpServer unhealthyServer;

    @BeforeEach
    void setUp() throws IOException {
        // Healthy backend — responds 200 to /health
        healthyServer = HttpServer.create(new InetSocketAddress(0), 0);
        healthyServer.createContext("/health", exchange -> {
            byte[] body = "OK".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        healthyServer.start();

        // Unhealthy backend — responds 500 to /health
        unhealthyServer = HttpServer.create(new InetSocketAddress(0), 0);
        unhealthyServer.createContext("/health", exchange -> {
            byte[] body = "ERROR".getBytes();
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        unhealthyServer.start();
    }

    @AfterEach
    void tearDown() {
        if (healthyServer != null) healthyServer.stop(0);
        if (unhealthyServer != null) unhealthyServer.stop(0);
    }

    private HealthCheckConfig quickConfig(int failureThreshold, int successThreshold) {
        return new HealthCheckConfig(
                Duration.ofMillis(100), // fast interval for tests
                Duration.ofSeconds(2),
                "/health",
                failureThreshold,
                successThreshold
        );
    }

    @Test
    void healthyBackendStaysAlive() throws InterruptedException {
        BackendPool pool = new RoundRobinPool();
        String url = "http://localhost:" + healthyServer.getAddress().getPort();
        Backend backend = new Backend(url, "healthy", 1, 0);
        pool.addBackend(backend);

        HealthChecker checker = new HealthChecker(pool, quickConfig(3, 1));

        // Run a single check manually
        checker.checkBackend(backend).join();

        assertTrue(backend.isAlive(), "Healthy backend should stay alive");
    }

    @Test
    void unhealthyBackendMarkedDeadAfterThreshold() throws InterruptedException {
        BackendPool pool = new RoundRobinPool();
        String url = "http://localhost:" + unhealthyServer.getAddress().getPort();
        Backend backend = new Backend(url, "unhealthy", 1, 0);
        pool.addBackend(backend);

        HealthChecker checker = new HealthChecker(pool, quickConfig(3, 1));

        // Should still be alive after 1-2 failures (threshold is 3)
        checker.checkBackend(backend).join();
        assertTrue(backend.isAlive(), "Should not be dead after 1 failure");

        checker.checkBackend(backend).join();
        assertTrue(backend.isAlive(), "Should not be dead after 2 failures");

        // Third failure should mark it dead
        checker.checkBackend(backend).join();
        assertFalse(backend.isAlive(), "Should be dead after 3 consecutive failures");
    }

    @Test
    void unreachableBackendMarkedDead() throws InterruptedException {
        BackendPool pool = new RoundRobinPool();
        // Port 1 is almost certainly not listening
        Backend backend = new Backend("http://localhost:1", "unreachable", 1, 0);
        pool.addBackend(backend);

        HealthChecker checker = new HealthChecker(pool, quickConfig(1, 1));

        checker.checkBackend(backend).join();
        assertFalse(backend.isAlive(), "Unreachable backend should be marked dead after 1 failure");
    }

    @Test
    void deadBackendRecoversAfterSuccessThreshold() throws InterruptedException {
        BackendPool pool = new RoundRobinPool();
        String url = "http://localhost:" + healthyServer.getAddress().getPort();
        Backend backend = new Backend(url, "recovering", 1, 0);
        pool.addBackend(backend);

        HealthChecker checker = new HealthChecker(pool, quickConfig(1, 2));

        // Mark dead initially
        backend.setAlive(false);
        assertFalse(backend.isAlive());

        // First success — not yet recovered (threshold is 2)
        checker.checkBackend(backend).join();
        assertFalse(backend.isAlive(), "Should not recover after 1 success (threshold=2)");

        // Second success — now recovered
        checker.checkBackend(backend).join();
        assertTrue(backend.isAlive(), "Should recover after 2 consecutive successes");
    }

    @Test
    void startAndStopHealthChecker() throws InterruptedException {
        BackendPool pool = new RoundRobinPool();
        String url = "http://localhost:" + healthyServer.getAddress().getPort();
        pool.addBackend(new Backend(url, "b1", 1, 0));

        HealthChecker checker = new HealthChecker(pool, quickConfig(3, 1));
        checker.start();

        // Let it run for a bit
        TimeUnit.MILLISECONDS.sleep(300);

        // Should stop without hanging
        checker.stop();
    }
}
