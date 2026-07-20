// Package proxy implements the core reverse proxy functionality.
//
// This package wraps Go's httputil.ReverseProxy with production-grade
// configuration: custom Transport with connection pooling and timeouts,
// request/response modification, and structured error handling.
//
// Architecture:
//   - ReverseProxy struct encapsulates all proxy logic and implements http.Handler.
//   - The Director function rewrites incoming requests to target the backend.
//   - ModifyResponse adds proxy identification headers to responses.
//   - ErrorHandler returns structured error responses when the backend is unreachable.
//
// Why httputil.ReverseProxy?
//   - Battle-tested in production by projects like Traefik, Caddy, and the Go standard library.
//   - Correctly handles hop-by-hop headers, chunked transfer encoding, WebSocket upgrades,
//     and streaming bodies.
//   - Building from scratch would require handling hundreds of HTTP edge cases with
//     significant security risk.
//
// Concurrency:
//   - ReverseProxy is safe for concurrent use. Each request gets its own goroutine
//     (handled by http.Server), and httputil.ReverseProxy is designed for this.
//   - The underlying http.Transport maintains a connection pool with goroutine-safe
//     access via internal mutexes.
//
// Performance:
//   - Connection pooling avoids TCP handshake overhead (~1.5 RTT per new connection).
//   - Streaming (no body buffering) keeps memory usage O(1) regardless of request size.
//   - Custom Transport limits are tuned for typical reverse proxy workloads.
package proxy

import (
	"fmt"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"time"

	"go.uber.org/zap"
)

// ReverseProxy wraps httputil.ReverseProxy with production configuration.
//
// It implements http.Handler so it can be used directly with http.Server.
// This struct owns the lifecycle of the underlying transport and proxy.
//
// Design: We use composition rather than embedding httputil.ReverseProxy
// to control the public API surface. Embedding would expose internal
// methods that callers shouldn't use directly.
type ReverseProxy struct {
	// proxy is the underlying Go reverse proxy. We configure it with
	// custom Director, ModifyResponse, ErrorHandler, and Transport.
	proxy *httputil.ReverseProxy

	// backendURL is the parsed URL of the upstream backend server.
	// Stored for logging and diagnostics.
	backendURL *url.URL

	// logger is the structured logger for request/error logging.
	logger *zap.Logger
}

// New creates a new ReverseProxy that forwards requests to the given backend URL.
//
// Parameters:
//   - backendRawURL: The full URL of the backend (e.g., "http://localhost:9001").
//     Must include scheme (http/https) and host.
//   - logger: Structured logger for operational visibility.
//
// Returns an error if the backend URL is invalid.
//
// The returned proxy is safe for concurrent use across multiple goroutines.
func New(backendRawURL string, logger *zap.Logger) (*ReverseProxy, error) {
	backendURL, err := url.Parse(backendRawURL)
	if err != nil {
		return nil, fmt.Errorf("parsing backend URL %q: %w", backendRawURL, err)
	}

	rp := &ReverseProxy{
		backendURL: backendURL,
		logger:     logger,
	}

	// Create the underlying reverse proxy with our custom configuration.
	rp.proxy = &httputil.ReverseProxy{
		Director:       rp.director,
		ModifyResponse: rp.modifyResponse,
		ErrorHandler:   rp.errorHandler,
		Transport:      newTransport(),
	}

	return rp, nil
}

// ServeHTTP implements the http.Handler interface.
//
// This is the entry point for every proxied request. The http.Server calls
// this in a new goroutine for each incoming connection.
func (rp *ReverseProxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	rp.logger.Info("proxying request",
		zap.String("method", r.Method),
		zap.String("path", r.URL.Path),
		zap.String("remote_addr", r.RemoteAddr),
		zap.String("backend", rp.backendURL.String()),
	)

	rp.proxy.ServeHTTP(w, r)
}

// director rewrites the incoming request to target the backend server.
//
// This function is called by httputil.ReverseProxy for every request before
// it's sent to the backend. It runs in the same goroutine as ServeHTTP.
//
// What we do:
//  1. Set the scheme and host to the backend's values.
//  2. Preserve the original request path and query string.
//  3. Set the Host header to the backend's host (so the backend can do
//     virtual hosting if needed).
//  4. Add X-Forwarded-For if not already present (httputil.ReverseProxy
//     does this automatically, but we add X-Forwarded-Host as well).
//  5. Add X-Real-IP with the client's actual IP address.
//
// Why we set Host explicitly:
//   Go's httputil.ReverseProxy sets req.URL but doesn't change req.Host.
//   The net/http client uses req.Host for the Host header if set, falling
//   back to req.URL.Host. By setting req.Host = backendURL.Host, we ensure
//   the backend receives its own hostname, which is the standard behavior
//   for reverse proxies in "pass" mode.
//
//   In later phases, we may add a config option to preserve the original
//   Host header (useful for name-based virtual hosting).
func (rp *ReverseProxy) director(req *http.Request) {
	req.URL.Scheme = rp.backendURL.Scheme
	req.URL.Host = rp.backendURL.Host
	req.Host = rp.backendURL.Host

	// Add X-Real-IP header with the client's IP (without port).
	// This is simpler than X-Forwarded-For for backends that just need
	// the originating client IP.
	if clientIP, _, err := net.SplitHostPort(req.RemoteAddr); err == nil {
		req.Header.Set("X-Real-IP", clientIP)
	}

	// Note: httputil.ReverseProxy automatically appends to X-Forwarded-For.
	// We don't need to handle it manually.
}

// modifyResponse is called after the backend responds but before the
// response is sent to the client.
//
// We add a "Via" header to indicate this response passed through our proxy.
// This is defined in RFC 7230 Section 5.7.1 and is standard practice for
// proxies. It aids debugging in multi-proxy architectures.
func (rp *ReverseProxy) modifyResponse(resp *http.Response) error {
	resp.Header.Set("X-Proxy", "load-balancer")
	resp.Header.Add("Via", "1.1 load-balancer")
	return nil
}

// errorHandler is called when the proxy cannot reach the backend or the
// backend returns an error during the connection phase (not HTTP errors).
//
// Common scenarios:
//   - Backend is down (connection refused)
//   - Backend is too slow (timeout)
//   - DNS resolution failure
//   - TLS handshake failure
//
// We return 502 Bad Gateway, which is the standard HTTP status code for
// "the server, while acting as a gateway or proxy, received an invalid
// response from the upstream server."
//
// In Phase 6 (Retry), this is where we'll add retry logic.
// In Phase 7 (Circuit Breaker), this feeds into the circuit breaker state.
func (rp *ReverseProxy) errorHandler(w http.ResponseWriter, r *http.Request, err error) {
	rp.logger.Error("proxy error",
		zap.String("method", r.Method),
		zap.String("path", r.URL.Path),
		zap.String("backend", rp.backendURL.String()),
		zap.Error(err),
	)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusBadGateway)

	// Write a structured JSON error response. This is better than a plain
	// text error because API clients can parse it programmatically.
	fmt.Fprintf(w, `{"error": "bad gateway", "message": "backend unavailable", "status": 502}`)
}

// newTransport creates an http.Transport configured for reverse proxy workloads.
//
// Why not use http.DefaultTransport?
//   The default transport has unlimited idle connections and conservative timeouts.
//   In a reverse proxy, we need:
//   - Bounded connection pools (prevent resource exhaustion)
//   - Aggressive timeouts (detect dead backends quickly)
//   - Keep-alive tuning (balance connection reuse vs. stale connections)
//
// Tuning rationale:
//   - MaxIdleConns=100: Global limit across all backends. In Phase 2 with
//     multiple backends, this prevents any single backend from monopolizing
//     the connection pool.
//   - MaxIdleConnsPerHost=10: Per-backend limit. With one backend in Phase 1,
//     this allows 10 persistent connections. This should handle ~1000 RPS
//     assuming <10ms average response time.
//   - IdleConnTimeout=90s: Matches common load balancer defaults (AWS ALB uses 60s).
//     Long enough to survive brief traffic lulls, short enough to release
//     unused connections.
//   - DialTimeout=30s: Time to establish a TCP connection. 30s is generous;
//     in practice, local connections establish in <1ms. This covers cross-DC
//     scenarios.
//   - ResponseHeaderTimeout=30s: Time to receive the first response header
//     after sending the request. Protects against backends that accept
//     connections but never respond.
func newTransport() *http.Transport {
	return &http.Transport{
		MaxIdleConns:        100,
		MaxIdleConnsPerHost: 10,
		IdleConnTimeout:     90 * time.Second,
		DialContext: (&net.Dialer{
			Timeout:   30 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
		// ForceAttemptHTTP2 enables HTTP/2 support when connecting to
		// backends over TLS. This is a performance optimization for
		// HTTPS backends — HTTP/2 multiplexes requests over a single
		// TCP connection, reducing latency and connection overhead.
		ForceAttemptHTTP2: true,
	}
}
