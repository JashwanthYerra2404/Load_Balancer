// Package pool manages backend servers and provides algorithms for selecting
// which backend should handle each incoming request.
//
// Architecture:
//   - Backend: represents a single upstream server with its own reverse proxy,
//     health status, and connection tracking. All mutable state uses atomic
//     operations for lock-free concurrent access.
//   - BackendPool: interface for backend selection algorithms. Phase 2 provides
//     RoundRobinPool; Phase 3 will add LeastConnections, Weighted, IPHash, etc.
//
// Concurrency design:
//   Each incoming HTTP request runs in its own goroutine (created by http.Server).
//   All these goroutines call pool.Next() concurrently to get a backend. The pool
//   must be safe for concurrent access without introducing contention.
//
//   We achieve this through:
//   - atomic.Uint64 for the round-robin counter (lock-free, ~5ns per operation)
//   - atomic.Bool for backend alive status (lock-free reads on every request)
//   - atomic.Int64 for active connection counts (lock-free increment/decrement)
//   - sync.RWMutex on the backend slice (only write-locked when adding/removing backends)
//
// Why a separate package from proxy?
//   Separation of concerns. The proxy package handles HTTP forwarding (Director,
//   ModifyResponse, ErrorHandler). The pool package handles backend lifecycle and
//   selection. This makes each package independently testable and allows swapping
//   selection algorithms without touching HTTP logic.
package pool

import (
	"fmt"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"sync/atomic"
	"time"

	"go.uber.org/zap"
)

// Backend represents a single upstream server.
//
// Each backend has its own httputil.ReverseProxy instance with an independent
// connection pool (http.Transport). This is a deliberate design choice:
//
// Alternative: Share a single Transport across all backends.
// Problem: A slow backend (e.g., experiencing GC pauses) would exhaust shared
// connections, blocking requests to healthy backends. Per-backend transports
// provide fault isolation — a slow backend only exhausts its own pool.
//
// Memory cost: ~1KB per backend for the struct + Transport overhead.
// For typical deployments (2-20 backends), this is negligible.
type Backend struct {
	// url is the parsed URL of the backend server.
	url *url.URL

	// name is a human-readable identifier for logging and dashboards.
	name string

	// weight determines the relative traffic share in weighted algorithms.
	// Higher weight = more traffic. Ignored by simple round-robin.
	// Default: 1. Range: 1-100.
	weight int

	// alive indicates whether the backend is currently healthy.
	// Updated by health checks (Phase 4). Starts as true.
	//
	// Uses atomic.Bool for lock-free reads. Every request goroutine reads
	// this to decide whether to skip this backend. A mutex would create
	// unnecessary contention under high load.
	alive atomic.Bool

	// activeConnections tracks the number of in-flight requests.
	// Incremented when a request starts, decremented when it completes.
	//
	// Used by:
	//   - LeastConnections algorithm (Phase 3) for backend selection
	//   - MaxConnections enforcement (connection limiting)
	//   - Metrics/dashboards for monitoring
	//
	// Uses atomic.Int64 for lock-free increment/decrement.
	activeConnections atomic.Int64

	// maxConnections is the maximum number of concurrent connections allowed.
	// 0 means unlimited. When the limit is reached, the backend is temporarily
	// skipped during selection (treated as "at capacity" rather than "unhealthy").
	maxConnections int

	// proxy is the httputil.ReverseProxy instance for this backend.
	// Each backend gets its own proxy with its own Transport (connection pool).
	proxy *httputil.ReverseProxy

	// logger is the structured logger for this backend's operations.
	logger *zap.Logger
}

// NewBackend creates a new Backend with a configured reverse proxy.
//
// Parameters:
//   - rawURL: Backend server URL (e.g., "http://localhost:9001")
//   - name: Human-readable name for logging (e.g., "backend-1")
//   - weight: Traffic weight for weighted algorithms (1-100, default 1)
//   - maxConns: Max concurrent connections (0 = unlimited)
//   - logger: Structured logger
//
// Each backend gets its own httputil.ReverseProxy and http.Transport.
// This provides connection pool isolation between backends.
func NewBackend(rawURL string, name string, weight int, maxConns int, logger *zap.Logger) (*Backend, error) {
	parsedURL, err := url.Parse(rawURL)
	if err != nil {
		return nil, fmt.Errorf("parsing backend URL %q: %w", rawURL, err)
	}

	if weight <= 0 {
		weight = 1
	}

	b := &Backend{
		url:            parsedURL,
		name:           name,
		weight:         weight,
		maxConnections: maxConns,
		logger:         logger,
	}

	// Start as alive. Health checks (Phase 4) will update this.
	b.alive.Store(true)

	// Create the reverse proxy for this backend.
	b.proxy = &httputil.ReverseProxy{
		Director:       b.director,
		ModifyResponse: b.modifyResponse,
		ErrorHandler:   b.errorHandler,
		Transport:      newTransport(),
	}

	return b, nil
}

// URL returns the backend's URL string.
func (b *Backend) URL() string {
	return b.url.String()
}

// Name returns the backend's human-readable name.
func (b *Backend) Name() string {
	return b.name
}

// Weight returns the backend's traffic weight.
func (b *Backend) Weight() int {
	return b.weight
}

// IsAlive returns whether the backend is currently healthy.
//
// This is called on every request by the pool's Next() method.
// Uses atomic.Bool.Load() — lock-free, ~1ns.
func (b *Backend) IsAlive() bool {
	return b.alive.Load()
}

// SetAlive updates the backend's health status.
//
// Called by health checks (Phase 4) and circuit breakers (Phase 7).
// Uses atomic.Bool.Store() — lock-free, ~1ns.
func (b *Backend) SetAlive(alive bool) {
	b.alive.Store(alive)
	b.logger.Info("backend status changed",
		zap.String("backend", b.name),
		zap.Bool("alive", alive),
	)
}

// ActiveConnections returns the current number of in-flight requests.
func (b *Backend) ActiveConnections() int64 {
	return b.activeConnections.Load()
}

// IsAtCapacity returns true if the backend has reached its connection limit.
// Returns false if maxConnections is 0 (unlimited).
func (b *Backend) IsAtCapacity() bool {
	if b.maxConnections == 0 {
		return false
	}
	return b.activeConnections.Load() >= int64(b.maxConnections)
}

// ServeHTTP forwards the request to this backend.
//
// It tracks active connections: increments before forwarding, decrements
// after the response is complete (or on error). This ensures the count
// is always accurate, even if the backend crashes mid-response.
//
// The defer pattern guarantees decrement happens regardless of panics
// or errors in the proxy pipeline.
func (b *Backend) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	b.activeConnections.Add(1)
	defer b.activeConnections.Add(-1)

	b.proxy.ServeHTTP(w, r)
}

// director rewrites the request to target this backend.
//
// Identical logic to Phase 1, but now scoped per-backend.
// Each backend's Director targets its own URL.
func (b *Backend) director(req *http.Request) {
	req.URL.Scheme = b.url.Scheme
	req.URL.Host = b.url.Host
	req.Host = b.url.Host

	// Add X-Real-IP with the client's IP.
	if clientIP, _, err := net.SplitHostPort(req.RemoteAddr); err == nil {
		req.Header.Set("X-Real-IP", clientIP)
	}

	// Add X-Backend-Name so the client can see which backend handled the request.
	// Useful for debugging load balancing distribution.
	req.Header.Set("X-Backend-Name", b.name)
}

// modifyResponse adds proxy identification headers.
func (b *Backend) modifyResponse(resp *http.Response) error {
	resp.Header.Set("X-Proxy", "load-balancer")
	resp.Header.Set("X-Backend-Name", b.name)
	resp.Header.Add("Via", "1.1 load-balancer")
	return nil
}

// errorHandler handles connection-level errors (backend unreachable, timeout).
func (b *Backend) errorHandler(w http.ResponseWriter, r *http.Request, err error) {
	b.logger.Error("backend error",
		zap.String("backend", b.name),
		zap.String("url", b.url.String()),
		zap.String("method", r.Method),
		zap.String("path", r.URL.Path),
		zap.Error(err),
	)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusBadGateway)
	fmt.Fprintf(w, `{"error": "bad gateway", "message": "backend %s unavailable", "backend": "%s", "status": 502}`,
		b.name, b.name)
}

// newTransport creates an http.Transport configured for reverse proxy workloads.
//
// Each backend gets its own Transport for connection pool isolation.
// See Phase 1 documentation for tuning rationale.
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
		ForceAttemptHTTP2:      true,
	}
}
