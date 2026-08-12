package com.loadbalancer.benchmark;

import com.loadbalancer.proxy.CapturedRequest;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.openjdk.jmh.annotations.*;

import java.io.ByteArrayInputStream;
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
public class ProxyBenchmark {

    private HttpExchange getExchange;
    private HttpExchange postExchange;

    @Setup
    public void setup() {
        getExchange = new DummyExchange("GET", null);
        postExchange = new DummyExchange("POST", "{\"id\": 123}".getBytes());
    }

    @Benchmark
    @Threads(1)
    public CapturedRequest testCaptureGet() throws Exception {
        return CapturedRequest.from(getExchange);
    }

    @Benchmark
    @Threads(1)
    public CapturedRequest testCapturePost() throws Exception {
        return CapturedRequest.from(postExchange);
    }

    private static class DummyExchange extends HttpExchange {
        private final String method;
        private final byte[] body;

        public DummyExchange(String method, byte[] body) {
            this.method = method;
            this.body = body;
        }

        @Override public Headers getRequestHeaders() { return new Headers(); }
        @Override public Headers getResponseHeaders() { return new Headers(); }
        @Override public URI getRequestURI() { return URI.create("/api/test"); }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { 
            return body != null ? new ByteArrayInputStream(body) : new ByteArrayInputStream(new byte[0]);
        }
        @Override public OutputStream getResponseBody() { return null; }
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
    }
}
