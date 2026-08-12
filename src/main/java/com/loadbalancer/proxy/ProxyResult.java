package com.loadbalancer.proxy;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Captures a backend response without committing to the client immediately.
 *
 * <p>This is the key architectural piece that enables retries and streaming.
 * Instead of writing directly to the {@link HttpExchange} (which is irreversible once
 * headers are sent), the backend returns a {@code ProxyResult} containing an
 * {@link InputStream}. The retry loop can inspect the status code before deciding
 * to commit or retry.
 *
 * <p><b>Lifecycle:</b>
 * <ol>
 *   <li>Backend sends request, gets HttpResponse → {@code ProxyResult}</li>
 *   <li>ProxyHandler inspects: is it retriable? retries left?</li>
 *   <li>If done → {@code result.writeTo(exchange)} streams the response to the client</li>
 * </ol>
 *
 * @param statusCode     HTTP status code from the backend (0 if connection error)
 * @param headers        Response headers from the backend (empty if connection error)
 * @param responseStream Response body stream (null if connection error)
 * @param backendName    Name of the backend that produced this result
 * @param error          Non-null if the request failed at the connection level
 */
public record ProxyResult(
        int statusCode,
        Map<String, List<String>> headers,
        InputStream responseStream,
        String backendName,
        Exception error
) {
    /** Response header keys that must NOT be forwarded to the client. */
    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
            "transfer-encoding", "connection", "content-length", "content-encoding"
    );

    /**
     * Returns true if this result represents a retriable failure.
     *
     * <p>Retriable conditions:
     * <ul>
     *   <li>Connection error (error != null, excluding InterruptedException)</li>
     *   <li>HTTP 502 Bad Gateway — upstream failure</li>
     *   <li>HTTP 503 Service Unavailable — backend overloaded</li>
     *   <li>HTTP 504 Gateway Timeout — backend too slow</li>
     * </ul>
     *
     * <p>Non-retriable: 200-499 (success or client error), InterruptedException.
     */
    public boolean isRetriable() {
        if (error != null) {
            // Don't retry if the thread was interrupted
            return !(error instanceof InterruptedException);
        }
        return statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    /**
     * Writes this result to the client's {@link HttpExchange}.
     *
     * <p>This is the "commit" operation — once called, the response is streamed
     * to the client and cannot be undone. The retry loop calls this only
     * when it's done retrying.
     */
    public void writeTo(HttpExchange exchange) throws IOException {
        if (error != null) {
            // Connection-level failure — send 502
            writeErrorResponse(exchange, 502,
                    String.format("backend %s unavailable", backendName));
            return;
        }

        // Forward backend response headers
        if (headers != null) {
            headers.forEach((key, values) -> {
                String lowerKey = key.toLowerCase();
                if (lowerKey.startsWith(":") || SKIP_RESPONSE_HEADERS.contains(lowerKey)) {
                    return;
                }
                for (String value : values) {
                    exchange.getResponseHeaders().add(key, value);
                }
            });
        }

        // Add proxy identification headers
        exchange.getResponseHeaders().set("X-Proxy", "load-balancer");
        exchange.getResponseHeaders().set("X-Backend-Name", backendName);
        exchange.getResponseHeaders().add("Via", "1.1 load-balancer");

        // Write status and body
        // We use chunked transfer encoding (by passing 0) since we don't know the content length 
        // after stripping the headers, and we stream it.
        exchange.sendResponseHeaders(statusCode, 0);

        if (responseStream != null) {
            try (OutputStream os = exchange.getResponseBody();
                 InputStream is = responseStream) {
                is.transferTo(os);
            }
        } else {
            exchange.getResponseBody().close();
        }
    }

    /**
     * Creates a ProxyResult representing a connection-level failure.
     */
    public static ProxyResult connectionError(String backendName, Exception e) {
        return new ProxyResult(0, Map.of(), null, backendName, e);
    }

    /**
     * Writes a JSON error response to the client.
     */
    private static void writeErrorResponse(HttpExchange exchange, int statusCode,
                                            String message) throws IOException {
        String json = String.format(
                "{\"error\":\"bad gateway\",\"message\":\"%s\",\"status\":%d}",
                message, statusCode);
        byte[] bytes = json.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
