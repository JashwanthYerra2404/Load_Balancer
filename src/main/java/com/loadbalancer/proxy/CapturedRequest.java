package com.loadbalancer.proxy;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Captures an incoming HTTP request upfront so it can be replayed on retries.
 *
 * <p>The problem: {@link HttpExchange#getRequestBody()} is a stream — once read,
 * it's gone. For retries, we need to send the same request body to multiple
 * backends. This class reads and buffers the body once during capture.
 *
 * <p><b>Memory safety and Idempotency:</b> 
 * - Non-idempotent methods (e.g., POST) are NEVER buffered and NEVER retried. Their stream is passed directly.
 * - Idempotent methods (e.g., PUT) with bodies larger than {@link #MAX_BODY_SIZE} are passed as streams and not retried.
 * - Idempotent methods with small or no bodies are fully captured for retry capability.
 *
 * @param method        HTTP method (GET, POST, PUT, etc.)
 * @param requestURI    Full request URI including path and query
 * @param headers       Request headers (filtered — hop-by-hop headers removed)
 * @param body          Request body bytes (null if streamed or bodyless)
 * @param bodyStream    Request body stream (null if buffered or bodyless)
 * @param remoteAddress Client's remote address (for proxy headers)
 * @param retriable     Whether this request can be safely retried
 */
public record CapturedRequest(
        String method,
        URI requestURI,
        Map<String, List<String>> headers,
        byte[] body,
        InputStream bodyStream,
        InetSocketAddress remoteAddress,
        boolean retriable
) {
    /**
     * Max body size we'll buffer for retry capability.
     * Requests with larger bodies get a single attempt (no retry).
     * 1 MB covers 99% of API requests (JSON, form data).
     */
    public static final int MAX_BODY_SIZE = 1_048_576; // 1 MB

    /** Request headers that should NOT be forwarded to the backend. */
    private static final Set<String> SKIP_HEADERS = Set.of(
            "host", "connection", "transfer-encoding", "keep-alive",
            "proxy-authorization", "content-length", "upgrade",
            "te", "trailer", "x-real-ip", "x-forwarded-for"
    );

    /**
     * Captures a request from an {@link HttpExchange}.
     */
    public static CapturedRequest from(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        URI requestURI = exchange.getRequestURI();
        InetSocketAddress remoteAddress = exchange.getRemoteAddress();

        // Capture filtered headers
        Map<String, List<String>> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((key, values) -> {
            if (!SKIP_HEADERS.contains(key.toLowerCase())) {
                headers.put(key, List.copyOf(values));
            }
        });

        boolean idempotent = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method) || "TRACE".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);

        byte[] body = null;
        InputStream bodyStream = null;
        boolean retriable = idempotent;
        
        // If it's a method that might have a body
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
            
            // We use the Content-Length header to make a quick decision on buffering.
            // If it's missing or chunked, we stream it to be safe (unless we force buffer, but streaming is safer).
            long contentLength = -1;
            String clHeader = exchange.getRequestHeaders().getFirst("Content-Length");
            if (clHeader != null) {
                try {
                    contentLength = Long.parseLong(clHeader);
                } catch (NumberFormatException ignored) {}
            }

            if (!idempotent || contentLength > MAX_BODY_SIZE || contentLength == -1) {
                // Do not buffer non-idempotent requests or large/unknown sized payloads.
                bodyStream = exchange.getRequestBody();
                retriable = false;
            } else {
                // Buffer small idempotent requests
                body = exchange.getRequestBody().readAllBytes();
                if (body.length > MAX_BODY_SIZE) {
                    retriable = false;
                }
            }
        }

        return new CapturedRequest(method, requestURI, headers, body, bodyStream, remoteAddress, retriable);
    }
}
