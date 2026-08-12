package com.loadbalancer.pool;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoundRobinPoolTest {

    @Test
    void emptyPoolReturnsNull() {
        RoundRobinPool pool = new RoundRobinPool();
        assertNull(pool.next(null));
    }

    @Test
    void singleBackendAlwaysSelected() {
        RoundRobinPool pool = new RoundRobinPool();
        pool.addBackend(new Backend("http://localhost:9001", "b1", 1, 0));

        for (int i = 0; i < 10; i++) {
            Backend selected = pool.next(null);
            assertNotNull(selected);
            assertEquals("b1", selected.name());
        }
    }

    @Test
    void evenDistributionAcrossBackends() {
        RoundRobinPool pool = new RoundRobinPool();
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
        RoundRobinPool pool = new RoundRobinPool();
        Backend b1 = new Backend("http://localhost:9001", "alive", 1, 0);
        Backend b2 = new Backend("http://localhost:9002", "dead", 1, 0);
        pool.addBackend(b1);
        pool.addBackend(b2);

        b2.setAlive(false);

        for (int i = 0; i < 10; i++) {
            Backend selected = pool.next(null);
            assertNotNull(selected);
            assertEquals("alive", selected.name());
        }
    }

    @Test
    void allDeadReturnsNull() {
        RoundRobinPool pool = new RoundRobinPool();
        Backend b1 = new Backend("http://localhost:9001", "b1", 1, 0);
        Backend b2 = new Backend("http://localhost:9002", "b2", 1, 0);
        pool.addBackend(b1);
        pool.addBackend(b2);

        b1.setAlive(false);
        b2.setAlive(false);

        assertNull(pool.next(null));
    }

    @Test
    void backendsReturnsDefensiveCopy() {
        RoundRobinPool pool = new RoundRobinPool();
        pool.addBackend(new Backend("http://localhost:9001", "b1", 1, 0));

        var backends = pool.backends();
        assertEquals(1, backends.size());

        // Should be unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> backends.add(null));
    }

    @Test
    void handlesNegativeCounterOverflow() throws Exception {
        RoundRobinPool pool = new RoundRobinPool();
        pool.addBackend(new Backend("http://localhost:9001", "b1", 1, 0));
        pool.addBackend(new Backend("http://localhost:9002", "b2", 1, 0));
        pool.addBackend(new Backend("http://localhost:9003", "b3", 1, 0));

        // Use reflection to set current to a negative value (simulating overflow)
        java.lang.reflect.Field currentField = RoundRobinPool.class.getDeclaredField("current");
        currentField.setAccessible(true);
        java.util.concurrent.atomic.AtomicLong current = (java.util.concurrent.atomic.AtomicLong) currentField.get(pool);
        current.set(-5);

        // Even with negative counter, it should cleanly round-robin using floorMod
        assertNotNull(pool.next(null));
        assertNotNull(pool.next(null));
        assertNotNull(pool.next(null));
    }
}
