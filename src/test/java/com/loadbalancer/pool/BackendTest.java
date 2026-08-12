package com.loadbalancer.pool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackendTest {

    @Test
    void creationSetsFieldsCorrectly() {
        Backend b = new Backend("http://localhost:9001", "backend-1", 3, 100);
        assertEquals("backend-1", b.name());
        assertEquals("http://localhost:9001", b.url().toString());
        assertEquals(3, b.weight());
        assertTrue(b.isAlive());
        assertEquals(0, b.activeConnections());
    }

    @Test
    void defaultWeightIsOneForZeroOrNegative() {
        Backend b1 = new Backend("http://localhost:9001", "b", 0, 0);
        assertEquals(1, b1.weight());

        Backend b2 = new Backend("http://localhost:9001", "b", -5, 0);
        assertEquals(1, b2.weight());
    }

    @Test
    void aliveToggle() {
        Backend b = new Backend("http://localhost:9001", "b", 1, 0);
        assertTrue(b.isAlive());

        b.setAlive(false);
        assertFalse(b.isAlive());

        b.setAlive(true);
        assertTrue(b.isAlive());
    }

    @Test
    void isAtCapacityUnlimited() {
        Backend b = new Backend("http://localhost:9001", "b", 1, 0);
        // maxConnections=0 means unlimited — never at capacity
        assertFalse(b.isAtCapacity());
    }

    @Test
    void isAtCapacityLimited() {
        Backend b = new Backend("http://localhost:9001", "b", 1, 5);

        // Use reflection-free approach: we can't directly set activeConnections,
        // but we can test the interface contract by calling isAtCapacity
        assertFalse(b.isAtCapacity()); // 0/5
    }
}
