package com.loadbalancer.session;

import com.loadbalancer.config.StickySessionConfig;
import com.loadbalancer.pool.Backend;
import com.loadbalancer.pool.BackendPool;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Decorator that adds cookie-based sticky sessions to any {@link BackendPool}.
 *
 * <p>Implements the Decorator pattern: wraps an inner pool (round-robin,
 * least-connections, etc.) and intercepts {@link #next(HttpExchange)} to
 * check for an affinity cookie. If the cookie points to a healthy backend,
 * that backend is returned directly — bypassing the inner pool's algorithm.
 *
 * <p><b>Algorithm:</b>
 * <ol>
 *   <li>Parse the {@code Cookie} header for the configured affinity cookie</li>
 *   <li>If found → lookup the backend by name in the pool</li>
 *   <li>If the backend is alive, available (circuit not open), and not at capacity → return it</li>
 *   <li>Otherwise → delegate to the inner pool's normal algorithm</li>
 * </ol>
 *
 * <p><b>Cookie format:</b> {@code LB_BACKEND=backend-2} (configurable name).
 * The cookie value is the backend's human-readable name.
 *
 * <p><b>Concurrency:</b> Cookie parsing is per-request (no shared state).
 * Backend lookup iterates {@code delegate.backends()} which is thread-safe
 * (CopyOnWriteArrayList in most pool implementations).
 *
 * <p><b>Why a decorator?</b> Sticky sessions are orthogonal to the load
 * balancing algorithm. A decorator lets us add/remove sticky behavior
 * without modifying any pool implementation.
 */
public class StickySessionPool implements BackendPool {

    private static final Logger logger = LoggerFactory.getLogger(StickySessionPool.class);

    private final BackendPool delegate;
    private final StickySessionConfig config;

    /**
     * Creates a sticky session wrapper around the given pool.
     *
     * @param delegate the inner pool to delegate to when no sticky match is found
     * @param config   sticky session configuration
     */
    public StickySessionPool(BackendPool delegate, StickySessionConfig config) {
        this.delegate = delegate;
        this.config = config;
    }

    /**
     * Selects a backend, preferring the one pinned by the affinity cookie.
     *
     * <p>If the affinity cookie is present and points to a healthy, available
     * backend, that backend is returned (sticky hit). Otherwise, the inner
     * pool's algorithm selects a backend (sticky miss — new cookie will be
     * set on the response).
     */
    @Override
    public Backend next(HttpExchange exchange) {
        // 1. Try to find pinned backend from cookie
        String pinnedName = extractAffinityCookie(exchange);
        if (pinnedName != null) {
            Backend pinned = findBackendByName(pinnedName);
            if (pinned != null && pinned.isAvailable() && !pinned.isAtCapacity()) {
                logger.debug("Sticky session hit: cookie={}, backend={}",
                        config.cookieName(), pinnedName);
                return pinned;
            }
            // Pinned backend unavailable — fall through to algorithm
            logger.info("Sticky session miss: backend={} unavailable, falling back to algorithm",
                    pinnedName);
        }

        // 2. No cookie or pinned backend down — use normal algorithm
        return delegate.next(exchange);
    }

    @Override
    public List<Backend> backends() {
        return delegate.backends();
    }

    @Override
    public void addBackend(Backend backend) {
        delegate.addBackend(backend);
    }

    /**
     * Extracts the affinity cookie value from the request's Cookie header.
     *
     * <p>Parses the raw Cookie header (format: {@code name1=val1; name2=val2})
     * and looks for the configured cookie name.
     *
     * @param exchange the HTTP exchange
     * @return the cookie value (backend name), or null if not found
     */
    String extractAffinityCookie(HttpExchange exchange) {
        if (exchange == null) return null;

        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null || cookieHeader.isEmpty()) return null;

        return parseCookieValue(cookieHeader, config.cookieName());
    }

    /**
     * Parses a specific cookie value from a raw Cookie header string.
     *
     * <p>Handles standard cookie format: {@code name1=val1; name2=val2}.
     * Trims whitespace around names and values for robustness.
     *
     * @param cookieHeader the raw Cookie header value
     * @param cookieName   the cookie name to look for
     * @return the cookie value, or null if not found
     */
    public static String parseCookieValue(String cookieHeader, String cookieName) {
        // Split on semicolons — each segment is "name=value"
        String[] pairs = cookieHeader.split(";");
        for (String pair : pairs) {
            String trimmed = pair.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String name = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if (name.equals(cookieName) && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Finds a backend by its name in the pool.
     *
     * <p>Iterates the delegate's backend list. This is O(n) but n is typically
     * 2-20 backends — negligible. The list is a CopyOnWriteArrayList in most
     * pool implementations, so iteration is lock-free.
     *
     * @param name the backend name to search for
     * @return the backend, or null if not found
     */
    private Backend findBackendByName(String name) {
        for (Backend b : delegate.backends()) {
            if (b.name().equals(name)) {
                return b;
            }
        }
        return null;
    }
}
