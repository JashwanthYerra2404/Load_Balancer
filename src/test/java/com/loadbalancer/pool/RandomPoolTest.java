package com.loadbalancer.pool;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RandomPoolTest {

    @Test
    void emptyPoolReturnsNull() {
        RandomPool pool = new RandomPool();
        assertNull(pool.next(null));
    }

    @Test
    void singleBackendAlwaysSelected() {
        RandomPool pool = new RandomPool();
        pool.addBackend(new Backend("http://localhost:9001", "only", 1, 0));

        for (int i = 0; i < 20; i++) {
            Backend selected = pool.next(null);
            assertNotNull(selected);
            assertEquals("only", selected.name());
        }
    }

    @Test
    void roughlyUniformDistribution() {
        RandomPool pool = new RandomPool();
        pool.addBackend(new Backend("http://localhost:9001", "b1", 1, 0));
        pool.addBackend(new Backend("http://localhost:9002", "b2", 1, 0));
        pool.addBackend(new Backend("http://localhost:9003", "b3", 1, 0));

        Map<String, Integer> counts = new HashMap<>();
        int total = 3000;
        for (int i = 0; i < total; i++) {
            Backend selected = pool.next(null);
            assertNotNull(selected);
            counts.merge(selected.name(), 1, Integer::sum);
        }

        // Each should get ~1000 ±15%
        double expected = total / 3.0;
        double tolerance = expected * 0.15;
        for (String name : new String[]{"b1", "b2", "b3"}) {
            int got = counts.getOrDefault(name, 0);
            assertTrue(Math.abs(got - expected) < tolerance,
                    name + " got " + got + " requests, expected ~" + (int) expected);
        }
    }

    @Test
    void skipsDeadBackends() {
        RandomPool pool = new RandomPool();
        Backend alive = new Backend("http://localhost:9001", "alive", 1, 0);
        Backend dead = new Backend("http://localhost:9002", "dead", 1, 0);
        pool.addBackend(alive);
        pool.addBackend(dead);
        dead.setAlive(false);

        for (int i = 0; i < 50; i++) {
            Backend selected = pool.next(null);
            assertNotNull(selected);
            assertEquals("alive", selected.name());
        }
    }

    @Test
    void allDeadReturnsNull() {
        RandomPool pool = new RandomPool();
        Backend b1 = new Backend("http://localhost:9001", "b1", 1, 0);
        pool.addBackend(b1);
        b1.setAlive(false);

        assertNull(pool.next(null));
    }
}
