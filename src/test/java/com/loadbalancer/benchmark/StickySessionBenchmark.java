package com.loadbalancer.benchmark;

import com.loadbalancer.config.StickySessionConfig;
import com.loadbalancer.pool.Backend;
import com.loadbalancer.pool.BackendPool;
import com.loadbalancer.pool.RoundRobinPool;
import com.loadbalancer.session.StickySessionPool;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.openjdk.jmh.annotations.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks for the sticky session hot path: cookie parsing and
 * sticky-hit vs sticky-miss backend selection, compared against the
 * plain (undecorated) pool.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class StickySessionBenchmark {

    private static final String COOKIE_HEADER =
            "session_id=abc123; LB_BACKEND=backend-2; theme=dark";

    private StickySessionConfig config;
    private StickySessionPool stickyPool;
    private BackendPool plainPool;

    private HttpExchange exchangeWithCookie;
    private HttpExchange exchangeWithoutCookie;

    @Setup
    public void setup() {
        config = new StickySessionConfig(true, "LB_BACKEND",
                Duration.ofHours(1), true, false);

        RoundRobinPool inner = new RoundRobinPool();
        plainPool = inner;
        for (int i = 1; i <= 5; i++) {
            Backend b = new Backend("http://localhost:900" + i, "backend-" + i, 1, 100);
            inner.addBackend(b);
        }
        stickyPool = new StickySessionPool(inner, config);

        exchangeWithCookie = dummyExchange(COOKIE_HEADER);
        exchangeWithoutCookie = dummyExchange(null);
    }

    // --- Cookie parsing micro-benchmarks ---

    @Benchmark
    @Threads(1)
    public String testParseCookieValueSeq() {
        return StickySessionPool.parseCookieValue(COOKIE_HEADER, "LB_BACKEND");
    }

    @Benchmark
    @Threads(8)
    public String testParseCookieValueConcurrent() {
        return StickySessionPool.parseCookieValue(COOKIE_HEADER, "LB_BACKEND");
    }

    // --- Set-Cookie header building ---

    @Benchmark
    @Threads(1)
    public String testBuildCookieHeaderSeq() {
        return config.buildCookieHeader("backend-2");
    }

    // --- Backend selection: sticky hit (cookie → pinned backend) ---

    @Benchmark
    @Threads(1)
    public Backend testStickyHitSeq() {
        return stickyPool.next(exchangeWithCookie);
    }

    @Benchmark
    @Threads(8)
    public Backend testStickyHitConcurrent() {
        return stickyPool.next(exchangeWithCookie);
    }

    // --- Backend selection: sticky miss (no cookie → delegate to algorithm) ---

    @Benchmark
    @Threads(1)
    public Backend testStickyMissSeq() {
        return stickyPool.next(exchangeWithoutCookie);
    }

    @Benchmark
    @Threads(8)
    public Backend testStickyMissConcurrent() {
        return stickyPool.next(exchangeWithoutCookie);
    }

    // --- Baseline: plain pool without the sticky decorator ---

    @Benchmark
    @Threads(1)
    public Backend testPlainPoolSeq() {
        return plainPool.next(exchangeWithoutCookie);
    }

    @Benchmark
    @Threads(8)
    public Backend testPlainPoolConcurrent() {
        return plainPool.next(exchangeWithoutCookie);
    }

    // --- Helper: dummy HttpExchange with an optional Cookie header ---

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
            @Override public URI getRequestURI() { return URI.create("/bench"); }
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
