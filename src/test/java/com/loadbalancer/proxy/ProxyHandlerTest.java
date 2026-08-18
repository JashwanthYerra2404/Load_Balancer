package com.loadbalancer.proxy;

import com.loadbalancer.circuit.CircuitBreaker;
import com.loadbalancer.config.CircuitBreakerConfig;
import com.loadbalancer.config.RetryConfig;
import com.loadbalancer.config.StickySessionConfig;
import com.loadbalancer.pool.Backend;
import com.loadbalancer.pool.BackendPool;
import com.loadbalancer.pool.RoundRobinPool;
import com.loadbalancer.session.StickySessionPool;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProxyHandler using real HttpServer instances.
 *
 * <p>Covers basic proxying, 503 when no backends, and retry behavior.
 * Uses real HTTP servers instead of mocks (Java 25+ restricts mocking
 * com.sun.net.httpserver classes).
 */
class ProxyHandlerTest {

    /** Default retry config for tests — fast backoff to avoid slow tests. */
    private static final RetryConfig TEST_RETRY = new RetryConfig(
            2, Duration.ofMillis(10), Duration.ofMillis(50)
    );

    /** No-retry config for tests that need single-attempt behavior. */
    private static final RetryConfig NO_RETRY = new RetryConfig(
            0, Duration.ofMillis(10), Duration.ofMillis(50)
    );

    @Test
    void returns503WhenNoBackendsAvailable() throws Exception {
        // Empty pool — no backends
        BackendPool pool = new RoundRobinPool();
        ProxyHandler handler = new ProxyHandler(pool, TEST_RETRY);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", handler);
        server.start();

        try {
            int port = server.getAddress().getPort();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/test"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(503, response.statusCode());
            assertTrue(response.body().contains("service unavailable"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void proxyResultCircuitOpenReturns503() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            ProxyResult result = ProxyResult.circuitOpen("test-backend");
            result.writeTo(exchange);
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/test"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(503, response.statusCode());
            assertTrue(response.body().contains("circuit breaker open for test-backend"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void forwardsRequestToBackend() throws Exception {
        // Start a mock backend that responds 200
        HttpServer backendServer = HttpServer.create(new InetSocketAddress(0), 0);
        backendServer.createContext("/", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        backendServer.start();

        try {
            int backendPort = backendServer.getAddress().getPort();

            RoundRobinPool pool = new RoundRobinPool();
            pool.addBackend(new Backend(
                    "http://localhost:" + backendPort,
                    "test-backend", 1, 0
            ));

            ProxyHandler handler = new ProxyHandler(pool, TEST_RETRY);

            HttpServer proxyServer = HttpServer.create(new InetSocketAddress(0), 0);
            proxyServer.createContext("/", handler);
            proxyServer.start();

            try {
                int proxyPort = proxyServer.getAddress().getPort();

                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + proxyPort + "/api/test"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("ok"));
            } finally {
                proxyServer.stop(0);
            }
        } finally {
            backendServer.stop(0);
        }
    }

    @Test
    void returns503ForDeadBackend() throws Exception {
        RoundRobinPool pool = new RoundRobinPool();
        Backend deadBackend = new Backend("http://localhost:1", "dead", 1, 0);
        deadBackend.setAlive(false);
        pool.addBackend(deadBackend);

        ProxyHandler handler = new ProxyHandler(pool, TEST_RETRY);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", handler);
        server.start();

        try {
            int port = server.getAddress().getPort();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/test"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(503, response.statusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesOnConnectionFailureAndSucceeds() throws Exception {
        // Backend B responds 200
        HttpServer goodBackend = HttpServer.create(new InetSocketAddress(0), 0);
        goodBackend.createContext("/", exchange -> {
            byte[] body = "{\"from\":\"good\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        goodBackend.start();

        try {
            int goodPort = goodBackend.getAddress().getPort();

            RoundRobinPool pool = new RoundRobinPool();
            // Backend A — unreachable (port 1)
            pool.addBackend(new Backend("http://localhost:1", "bad-backend", 1, 0));
            // Backend B — healthy
            pool.addBackend(new Backend(
                    "http://localhost:" + goodPort, "good-backend", 1, 0));

            ProxyHandler handler = new ProxyHandler(pool, TEST_RETRY);

            HttpServer proxyServer = HttpServer.create(new InetSocketAddress(0), 0);
            proxyServer.createContext("/", handler);
            proxyServer.start();

            try {
                int proxyPort = proxyServer.getAddress().getPort();

                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + proxyPort + "/test"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                // Should succeed after retrying to good-backend
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("good"));
            } finally {
                proxyServer.stop(0);
            }
        } finally {
            goodBackend.stop(0);
        }
    }

    @Test
    void retriesOn502AndSucceeds() throws Exception {
        // Backend A returns 502
        HttpServer badBackend = HttpServer.create(new InetSocketAddress(0), 0);
        badBackend.createContext("/", exchange -> {
            byte[] body = "bad gateway".getBytes();
            exchange.sendResponseHeaders(502, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        badBackend.start();

        // Backend B returns 200
        HttpServer goodBackend = HttpServer.create(new InetSocketAddress(0), 0);
        goodBackend.createContext("/", exchange -> {
            byte[] body = "{\"from\":\"good\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        goodBackend.start();

        try {
            RoundRobinPool pool = new RoundRobinPool();
            pool.addBackend(new Backend(
                    "http://localhost:" + badBackend.getAddress().getPort(),
                    "bad-backend", 1, 0));
            pool.addBackend(new Backend(
                    "http://localhost:" + goodBackend.getAddress().getPort(),
                    "good-backend", 1, 0));

            ProxyHandler handler = new ProxyHandler(pool, TEST_RETRY);

            HttpServer proxyServer = HttpServer.create(new InetSocketAddress(0), 0);
            proxyServer.createContext("/", handler);
            proxyServer.start();

            try {
                int proxyPort = proxyServer.getAddress().getPort();

                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + proxyPort + "/test"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                // First attempt hits bad-backend (502, retriable), retry hits good-backend (200)
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("good"));
            } finally {
                proxyServer.stop(0);
            }
        } finally {
            badBackend.stop(0);
            goodBackend.stop(0);
        }
    }

    @Test
    void noRetryOn200() throws Exception {
        // Backend returns 200 — should not retry
        HttpServer backend = HttpServer.create(new InetSocketAddress(0), 0);
        backend.createContext("/", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        backend.start();

        try {
            RoundRobinPool pool = new RoundRobinPool();
            pool.addBackend(new Backend(
                    "http://localhost:" + backend.getAddress().getPort(),
                    "backend-1", 1, 0));

            ProxyHandler handler = new ProxyHandler(pool, TEST_RETRY);

            HttpServer proxyServer = HttpServer.create(new InetSocketAddress(0), 0);
            proxyServer.createContext("/", handler);
            proxyServer.start();

            try {
                int proxyPort = proxyServer.getAddress().getPort();
                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + proxyPort + "/test"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                assertEquals(200, response.statusCode());
            } finally {
                proxyServer.stop(0);
            }
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void noRetryOn404() throws Exception {
        // Backend returns 404 — client error, should not retry
        HttpServer backend = HttpServer.create(new InetSocketAddress(0), 0);
        backend.createContext("/", exchange -> {
            byte[] body = "not found".getBytes();
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        backend.start();

        try {
            RoundRobinPool pool = new RoundRobinPool();
            pool.addBackend(new Backend(
                    "http://localhost:" + backend.getAddress().getPort(),
                    "backend-1", 1, 0));

            ProxyHandler handler = new ProxyHandler(pool, TEST_RETRY);

            HttpServer proxyServer = HttpServer.create(new InetSocketAddress(0), 0);
            proxyServer.createContext("/", handler);
            proxyServer.start();

            try {
                int proxyPort = proxyServer.getAddress().getPort();
                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + proxyPort + "/test"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                assertEquals(404, response.statusCode());
            } finally {
                proxyServer.stop(0);
            }
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void noRetryWhenRetriesDisabled() throws Exception {
        // Backend is unreachable — with max_retries=0, should fail immediately
        RoundRobinPool pool = new RoundRobinPool();
        pool.addBackend(new Backend("http://localhost:1", "unreachable", 1, 0));

        ProxyHandler handler = new ProxyHandler(pool, NO_RETRY);

        HttpServer proxyServer = HttpServer.create(new InetSocketAddress(0), 0);
        proxyServer.createContext("/", handler);
        proxyServer.start();

        try {
            int proxyPort = proxyServer.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + proxyPort + "/test"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            // Should get 502 (connection error) without retrying
            assertEquals(502, response.statusCode());
            assertTrue(response.body().contains("bad gateway"));
        } finally {
            proxyServer.stop(0);
        }
    }

    @Test
    void backoffCalculation() {
        ProxyHandler handler = new ProxyHandler(new RoundRobinPool(), TEST_RETRY);

        // Test that backoff increases with attempt number
        long backoff0 = handler.calculateBackoff(0);
        long backoff1 = handler.calculateBackoff(1);
        long backoff2 = handler.calculateBackoff(2);

        // With initial=10ms, max=50ms:
        // attempt 0: 10ms * 2^0 = 10ms (±25% jitter → 7.5-12.5ms)
        // attempt 1: 10ms * 2^1 = 20ms (±25% jitter → 15-25ms)
        // attempt 2: 10ms * 2^2 = 40ms (±25% jitter → 30-50ms)
        assertTrue(backoff0 >= 5 && backoff0 <= 15,
                "Backoff 0 should be ~10ms, got " + backoff0);
        assertTrue(backoff1 >= 10 && backoff1 <= 30,
                "Backoff 1 should be ~20ms, got " + backoff1);
        assertTrue(backoff2 >= 25 && backoff2 <= 55,
                "Backoff 2 should be ~40ms (capped at 50ms), got " + backoff2);
    }

    @Test
    void circuitBreakerTripsAfterFailures() throws Exception {
        // Circuit breaker config: trips after 2 failures in 5s, recovery after 100ms
        CircuitBreakerConfig cbConfig = new CircuitBreakerConfig(
                2, Duration.ofSeconds(5), Duration.ofMillis(100)
        );

        // Single backend that's unreachable → connection failures
        RoundRobinPool pool = new RoundRobinPool();
        Backend unreachable = new Backend("http://localhost:1", "unreachable", 1, 0, cbConfig);
        pool.addBackend(unreachable);

        // No retries — we want to observe circuit breaker state directly
        ProxyHandler handler = new ProxyHandler(pool, NO_RETRY);

        HttpServer proxyServer = HttpServer.create(new InetSocketAddress(0), 0);
        proxyServer.createContext("/", handler);
        proxyServer.start();

        try {
            int port = proxyServer.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();

            // Request 1: actual connection failure (circuit records failure)
            HttpResponse<String> r1 = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/test"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(502, r1.statusCode());

            // Request 2: another connection failure → circuit trips (2 failures)
            HttpResponse<String> r2 = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/test"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            // After the 2nd failure, circuit is now OPEN
            assertEquals(CircuitBreaker.State.OPEN, unreachable.circuitBreaker().state());

            // Request 3: circuit is OPEN → pool.next() returns null (no available backends)
            // → 503 Service Unavailable
            HttpResponse<String> r3 = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/test"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, r3.statusCode());
            assertTrue(r3.body().contains("service unavailable"));
        } finally {
            proxyServer.stop(0);
        }
    }

    @Test
    void circuitBreakerRecoveryWithProbe() throws Exception {
        // Fast circuit breaker: trips after 2 failures, recovers after 50ms
        CircuitBreakerConfig cbConfig = new CircuitBreakerConfig(
                2, Duration.ofSeconds(5), Duration.ofMillis(50)
        );

        // Good backend that always responds 200
        HttpServer goodBackend = HttpServer.create(new InetSocketAddress(0), 0);
        goodBackend.createContext("/", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        goodBackend.start();

        try {
            int backendPort = goodBackend.getAddress().getPort();

            // Backend with circuit breaker
            RoundRobinPool pool = new RoundRobinPool();
            Backend backend = new Backend(
                    "http://localhost:" + backendPort, "test-backend", 1, 0, cbConfig);
            pool.addBackend(backend);

            // Manually trip the circuit
            backend.circuitBreaker().recordFailure();
            backend.circuitBreaker().recordFailure();
            assertEquals(CircuitBreaker.State.OPEN, backend.circuitBreaker().state());

            // Wait for recovery timeout
            Thread.sleep(80);

            // Probe request should succeed → circuit closes
            ProxyHandler handler = new ProxyHandler(pool, NO_RETRY);
            HttpServer proxyServer = HttpServer.create(new InetSocketAddress(0), 0);
            proxyServer.createContext("/", handler);
            proxyServer.start();

            try {
                int proxyPort = proxyServer.getAddress().getPort();
                HttpClient client = HttpClient.newHttpClient();

                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + proxyPort + "/test"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode());
                assertEquals(CircuitBreaker.State.CLOSED, backend.circuitBreaker().state(),
                        "Circuit should close after successful probe");
            } finally {
                proxyServer.stop(0);
            }
        } finally {
            goodBackend.stop(0);
        }
    }

    @Test
    void stickySessionCookieInResponse() throws Exception {
        StickySessionConfig stickyConfig = new StickySessionConfig(
                true, "LB_BACKEND", Duration.ofHours(1), true, false
        );

        HttpServer backend = HttpServer.create(new InetSocketAddress(0), 0);
        backend.createContext("/", exchange -> {
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        backend.start();

        try {
            RoundRobinPool pool = new RoundRobinPool();
            pool.addBackend(new Backend(
                    "http://localhost:" + backend.getAddress().getPort(),
                    "test-backend", 1, 0));
            StickySessionPool stickyPool = new StickySessionPool(pool, stickyConfig);

            ProxyHandler handler = new ProxyHandler(stickyPool, NO_RETRY, stickyConfig);
            HttpServer proxyServer = HttpServer.create(new InetSocketAddress(0), 0);
            proxyServer.createContext("/", handler);
            proxyServer.start();

            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + proxyServer.getAddress().getPort() + "/test"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode());

                // Check for Set-Cookie header
                var setCookies = response.headers().allValues("Set-Cookie");
                assertFalse(setCookies.isEmpty(), "Response should include Set-Cookie");
                assertTrue(setCookies.stream().anyMatch(c -> c.contains("LB_BACKEND=test-backend")),
                        "Cookie should contain backend name");
                assertTrue(setCookies.stream().anyMatch(c -> c.contains("HttpOnly")),
                        "Cookie should have HttpOnly flag");
                assertTrue(setCookies.stream().anyMatch(c -> c.contains("Max-Age=3600")),
                        "Cookie should have Max-Age=3600");
            } finally {
                proxyServer.stop(0);
            }
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void stickySessionRoutesToPinnedBackend() throws Exception {
        StickySessionConfig stickyConfig = new StickySessionConfig(
                true, "LB_BACKEND", Duration.ofHours(1), true, false
        );

        // Two backends that return their own name
        HttpServer backend1 = HttpServer.create(new InetSocketAddress(0), 0);
        backend1.createContext("/", exchange -> {
            byte[] body = "backend-1".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        backend1.start();

        HttpServer backend2 = HttpServer.create(new InetSocketAddress(0), 0);
        backend2.createContext("/", exchange -> {
            byte[] body = "backend-2".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        backend2.start();

        try {
            RoundRobinPool pool = new RoundRobinPool();
            pool.addBackend(new Backend(
                    "http://localhost:" + backend1.getAddress().getPort(),
                    "backend-1", 1, 0));
            pool.addBackend(new Backend(
                    "http://localhost:" + backend2.getAddress().getPort(),
                    "backend-2", 1, 0));
            StickySessionPool stickyPool = new StickySessionPool(pool, stickyConfig);

            ProxyHandler handler = new ProxyHandler(stickyPool, NO_RETRY, stickyConfig);
            HttpServer proxyServer = HttpServer.create(new InetSocketAddress(0), 0);
            proxyServer.createContext("/", handler);
            proxyServer.start();

            try {
                int port = proxyServer.getAddress().getPort();
                HttpClient client = HttpClient.newHttpClient();

                // Send request with cookie pinned to backend-2
                // Even though round-robin would pick backend-1, sticky should win
                for (int i = 0; i < 5; i++) {
                    HttpResponse<String> response = client.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create("http://localhost:" + port + "/test"))
                                    .header("Cookie", "LB_BACKEND=backend-2")
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString());

                    assertEquals(200, response.statusCode());
                    assertEquals("backend-2", response.body(),
                            "Sticky session should always route to backend-2");
                }
            } finally {
                proxyServer.stop(0);
            }
        } finally {
            backend1.stop(0);
            backend2.stop(0);
        }
    }

    @Test
    void noStickyCookieWhenStickyDisabled() throws Exception {
        StickySessionConfig disabled = new StickySessionConfig(
                false, "LB_BACKEND", Duration.ofHours(1), true, false);

        HttpServer backend = HttpServer.create(new InetSocketAddress(0), 0);
        backend.createContext("/", exchange -> {
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        backend.start();

        try {
            RoundRobinPool pool = new RoundRobinPool();
            pool.addBackend(new Backend(
                    "http://localhost:" + backend.getAddress().getPort(),
                    "test-backend", 1, 0));

            ProxyHandler handler = new ProxyHandler(pool, NO_RETRY, disabled);
            HttpServer proxyServer = HttpServer.create(new InetSocketAddress(0), 0);
            proxyServer.createContext("/", handler);
            proxyServer.start();

            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + proxyServer.getAddress().getPort() + "/test"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode());
                assertTrue(response.headers().allValues("Set-Cookie").isEmpty(),
                        "No Set-Cookie expected when sticky sessions are disabled");
            } finally {
                proxyServer.stop(0);
            }
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void stickyFallsBackAndRepinsWhenPinnedBackendDown() throws Exception {
        StickySessionConfig stickyConfig = new StickySessionConfig(
                true, "LB_BACKEND", Duration.ofHours(1), true, false
        );

        HttpServer backend1 = HttpServer.create(new InetSocketAddress(0), 0);
        backend1.createContext("/", exchange -> {
            byte[] body = "backend-1".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        backend1.start();

        HttpServer backend2 = HttpServer.create(new InetSocketAddress(0), 0);
        backend2.createContext("/", exchange -> {
            byte[] body = "backend-2".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        backend2.start();

        try {
            RoundRobinPool pool = new RoundRobinPool();
            Backend b1 = new Backend(
                    "http://localhost:" + backend1.getAddress().getPort(),
                    "backend-1", 1, 0);
            Backend b2 = new Backend(
                    "http://localhost:" + backend2.getAddress().getPort(),
                    "backend-2", 1, 0);
            pool.addBackend(b1);
            pool.addBackend(b2);
            StickySessionPool stickyPool = new StickySessionPool(pool, stickyConfig);

            ProxyHandler handler = new ProxyHandler(stickyPool, NO_RETRY, stickyConfig);
            HttpServer proxyServer = HttpServer.create(new InetSocketAddress(0), 0);
            proxyServer.createContext("/", handler);
            proxyServer.start();

            try {
                int port = proxyServer.getAddress().getPort();
                HttpClient client = HttpClient.newHttpClient();

                // First request — gets pinned to backend-1 via Set-Cookie
                HttpResponse<String> first = client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + port + "/test"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals("backend-1", first.body());
                assertTrue(first.headers().allValues("Set-Cookie").stream()
                        .anyMatch(c -> c.contains("LB_BACKEND=backend-1")));

                // backend-1 goes down (health checker would do this)
                b1.setAlive(false);

                // Cookie still pins to backend-1, but it's dead →
                // fallback to backend-2 AND re-pin with a fresh cookie
                HttpResponse<String> second = client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + port + "/test"))
                                .header("Cookie", "LB_BACKEND=backend-1")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(200, second.statusCode());
                assertEquals("backend-2", second.body(), "Should fall back to live backend");
                assertTrue(second.headers().allValues("Set-Cookie").stream()
                                .anyMatch(c -> c.contains("LB_BACKEND=backend-2")),
                        "Response should re-pin the client to backend-2");
            } finally {
                proxyServer.stop(0);
            }
        } finally {
            backend1.stop(0);
            backend2.stop(0);
        }
    }
}
