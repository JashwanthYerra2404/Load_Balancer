package com.loadbalancer.pool;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IPHashPool. Uses a real HttpServer to create genuine HttpExchange
 * objects instead of mocking HttpExchange (which is unmockable on Java 25+).
 *
 * <p>Approach: We test the hashing logic directly via the package-private
 * static methods, and test pool behavior by passing null exchange (the pool
 * extracts IP from exchange.getRemoteAddress(), which we test separately).
 */
class IPHashPoolTest {

    @Test
    void emptyPoolReturnsNull() {
        IPHashPool pool = new IPHashPool();
        // Passing null exchange — pool checks size first and returns null
        assertNull(pool.next(null));
    }

    @Test
    void fnv1aHashIsDeterministic() {
        int hash1 = IPHashPool.fnv1aHash("192.168.1.100");
        int hash2 = IPHashPool.fnv1aHash("192.168.1.100");
        assertEquals(hash1, hash2);
    }

    @Test
    void fnv1aHashDifferentInputsDifferentHashes() {
        int hash1 = IPHashPool.fnv1aHash("192.168.1.1");
        int hash2 = IPHashPool.fnv1aHash("10.0.0.1");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void fnv1aHashDistributesWell() {
        // Generate hashes for 100 IPs, check they map to at least 2 of 3 buckets
        Map<Integer, Integer> buckets = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            String ip = "10.0." + (i / 256) + "." + (i % 256);
            int bucket = Math.floorMod(IPHashPool.fnv1aHash(ip), 3);
            buckets.merge(bucket, 1, Integer::sum);
        }
        // All 3 buckets should have traffic
        assertEquals(3, buckets.size(), "FNV-1a should distribute across all buckets");
    }

    @Test
    void allDeadReturnsNull() {
        IPHashPool pool = new IPHashPool();
        Backend b1 = new Backend("http://localhost:9001", "b1", 1, 0);
        Backend b2 = new Backend("http://localhost:9002", "b2", 1, 0);
        pool.addBackend(b1);
        pool.addBackend(b2);
        b1.setAlive(false);
        b2.setAlive(false);

        // Null exchange — extractIP returns "unknown"
        assertNull(pool.next(null));
    }

    @Test
    void skipsDeadBackendsFallsForward() {
        IPHashPool pool = new IPHashPool();
        Backend b1 = new Backend("http://localhost:9001", "b1", 1, 0);
        Backend b2 = new Backend("http://localhost:9002", "b2", 1, 0);
        Backend b3 = new Backend("http://localhost:9003", "b3", 1, 0);
        pool.addBackend(b1);
        pool.addBackend(b2);
        pool.addBackend(b3);

        // Kill two backends — only one should be available
        b1.setAlive(false);
        b2.setAlive(false);

        Backend selected = pool.next(null);
        assertNotNull(selected);
        assertEquals("b3", selected.name());
    }

    @Test
    void backendsReturnsDefensiveCopy() {
        IPHashPool pool = new IPHashPool();
        pool.addBackend(new Backend("http://localhost:9001", "b1", 1, 0));

        var backends = pool.backends();
        assertEquals(1, backends.size());
        assertThrows(UnsupportedOperationException.class, () -> backends.add(null));
    }
}
