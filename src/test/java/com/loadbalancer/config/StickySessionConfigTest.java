package com.loadbalancer.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StickySessionConfig} — defaults and Set-Cookie building.
 */
class StickySessionConfigTest {

    @Test
    void withDefaultsAppliesAllDefaults() {
        StickySessionConfig cfg = StickySessionConfig.withDefaults(null, null, null, null, null);

        assertFalse(cfg.enabled());
        assertEquals("LB_BACKEND", cfg.cookieName());
        assertEquals(Duration.ofHours(1), cfg.ttl());
        assertTrue(cfg.httpOnly());
        assertFalse(cfg.secure());
    }

    @Test
    void withDefaultsPreservesExplicitValues() {
        StickySessionConfig cfg = StickySessionConfig.withDefaults(
                true, "MY_COOKIE", Duration.ofMinutes(5), false, true);

        assertTrue(cfg.enabled());
        assertEquals("MY_COOKIE", cfg.cookieName());
        assertEquals(Duration.ofMinutes(5), cfg.ttl());
        assertFalse(cfg.httpOnly());
        assertTrue(cfg.secure());
    }

    @Test
    void withDefaultsReplacesEmptyCookieName() {
        StickySessionConfig cfg = StickySessionConfig.withDefaults(null, "", null, null, null);
        assertEquals("LB_BACKEND", cfg.cookieName());
    }

    @Test
    void buildCookieHeaderFullForm() {
        StickySessionConfig cfg = new StickySessionConfig(
                true, "LB_BACKEND", Duration.ofHours(1), true, false);

        assertEquals("LB_BACKEND=backend-2; Path=/; Max-Age=3600; HttpOnly",
                cfg.buildCookieHeader("backend-2"));
    }

    @Test
    void buildCookieHeaderSessionCookieOmitsMaxAge() {
        // Duration.ZERO means a browser session cookie — no Max-Age attribute
        StickySessionConfig cfg = new StickySessionConfig(
                true, "LB_BACKEND", Duration.ZERO, true, false);

        assertEquals("LB_BACKEND=backend-1; Path=/; HttpOnly",
                cfg.buildCookieHeader("backend-1"));
    }

    @Test
    void buildCookieHeaderWithoutHttpOnly() {
        StickySessionConfig cfg = new StickySessionConfig(
                true, "LB_BACKEND", Duration.ofHours(2), false, false);

        assertEquals("LB_BACKEND=b; Path=/; Max-Age=7200",
                cfg.buildCookieHeader("b"));
    }

    @Test
    void buildCookieHeaderWithSecure() {
        StickySessionConfig cfg = new StickySessionConfig(
                true, "LB_BACKEND", Duration.ofHours(1), false, true);

        assertEquals("LB_BACKEND=b; Path=/; Max-Age=3600; Secure",
                cfg.buildCookieHeader("b"));
    }

    @Test
    void buildCookieHeaderAllFlags() {
        StickySessionConfig cfg = new StickySessionConfig(
                true, "AFFINITY", Duration.ofSeconds(90), true, true);

        assertEquals("AFFINITY=backend-9; Path=/; Max-Age=90; HttpOnly; Secure",
                cfg.buildCookieHeader("backend-9"));
    }

    @Test
    void buildCookieHeaderUsesConfiguredCookieName() {
        StickySessionConfig cfg = new StickySessionConfig(
                true, "MY_STICKY", Duration.ofMinutes(30), true, false);

        assertEquals("MY_STICKY=backend-1; Path=/; Max-Age=1800; HttpOnly",
                cfg.buildCookieHeader("backend-1"));
    }
}
