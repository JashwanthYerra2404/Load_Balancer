package pool

import (
	"net/http"
	"sync"
	"sync/atomic"

	"go.uber.org/zap"
)

// LeastConnectionsPool selects the backend with the fewest active connections.
//
// This algorithm is ideal for workloads with variable request durations. If some
// requests take 10ms and others take 500ms, round-robin will overload backends
// that happen to get the slow requests. LeastConnections naturally directs
// traffic away from busy backends.
//
// Algorithm:
//  1. Scan all backends, tracking the one with the lowest ActiveConnections().
//  2. Skip backends that are dead or at capacity.
//  3. On tie, use a round-robin counter as a tie-breaker to prevent thundering
//     herd — without this, all goroutines would pile onto backend[0] when all
//     backends have 0 connections (e.g., at startup or after a traffic lull).
//
// Concurrency:
//   - Backend connection counts use atomic.Int64 — lock-free reads.
//   - The backend slice is protected by sync.RWMutex (read-locked per request).
//   - The tie-breaker counter uses atomic.Uint64 (lock-free).
//
// Time complexity: O(n) per call where n = number of backends.
// Space complexity: O(1) beyond the backend slice.
//
// Real-world usage: This is the default algorithm in HAProxy ("leastconn")
// and is available in Nginx ("least_conn"), AWS ALB, and Envoy.
type LeastConnectionsPool struct {
	backends []*Backend
	current  atomic.Uint64 // tie-breaker for equal connection counts
	mu       sync.RWMutex
	logger   *zap.Logger
}

// NewLeastConnectionsPool creates a new least-connections backend pool.
func NewLeastConnectionsPool(logger *zap.Logger) *LeastConnectionsPool {
	return &LeastConnectionsPool{
		backends: make([]*Backend, 0),
		logger:   logger,
	}
}

// Next selects the healthy backend with the fewest active connections.
//
// When multiple backends have the same (lowest) connection count, a round-robin
// counter is used as a tie-breaker. This prevents the "thundering herd" problem
// where all requests go to the same backend when connection counts are equal
// (common at startup when all counts are 0).
func (p *LeastConnectionsPool) Next(r *http.Request) *Backend {
	p.mu.RLock()
	defer p.mu.RUnlock()

	backendCount := len(p.backends)
	if backendCount == 0 {
		return nil
	}

	var best *Backend
	bestConns := int64(1<<63 - 1) // max int64
	tieCount := 0

	// Tie-breaker: which tied backend to pick.
	tieBreaker := p.current.Add(1)

	for _, b := range p.backends {
		if !b.IsAlive() || b.IsAtCapacity() {
			continue
		}

		conns := b.ActiveConnections()
		if conns < bestConns {
			best = b
			bestConns = conns
			tieCount = 1
		} else if conns == bestConns {
			tieCount++
			// Use the tie-breaker to distribute among tied backends.
			// This ensures deterministic but varied selection.
			if int(tieBreaker%uint64(tieCount)) == tieCount-1 {
				best = b
			}
		}
	}

	if best == nil {
		p.logger.Warn("no healthy backends available",
			zap.Int("total_backends", backendCount),
		)
	}

	return best
}

// Backends returns all backends in the pool.
func (p *LeastConnectionsPool) Backends() []*Backend {
	p.mu.RLock()
	defer p.mu.RUnlock()

	result := make([]*Backend, len(p.backends))
	copy(result, p.backends)
	return result
}

// AddBackend adds a backend to the pool.
func (p *LeastConnectionsPool) AddBackend(b *Backend) {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.backends = append(p.backends, b)
	p.logger.Info("backend added to least-connections pool",
		zap.String("backend", b.Name()),
		zap.String("url", b.URL()),
		zap.Int("pool_size", len(p.backends)),
	)
}
