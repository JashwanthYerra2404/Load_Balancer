package com.loadbalancer.util;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClientIp} — shared client identification for
 * IP-hash routing and per-client rate limiting.
 */
class ClientIpTest {

    @Test
    void prefersXForwardedFor() {
        HttpExchange exchange = dummyExchange("198.51.100.7, 10.0.0.1", null, "192.168.0.5");
        assertEquals("198.51.100.7", ClientIp.extract(exchange),
                "Leftmost X-Forwarded-For entry is the original client");
    }

    @Test
    void fallsBackToXRealIp() {
        HttpExchange exchange = dummyExchange(null, "203.0.113.4", "192.168.0.5");
        assertEquals("203.0.113.4", ClientIp.extract(exchange));
    }

    @Test
    void fallsBackToRemoteAddress() {
        HttpExchange exchange = dummyExchange(null, null, "192.168.0.5");
        assertEquals("192.168.0.5", ClientIp.extract(exchange));
    }

    @Test
    void nullExchangeReturnsUnknown() {
        assertEquals("unknown", ClientIp.extract(null));
    }

    @Test
    void emptyHeadersFallBackToRemoteAddress() {
        HttpExchange exchange = dummyExchange("", "", "192.168.0.5");
        assertEquals("192.168.0.5", ClientIp.extract(exchange));
    }

    private static HttpExchange dummyExchange(String xff, String xRealIp, String remoteAddr) {
        return new HttpExchange() {
            @Override public Headers getRequestHeaders() {
                Headers h = new Headers();
                if (xff != null) h.add("X-Forwarded-For", xff);
                if (xRealIp != null) h.add("X-Real-IP", xRealIp);
                return h;
            }
            @Override public Headers getResponseHeaders() { return new Headers(); }
            @Override public URI getRequestURI() { return URI.create("/"); }
            @Override public String getRequestMethod() { return "GET"; }
            @Override public HttpContext getHttpContext() { return null; }
            @Override public void close() {}
            @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
            @Override public OutputStream getResponseBody() { return OutputStream.nullOutputStream(); }
            @Override public void sendResponseHeaders(int rCode, long responseLength) {}
            @Override public InetSocketAddress getRemoteAddress() {
                return new InetSocketAddress(remoteAddr, 12345);
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
