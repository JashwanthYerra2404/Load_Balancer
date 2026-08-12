package com.loadbalancer.pool;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WeightedRoundRobinPoolTest {

    @Test
    void emptyPoolReturnsNull() {
        WeightedRoundRobinPool pool = new WeightedRoundRobinPool();
        assertNull(pool.next(null));
    }

    @Test
    void proportionalDistribution() {
        WeightedRoundRobinPool pool = new WeightedRoundRobinPool();
        pool.addBackend(new Backend("http://localhost:9001", "heavy", 3, 0));
        pool.addBackend(new Backend("http://localhost:9002", "medium", 2, 0));
        pool.addBackend(new Backend("http://localhost:9003", "light", 1, 0));

        Map<String, Integer> counts = new HashMap<>();
        // 60 requests = 10 full cycles of weight sum 6
        for (int i = 0; i < 60; i++) {
            Backend selected = pool.next(null);
            assertNotNull(selected);
            counts.merge(selected.name(), 1, Integer::sum);
        }

        assertEquals(30, counts.get("heavy"));
        assertEquals(20, counts.get("medium"));
        assertEquals(10, counts.get("light"));
    }

    @Test
    void smoothDistribution() {
        WeightedRoundRobinPool pool = new WeightedRoundRobinPool();
        pool.addBackend(new Backend("http://localhost:9001", "A", 5, 0));
        pool.addBackend(new Backend("http://localhost:9002", "B", 1, 0));

        // First 6 requests should include B (smooth, not batched)
        String[] sequence = new String[6];
        for (int i = 0; i < 6; i++) {
            Backend selected = pool.next(null);
            assertNotNull(selected);
            sequence[i] = selected.name();
        }

        boolean foundB = false;
        for (String name : sequence) {
            if ("B".equals(name)) {
                foundB = true;
                break;
            }
        }
        assertTrue(foundB, "SWRR should interleave — B should appear in first 6 requests");
    }

    @Test
    void equalWeightsGiveEqualDistribution() {
        WeightedRoundRobinPool pool = new WeightedRoundRobinPool();
        pool.addBackend(new Backend("http://localhost:9001", "b1", 1, 0));
        pool.addBackend(new Backend("http://localhost:9002", "b2", 1, 0));
        pool.addBackend(new Backend("http://localhost:9003", "b3", 1, 0));

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            Backend selected = pool.next(null);
            assertNotNull(selected);
            counts.merge(selected.name(), 1, Integer::sum);
        }

        assertEquals(10, counts.get("b1"));
        assertEquals(10, counts.get("b2"));
        assertEquals(10, counts.get("b3"));
    }

    @Test
    void skipsDeadBackends() {
        WeightedRoundRobinPool pool = new WeightedRoundRobinPool();
        Backend alive1 = new Backend("http://localhost:9001", "alive-1", 3, 0);
        Backend dead = new Backend("http://localhost:9002", "dead", 2, 0);
        Backend alive2 = new Backend("http://localhost:9003", "alive-2", 1, 0);
        pool.addBackend(alive1);
        pool.addBackend(dead);
        pool.addBackend(alive2);

        dead.setAlive(false);

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            Backend selected = pool.next(null);
            assertNotNull(selected);
            counts.merge(selected.name(), 1, Integer::sum);
        }

        assertEquals(0, counts.getOrDefault("dead", 0));
        // alive-1 (w=3) and alive-2 (w=1) → 3:1 ratio → 30:10
        assertEquals(30, counts.get("alive-1"));
        assertEquals(10, counts.get("alive-2"));
    }

    @Test
    void allDeadReturnsNull() {
        WeightedRoundRobinPool pool = new WeightedRoundRobinPool();
        Backend b1 = new Backend("http://localhost:9001", "b1", 1, 0);
        pool.addBackend(b1);
        b1.setAlive(false);

        assertNull(pool.next(null));
    }

    @Test
    void deadBackendHasWeightReset() throws Exception {
        WeightedRoundRobinPool pool = new WeightedRoundRobinPool();
        Backend b1 = new Backend("http://localhost:9001", "b1", 10, 0);
        Backend b2 = new Backend("http://localhost:9002", "b2", 10, 0);
        pool.addBackend(b1);
        pool.addBackend(b2);

        // Run next() a few times to build up currentWeights
        pool.next(null);
        pool.next(null);

        // Mark b1 dead
        b1.setAlive(false);

        // Call next() so the pool skips b1 and should reset its weight
        pool.next(null);

        // Use reflection to verify b1's weight was reset
        java.lang.reflect.Field weightsField = WeightedRoundRobinPool.class.getDeclaredField("currentWeights");
        weightsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<Integer> currentWeights =
                (java.util.List<Integer>) weightsField.get(pool);

        assertEquals(0, currentWeights.get(0), "Dead backend currentWeight should be reset to 0");
    }
}
