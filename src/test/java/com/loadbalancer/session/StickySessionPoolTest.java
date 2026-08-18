package com.loadbalancer.session;

import com.loadbalancer.config.StickySessionConfig;
import com.loadbalancer.pool.Backend;
import com.loadbalancer.pool.BackendPool;
import com.loadbalancer.pool.RoundRobinPool;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StickySessionPool} cookie-based session affinity.
 */
class StickySessionPoolTest {

    private static final StickySessionConfig ENABLED_CONFIG = new StickySessionConfig(
            true, "LB_BACKEND", Duration.ofHours(1), true, false
    );

    private RoundRobinPool innerPool;
    private Backend backend1;
    private Backend backend2;
    private Backend backend3;

    @BeforeEach
    void setup() {
        innerPool = new RoundRobinPool();
        backend1 = new Backend("http://localhost:9001", "backend-1", 1, 0);
        backend2 = new Backend("http://localhost:9002", "backend-2", 1, 0);
        backend3 = new Backend("http://localhost:9003", "backend-3", 1, 0);
        innerPool.addBackend(backend1);
        innerPool.addBackend(backend2);
        innerPool.addBackend(backend3);
    }

    @Test
    void delegatesToInnerPoolWithNoCookie() {
        StickySessionPool pool = new StickySessionPool(innerPool, ENABLED_CONFIG);

        // No cookie → should use round-robin (inner pool)
        HttpExchange exchange = dummyExchange(null);
        Backend selected = pool.next(exchange);
        assertNotNull(selected);
    }

    @Test
    void routesToPinnedBackendWhenCookiePresent() {
        StickySessionPool pool = new StickySessionPool(innerPool, ENABLED_CONFIG);

        // Cookie pinned to backend-2
        HttpExchange exchange = dummyExchange("LB_BACKEND=backend-2");
        Backend selected = pool.next(exchange);
        assertEquals("backend-2", selected.name());

        // Repeat — should always return backend-2
        for (int i = 0; i < 10; i++) {
            selected = pool.next(dummyExchange("LB_BACKEND=backend-2"));
            assertEquals("backend-2", selected.name());
        }
    }

    @Test
    void fallsBackWhenPinnedBackendDead() {
        StickySessionPool pool = new StickySessionPool(innerPool, ENABLED_CONFIG);

        // Kill backend-2
        backend2.setAlive(false);

        // Cookie points to dead backend → should delegate to inner pool
        HttpExchange exchange = dummyExchange("LB_BACKEND=backend-2");
        Backend selected = pool.next(exchange);
        assertNotNull(selected);
        assertNotEquals("backend-2", selected.name());
    }

    @Test
    void ignoresUnknownBackendInCookie() {
        StickySessionPool pool = new StickySessionPool(innerPool, ENABLED_CONFIG);

        // Cookie points to backend that doesn't exist
        HttpExchange exchange = dummyExchange("LB_BACKEND=nonexistent");
        Backend selected = pool.next(exchange);
        assertNotNull(selected);
        // Should use inner pool's algorithm
    }

    @Test
    void handlesMalformedCookieHeader() {
        StickySessionPool pool = new StickySessionPool(innerPool, ENABLED_CONFIG);

        // Various malformed cookies — should not crash
        String[] malformed = {"", "garbage", "=", "===", "LB_BACKEND=", "other=value"};
        for (String cookie : malformed) {
            HttpExchange exchange = dummyExchange(cookie);
            Backend selected = pool.next(exchange);
            assertNotNull(selected, "Should not crash on cookie: " + cookie);
        }
    }

    @Test
    void handlesMultipleCookies() {
        StickySessionPool pool = new StickySessionPool(innerPool, ENABLED_CONFIG);

        // Multiple cookies — should find the right one
        HttpExchange exchange = dummyExchange("session_id=abc123; LB_BACKEND=backend-3; theme=dark");
        Backend selected = pool.next(exchange);
        assertEquals("backend-3", selected.name());
    }

    @Test
    void backendsAndAddBackendDelegate() {
        StickySessionPool pool = new StickySessionPool(innerPool, ENABLED_CONFIG);

        // backends() should delegate
        assertEquals(3, pool.backends().size());

        // addBackend() should delegate
        Backend backend4 = new Backend("http://localhost:9004", "backend-4", 1, 0);
        pool.addBackend(backend4);
        assertEquals(4, pool.backends().size());
    }

    @Test
    void parseCookieValueExtractsCorrectly() {
        assertEquals("backend-1",
                StickySessionPool.parseCookieValue("LB_BACKEND=backend-1", "LB_BACKEND"));
        assertEquals("backend-2",
                StickySessionPool.parseCookieValue("foo=bar; LB_BACKEND=backend-2; baz=qux", "LB_BACKEND"));
        assertNull(
                StickySessionPool.parseCookieValue("foo=bar; baz=qux", "LB_BACKEND"));
        assertNull(
                StickySessionPool.parseCookieValue("LB_BACKEND=", "LB_BACKEND"));
        assertNull(
                StickySessionPool.parseCookieValue("", "LB_BACKEND"));
    }

    @Test
    void cookieWithWhitespace() {
        StickySessionPool pool = new StickySessionPool(innerPool, ENABLED_CONFIG);

        // Extra whitespace around cookie values
        HttpExchange exchange = dummyExchange("  LB_BACKEND = backend-1 ; other=val ");
        Backend selected = pool.next(exchange);
        assertEquals("backend-1", selected.name());
    }

    @Test
    void worksWithLeastConnectionsPool() {
        // Sticky sessions are a decorator — algorithm-agnostic. Verify with
        // least-connections instead of round-robin.
        com.loadbalancer.pool.LeastConnectionsPool leastConn =
                new com.loadbalancer.pool.LeastConnectionsPool();
        leastConn.addBackend(backend1);
        leastConn.addBackend(backend2);
        leastConn.addBackend(backend3);

        StickySessionPool pool = new StickySessionPool(leastConn, ENABLED_CONFIG);

        // Cookie pins to backend-1 regardless of connection counts
        for (int i = 0; i < 10; i++) {
            Backend selected = pool.next(dummyExchange("LB_BACKEND=backend-1"));
            assertEquals("backend-1", selected.name());
        }
    }

    @Test
    void fallsBackWhenPinnedBackendAtCapacity() {
        // backend-2 pinned by cookie but at max connections → delegate to inner pool
        Backend atCapacity = new Backend("http://localhost:9002", "backend-2", 1, 1) {
            @Override public boolean isAtCapacity() { return true; }
        };
        RoundRobinPool pool2 = new RoundRobinPool();
        pool2.addBackend(backend1);
        pool2.addBackend(atCapacity);
        pool2.addBackend(backend3);

        StickySessionPool pool = new StickySessionPool(pool2, ENABLED_CONFIG);
        Backend selected = pool.next(dummyExchange("LB_BACKEND=backend-2"));
        assertNotNull(selected);
        assertNotEquals("backend-2", selected.name(), "Should bypass backend at capacity");
    }

    @Test
    void nullExchangeReturnsFromDelegate() {
        StickySessionPool pool = new StickySessionPool(innerPool, ENABLED_CONFIG);
        // null exchange → extractAffinityCookie returns null → delegates
        assertNull(pool.extractAffinityCookie(null));
    }

    // --- Helper: creates a dummy HttpExchange with a Cookie header ---

    private static HttpExchange dummyExchange(String cookieHeaderValue) {
        return new HttpExchange() {
            @Override public Headers getRequestHeaders() {
                Headers h = new Headers();
                if (cookieHeaderValue != null) {
                    h.add("Cookie", cookieHeaderValue);
                }
                return h;
            }
            @Override public Headers getResponseHeaders() { return new Headers(); }
            @Override public URI getRequestURI() { return URI.create("/test"); }
            @Override public String getRequestMethod() { return "GET"; }
            @Override public HttpContext getHttpContext() { return null; }
            @Override public void close() {}
            @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
            @Override public OutputStream getResponseBody() { return OutputStream.nullOutputStream(); }
            @Override public void sendResponseHeaders(int rCode, long responseLength) {}
            @Override public InetSocketAddress getRemoteAddress() {
                return new InetSocketAddress("127.0.0.1", 8080);
            }
            @Override public int getResponseCode() { return 0; }
            @Override public InetSocketAddress getLocalAddress() { return null; }
            @Override public String getProtocol() { return "HTTP/1.1"; }
            @Override public Object getAttribute(String name) { return null; }
            @Override public void setAttribute(String name, Object value) {}
            @Override public void setStreams(InputStream i, OutputStream o) {}
            @Override public HttpPrincipal getPrincipal() { return null; }
        };
    }
}
