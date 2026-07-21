// Package proxy implements the HTTP handler that ties together backend
// selection and request forwarding.
//
// Architecture evolution (Phase 1 → Phase 2):
//   Phase 1: proxy owned a single httputil.ReverseProxy and hardcoded backend URL.
//   Phase 2: proxy delegates to a BackendPool for backend selection. Each Backend
//            owns its own httputil.ReverseProxy. The proxy is now a thin orchestrator.
//
// Why this separation matters:
//   - The proxy package answers: "How do we handle an incoming request?"
//   - The pool package answers: "Which backend should handle this request?"
//   - The backend struct answers: "How do we forward to a specific backend?"
//
// Each concern is independently testable and replaceable.
//
// Concurrency:
//   ServeHTTP is called concurrently by http.Server (one goroutine per request).
//   The pool.Next() call is thread-safe (uses atomics internally).
//   Backend.ServeHTTP() is thread-safe (httputil.ReverseProxy is designed for this).
package proxy

import (
	"fmt"
	"net/http"

	"go.uber.org/zap"

	"github.com/JashwanthYerra2404/Load_Balancer/internal/pool"
)

// ReverseProxy is the main HTTP handler for the load balancer.
//
// It selects a backend from the pool for each request and forwards
// the request to it. If no backends are available, it returns 503.
//
// Design: This is intentionally thin. All the complexity lives in:
//   - pool.BackendPool: backend selection algorithm
//   - pool.Backend: HTTP forwarding, header manipulation, error handling
//
// This struct is the "glue" between the HTTP server and the backend pool.
type ReverseProxy struct {
	// pool provides backend selection. The proxy doesn't know (or care)
	// which algorithm the pool uses — round-robin, least connections, etc.
	pool pool.BackendPool

	// logger for request-level logging.
	logger *zap.Logger
}

// New creates a new ReverseProxy with the given backend pool.
//
// The pool must be populated with at least one backend before serving
// traffic, but this is not enforced here — a startup check in main()
// is more appropriate.
func New(backendPool pool.BackendPool, logger *zap.Logger) *ReverseProxy {
	return &ReverseProxy{
		pool:   backendPool,
		logger: logger,
	}
}

// ServeHTTP implements the http.Handler interface.
//
// Request flow:
//  1. Ask the pool for a backend (pool.Next)
//  2. If no backend available → 503 Service Unavailable
//  3. If backend available → forward request via backend.ServeHTTP
//
// Why 503 (not 502)?
//   - 502 Bad Gateway: "I tried to reach a backend but it failed"
//   - 503 Service Unavailable: "I have no backends to try"
//   These are semantically different. 503 tells the client "try later"
//   (the Retry-After header can be added). 502 says "something is broken
//   right now." This distinction matters for client retry logic and
//   monitoring/alerting.
func (rp *ReverseProxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	backend := rp.pool.Next(r)

	if backend == nil {
		rp.logger.Error("no available backends",
			zap.String("method", r.Method),
			zap.String("path", r.URL.Path),
			zap.String("remote_addr", r.RemoteAddr),
		)

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusServiceUnavailable)
		fmt.Fprintf(w, `{"error": "service unavailable", "message": "no healthy backends", "status": 503}`)
		return
	}

	rp.logger.Info("proxying request",
		zap.String("method", r.Method),
		zap.String("path", r.URL.Path),
		zap.String("remote_addr", r.RemoteAddr),
		zap.String("backend", backend.Name()),
		zap.Int64("backend_active_conns", backend.ActiveConnections()),
	)

	backend.ServeHTTP(w, r)
}
