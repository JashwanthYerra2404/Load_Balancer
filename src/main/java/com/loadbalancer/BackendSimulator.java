package com.loadbalancer;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A standalone backend server simulator for testing and demonstrating the load balancer.
 *
 * <p>Supports command-line flags:
 * <ul>
 *   <li>{@code --port <port>} (default: 9001)</li>
 *   <li>{@code --name <name>} (default: backend-1)</li>
 *   <li>{@code --latency <ms>} (default: 0)</li>
 * </ul>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /} - Server identity, timestamp, and forwarded headers</li>
 *   <li>{@code GET /health} - Health check endpoint (always returns 200 OK)</li>
 *   <li>{@code GET /slow} - Simulated slow response (3-second delay)</li>
 *   <li>{@code GET /error} - Simulated failure (always returns 500 Internal Server Error)</li>
 *   <li>{@code GET /echo} - Echoes back HTTP request method, URI, headers, and body</li>
 * </ul>
 */
public class BackendSimulator {

    private static final Logger logger = LoggerFactory.getLogger(BackendSimulator.class);

    public static void main(String[] args) {
        int port = 9001;
        String name = "backend-1";
        long latencyMs = 0;

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--name" -> name = args[i + 1];
                case "--latency" -> latencyMs = Long.parseLong(args[i + 1]);
            }
        }

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);

            final String serverName = name;
            final long baseLatency = latencyMs;

            server.createContext("/health", exchange -> {
                applyLatency(baseLatency);
                sendResponse(exchange, 200, "OK\n");
            });

            server.createContext("/slow", exchange -> {
                applyLatency(baseLatency + 3000);
                sendResponse(exchange, 200, "Slow response from " + serverName + "\n");
            });

            server.createContext("/error", exchange -> {
                applyLatency(baseLatency);
                sendResponse(exchange, 500, "Simulated error from " + serverName + "\n");
            });

            server.createContext("/echo", exchange -> {
                applyLatency(baseLatency);
                StringBuilder sb = new StringBuilder();
                sb.append("Method: ").append(exchange.getRequestMethod()).append("\n");
                sb.append("URI: ").append(exchange.getRequestURI()).append("\n");
                sb.append("Headers:\n");
                exchange.getRequestHeaders().forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
                try (InputStream is = exchange.getRequestBody()) {
                    byte[] body = is.readAllBytes();
                    if (body.length > 0) {
                        sb.append("Body:\n").append(new String(body, StandardCharsets.UTF_8)).append("\n");
                    }
                }
                sendResponse(exchange, 200, sb.toString());
            });

            server.createContext("/", exchange -> {
                applyLatency(baseLatency);
                StringBuilder sb = new StringBuilder();
                sb.append("Hello from ").append(serverName)
                        .append(" (port ").append(exchange.getLocalAddress().getPort()).append(")\n");
                sb.append("Timestamp: ").append(Instant.now()).append("\n");
                sb.append("Client IP: ").append(exchange.getRemoteAddress().getAddress().getHostAddress()).append("\n");

                Headers headers = exchange.getRequestHeaders();
                if (headers.containsKey("X-Forwarded-For")) {
                    sb.append("X-Forwarded-For: ").append(headers.getFirst("X-Forwarded-For")).append("\n");
                }
                if (headers.containsKey("X-Real-IP")) {
                    sb.append("X-Real-IP: ").append(headers.getFirst("X-Real-IP")).append("\n");
                }
                if (headers.containsKey("Via")) {
                    sb.append("Via: ").append(headers.getFirst("Via")).append("\n");
                }
                if (headers.containsKey("X-Backend-Name")) {
                    sb.append("X-Backend-Name: ").append(headers.getFirst("X-Backend-Name")).append("\n");
                }

                sendResponse(exchange, 200, sb.toString());
            });

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Stopping backend simulator {}...", serverName);
                server.stop(5);
                executor.shutdown();
            }, "backend-shutdown"));

            logger.info("Backend simulator '{}' starting on port {} (latency: {}ms)", serverName, port, baseLatency);
            server.start();

            synchronized (BackendSimulator.class) {
                BackendSimulator.class.wait();
            }

        } catch (Exception e) {
            logger.error("Backend simulator error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void applyLatency(long latencyMs) {
        if (latencyMs > 0) {
            try {
                TimeUnit.MILLISECONDS.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
