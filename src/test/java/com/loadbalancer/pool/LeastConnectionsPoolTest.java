package com.loadbalancer.pool;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LeastConnectionsPoolTest {

    @Test
    void emptyPoolReturnsNull() {
        LeastConnectionsPool pool = new LeastConnectionsPool();
        assertNull(pool.next(null));
    }

    @Test
    void selectsLeastLoadedBackend() {
        LeastConnectionsPool pool = new LeastConnectionsPool();
        // We can't easily set activeConnections from tests without
        // making a request, but we can verify the basic selection logic
        // by observing that with equal connections, backends are distributed.
        pool.addBackend(new Backend("http://localhost:9001", "b1", 1, 0));
        pool.addBackend(new Backend("http://localhost:9002", "b2", 1, 0));

        // With 0 connections on all, tie-breaking should distribute
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            Backend selected = pool.next(null);
            assertNotNull(selected);
            counts.merge(selected.name(), 1, Integer::sum);
        }

        // Both should get some traffic
        assertTrue(counts.getOrDefault("b1", 0) > 0, "b1 should get some requests");
        assertTrue(counts.getOrDefault("b2", 0) > 0, "b2 should get some requests");
    }

    @Test
    void skipsDeadBackends() {
        LeastConnectionsPool pool = new LeastConnectionsPool();
        Backend alive = new Backend("http://localhost:9001", "alive", 1, 0);
        Backend dead = new Backend("http://localhost:9002", "dead", 1, 0);
        pool.addBackend(alive);
        pool.addBackend(dead);

        dead.setAlive(false);

        for (int i = 0; i < 10; i++) {
            Backend selected = pool.next(null);
            assertNotNull(selected);
            assertEquals("alive", selected.name());
        }
    }

    @Test
    void allDeadReturnsNull() {
        LeastConnectionsPool pool = new LeastConnectionsPool();
        Backend b1 = new Backend("http://localhost:9001", "b1", 1, 0);
        pool.addBackend(b1);
        b1.setAlive(false);

        assertNull(pool.next(null));
    }

    @Test
    void uniformTieBreakerDistributionAcrossMultipleTiedBackends() {
        LeastConnectionsPool pool = new LeastConnectionsPool();
        pool.addBackend(new Backend("http://localhost:9001", "b1", 1, 0));
        pool.addBackend(new Backend("http://localhost:9002", "b2", 1, 0));
        pool.addBackend(new Backend("http://localhost:9003", "b3", 1, 0));
        pool.addBackend(new Backend("http://localhost:9004", "b4", 1, 0));

        // Since all 4 backends have 0 active connections, they should be perfectly round-robined
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 400; i++) {
            Backend selected = pool.next(null);
            assertNotNull(selected);
            counts.merge(selected.name(), 1, Integer::sum);
        }

        // We expect exactly 100 requests per backend due to the fair Math.floorMod tie-breaker
        assertEquals(100, counts.get("b1"));
        assertEquals(100, counts.get("b2"));
        assertEquals(100, counts.get("b3"));
        assertEquals(100, counts.get("b4"));
    }
}
