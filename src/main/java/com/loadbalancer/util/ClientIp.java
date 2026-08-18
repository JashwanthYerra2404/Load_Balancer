package com.loadbalancer.util;

import com.sun.net.httpserver.HttpExchange;

import java.net.InetSocketAddress;

/**
 * Extracts the real client IP from an HTTP exchange.
 *
 * <p>Resolution order (standard reverse-proxy convention):
 * <ol>
 *   <li>{@code X-Forwarded-For} — first (leftmost) entry is the original client</li>
 *   <li>{@code X-Real-IP} — common single-IP variant</li>
 *   <li>Raw socket remote address</li>
 * </ol>
 *
 * <p>Shared by {@code IPHashPool} (routing) and the rate limiter
 * (per-client limiting) so both identify clients identically.
 */
public final class ClientIp {

    private ClientIp() {
        // Utility class — no instantiation
    }

    /**
     * Resolves the client IP for the given exchange.
     *
     * @return the client IP, or "unknown" if no address can be determined
     */
    public static String extract(HttpExchange exchange) {
        if (exchange == null) return "unknown";

        String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            // Can contain multiple IPs like "client, proxy1, proxy2"
            return xff.split(",")[0].trim();
        }

        String xRealIP = exchange.getRequestHeaders().getFirst("X-Real-IP");
        if (xRealIP != null && !xRealIP.isEmpty()) {
            return xRealIP;
        }

        InetSocketAddress remote = exchange.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return remote.getAddress().getHostAddress();
        }
        return "unknown";
    }
}
