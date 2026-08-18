package com.loadbalancer.benchmark;

import com.loadbalancer.ratelimit.TokenBucketRateLimiter;
import com.loadbalancer.util.ClientIp;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.openjdk.jmh.annotations.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks for the per-client token bucket rate limiter hot path:
 * admit (CAS), deny (lock-free read), map lookup across many clients,
 * and client-IP extraction from an HttpExchange.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class RateLimiterBenchmark {

    private TokenBucketRateLimiter admittingLimiter;

    /** Capacity 1, immediately drained → steady-state deny path. */
    private TokenBucketRateLimiter denyingLimiter;

    private TokenBucketRateLimiter manyClientsLimiter;

    private static final String[] CLIENTS = new String[1000];
    private static final String CLIENT = "192.168.1.100";

    private HttpExchange exchangeWithXff;

    @Setup
    public void setup() {
        // Huge capacity → always admits: measures the full admit CAS path
        admittingLimiter = new TokenBucketRateLimiter(1_000_000, 1_000_000);

        // Drain once → every call now measures the deny path (no CAS)
        denyingLimiter = new TokenBucketRateLimiter(1, 1);
        denyingLimiter.tryAcquire(CLIENT);

        manyClientsLimiter = new TokenBucketRateLimiter(1_000_000, 1_000_000);
        for (int i = 0; i < CLIENTS.length; i++) {
            CLIENTS[i] = "10.0." + (i / 250) + "." + (i % 250);
        }

        exchangeWithXff = dummyExchange();
    }

    @Benchmark
    @Threads(1)
    public boolean testAdmitSeq() {
        return admittingLimiter.tryAcquire(CLIENT);
    }

    @Benchmark
    @Threads(8)
    public boolean testAdmitConcurrent() {
        return admittingLimiter.tryAcquire(CLIENT);
    }

    @Benchmark
    @Threads(1)
    public boolean testDenySeq() {
        return denyingLimiter.tryAcquire(CLIENT);
    }

    @Benchmark
    @Threads(8)
    public boolean testDenyConcurrent() {
        return denyingLimiter.tryAcquire(CLIENT);
    }

    @Benchmark
    @Threads(8)
    public boolean testManyClientsConcurrent() {
        // 8 threads hitting 1000 distinct buckets — measures map lookup +
        // independent CAS per bucket (realistic fan-out)
        String client = CLIENTS[ThreadLocalRandom.current().nextInt(CLIENTS.length)];
        return manyClientsLimiter.tryAcquire(client);
    }

    @Benchmark
    @Threads(1)
    public long testRetryAfterMillisSeq() {
        return denyingLimiter.retryAfterMillis(CLIENT);
    }

    @Benchmark
    @Threads(1)
    public String testClientIpExtractionSeq() {
        return ClientIp.extract(exchangeWithXff);
    }

    private static HttpExchange dummyExchange() {
        return new HttpExchange() {
            @Override public Headers getRequestHeaders() {
                Headers h = new Headers();
                h.add("X-Forwarded-For", "198.51.100.7, 10.0.0.1");
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
