package com.loadbalancer.proxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CapturedRequest} — request capture and replay capability.
 */
class CapturedRequestTest {

    @Test
    void capturesGetRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        final CapturedRequest[] captured = new CapturedRequest[1];

        server.createContext("/", exchange -> {
            try {
                captured[0] = CapturedRequest.from(exchange);
                exchange.sendResponseHeaders(200, -1);
                exchange.getResponseBody().close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/api/test?q=hello"))
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertNotNull(captured[0]);
            assertEquals("GET", captured[0].method());
            assertEquals("/api/test", captured[0].requestURI().getRawPath());
            assertEquals("q=hello", captured[0].requestURI().getRawQuery());
            assertNull(captured[0].body(), "GET requests should have no body");
            assertTrue(captured[0].retriable(), "GET requests are always retriable");
            assertNotNull(captured[0].remoteAddress());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsPostWithoutBuffering() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        final CapturedRequest[] captured = new CapturedRequest[1];

        server.createContext("/", exchange -> {
            try {
                captured[0] = CapturedRequest.from(exchange);
                // Consume the stream so the test client doesn't hang
                if (captured[0].bodyStream() != null) {
                    captured[0].bodyStream().readAllBytes();
                }
                exchange.sendResponseHeaders(200, -1);
                exchange.getResponseBody().close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            String requestBody = "{\"name\":\"test\"}";
            client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/api/create"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertNotNull(captured[0]);
            assertEquals("POST", captured[0].method());
            assertNull(captured[0].body(), "POST bodies are not buffered");
            assertNotNull(captured[0].bodyStream(), "POST bodies should be streamed");
            assertFalse(captured[0].retriable(), "POST requests are not retriable");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void filtersHopByHopHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        final CapturedRequest[] captured = new CapturedRequest[1];

        server.createContext("/", exchange -> {
            try {
                captured[0] = CapturedRequest.from(exchange);
                exchange.sendResponseHeaders(200, -1);
                exchange.getResponseBody().close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/test"))
                            .header("Accept", "text/html")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertNotNull(captured[0]);
            // Host and Connection headers should be filtered out
            assertFalse(captured[0].headers().containsKey("Host"),
                    "Host header should be filtered");
            assertFalse(captured[0].headers().containsKey("Connection"),
                    "Connection header should be filtered");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bodyIsReplayableForSmallPut() throws Exception {
        // Verify the same captured body can be read multiple times
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        final CapturedRequest[] captured = new CapturedRequest[1];

        server.createContext("/", exchange -> {
            try {
                captured[0] = CapturedRequest.from(exchange);
                exchange.sendResponseHeaders(200, -1);
                exchange.getResponseBody().close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            String body = "replay-test-body";
            client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/test"))
                            .PUT(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertNotNull(captured[0]);
            // Read body multiple times — should be identical each time
            assertNotNull(captured[0].body(), "PUT bodies are buffered if small");
            assertEquals(body, new String(captured[0].body()));
            assertEquals(body, new String(captured[0].body()));
            assertEquals(body, new String(captured[0].body()));
            assertTrue(captured[0].retriable());
        } finally {
            server.stop(0);
        }
    }
}
