package com.loadbalancer.benchmark;

import com.loadbalancer.pool.*;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.openjdk.jmh.annotations.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class PoolBenchmark {

    private BackendPool roundRobin;
    private BackendPool leastConnections;
    private BackendPool weightedRoundRobin;
    private BackendPool ipHash;
    private BackendPool random;

    private HttpExchange dummyExchange;

    @Setup
    public void setup() {
        roundRobin = new RoundRobinPool();
        leastConnections = new LeastConnectionsPool();
        weightedRoundRobin = new WeightedRoundRobinPool();
        ipHash = new IPHashPool();
        random = new RandomPool();

        // Add some backends
        for (int i = 1; i <= 5; i++) {
            Backend b = new Backend("http://localhost:900" + i, "backend" + i, i, 100);
            roundRobin.addBackend(b);
            leastConnections.addBackend(b);
            weightedRoundRobin.addBackend(b);
            ipHash.addBackend(b);
            random.addBackend(b);
        }

        // Dummy HttpExchange for testing IP hash extraction
        dummyExchange = new HttpExchange() {
            @Override public Headers getRequestHeaders() {
                Headers h = new Headers();
                h.add("X-Forwarded-For", "192.168.1.100");
                return h;
            }
            @Override public Headers getResponseHeaders() { return null; }
            @Override public URI getRequestURI() { return null; }
            @Override public String getRequestMethod() { return null; }
            @Override public HttpContext getHttpContext() { return null; }
            @Override public void close() {}
            @Override public InputStream getRequestBody() { return null; }
            @Override public OutputStream getResponseBody() { return null; }
            @Override public void sendResponseHeaders(int rCode, long responseLength) {}
            @Override public InetSocketAddress getRemoteAddress() {
                return new InetSocketAddress("192.168.1.100", 8080);
            }
            @Override public int getResponseCode() { return 0; }
            @Override public InetSocketAddress getLocalAddress() { return null; }
            @Override public String getProtocol() { return null; }
            @Override public Object getAttribute(String name) { return null; }
            @Override public void setAttribute(String name, Object value) {}
            @Override public void setStreams(InputStream i, OutputStream o) {}
            @Override public HttpPrincipal getPrincipal() { return null; }
        };
    }

    @Benchmark
    @Threads(1)
    public Backend testRoundRobinSeq() {
        return roundRobin.next(dummyExchange);
    }

    @Benchmark
    @Threads(8)
    public Backend testRoundRobinConcurrent() {
        return roundRobin.next(dummyExchange);
    }

    @Benchmark
    @Threads(1)
    public Backend testLeastConnectionsSeq() {
        return leastConnections.next(dummyExchange);
    }

    @Benchmark
    @Threads(8)
    public Backend testLeastConnectionsConcurrent() {
        return leastConnections.next(dummyExchange);
    }

    @Benchmark
    @Threads(1)
    public Backend testWeightedRoundRobinSeq() {
        return weightedRoundRobin.next(dummyExchange);
    }

    @Benchmark
    @Threads(8)
    public Backend testWeightedRoundRobinConcurrent() {
        return weightedRoundRobin.next(dummyExchange);
    }

    @Benchmark
    @Threads(1)
    public Backend testIPHashSeq() {
        return ipHash.next(dummyExchange);
    }

    @Benchmark
    @Threads(8)
    public Backend testIPHashConcurrent() {
        return ipHash.next(dummyExchange);
    }

    @Benchmark
    @Threads(1)
    public Backend testRandomSeq() {
        return random.next(dummyExchange);
    }

    @Benchmark
    @Threads(8)
    public Backend testRandomConcurrent() {
        return random.next(dummyExchange);
    }
}
