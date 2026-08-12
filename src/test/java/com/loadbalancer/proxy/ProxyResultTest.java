package com.loadbalancer.proxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ProxyResult} — retriability logic and writeTo behavior.
 */
class ProxyResultTest {

    @Test
    void connectionErrorIsRetriable() {
        ProxyResult result = ProxyResult.connectionError("b1", new IOException("Connection refused"));
        assertTrue(result.isRetriable());
    }

    @Test
    void interruptedExceptionIsNotRetriable() {
        ProxyResult result = ProxyResult.connectionError("b1", new InterruptedException("interrupted"));
        assertFalse(result.isRetriable(), "InterruptedException should not be retriable");
    }

    @Test
    void http502IsRetriable() {
        ProxyResult result = new ProxyResult(502, Map.of(), new ByteArrayInputStream(new byte[0]), "b1", null);
        assertTrue(result.isRetriable());
    }

    @Test
    void http503IsRetriable() {
        ProxyResult result = new ProxyResult(503, Map.of(), new ByteArrayInputStream(new byte[0]), "b1", null);
        assertTrue(result.isRetriable());
    }

    @Test
    void http504IsRetriable() {
        ProxyResult result = new ProxyResult(504, Map.of(), new ByteArrayInputStream(new byte[0]), "b1", null);
        assertTrue(result.isRetriable());
    }

    @Test
    void http200IsNotRetriable() {
        ProxyResult result = new ProxyResult(200, Map.of(), new ByteArrayInputStream("ok".getBytes()), "b1", null);
        assertFalse(result.isRetriable());
    }

    @Test
    void http404IsNotRetriable() {
        ProxyResult result = new ProxyResult(404, Map.of(), new ByteArrayInputStream("not found".getBytes()), "b1", null);
        assertFalse(result.isRetriable());
    }

    @Test
    void http500IsNotRetriable() {
        // 500 is an application error, not a gateway/proxy error — don't retry
        ProxyResult result = new ProxyResult(500, Map.of(), new ByteArrayInputStream("error".getBytes()), "b1", null);
        assertFalse(result.isRetriable());
    }

    @Test
    void writeToSetsStatusAndBody() throws Exception {
        // Create a result with known content
        Map<String, List<String>> headers = Map.of(
                "X-Custom", List.of("test-value")
        );
        byte[] body = "{\"status\":\"ok\"}".getBytes();
        ProxyResult result = new ProxyResult(200, headers, new ByteArrayInputStream(body), "b1", null);

        // Start a server that lets us test writeTo via the proxy
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            try {
                result.writeTo(exchange);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/test"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("ok"));
            // Proxy identification headers should be set
            assertNotNull(response.headers().firstValue("X-Proxy").orElse(null));
            assertEquals("load-balancer", response.headers().firstValue("X-Proxy").orElse(""));
            // Custom header should be forwarded
            assertEquals("test-value", response.headers().firstValue("X-Custom").orElse(""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void writeToHandlesConnectionError() throws Exception {
        ProxyResult result = ProxyResult.connectionError("b1",
                new IOException("Connection refused"));

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            try {
                result.writeTo(exchange);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/test"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(502, response.statusCode());
            assertTrue(response.body().contains("bad gateway"));
        } finally {
            server.stop(0);
        }
    }
}
