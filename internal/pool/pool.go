package pool

import (
	"net/http"
	"sync"
	"sync/atomic"

	"go.uber.org/zap"
)

// BackendPool is the interface for backend selection algorithms.
//
// Why an interface?
//   In Phase 3, we'll implement multiple algorithms: RoundRobin, LeastConnections,
//   WeightedRoundRobin, Random, IPHash. Each implements this interface. The proxy
//   package doesn't care which algorithm is used — it just calls Next().
//
//   This follows the Strategy pattern: the algorithm is encapsulated behind an
//   interface and can be swapped at configuration time without changing the
//   proxy's code.
//
// Concurrency contract:
//   All methods must be safe for concurrent use from multiple goroutines.
//   The http.Server creates a new goroutine for each request, and all of
//   them call Next() concurrently.
type BackendPool interface {
	// Next selects the next backend for the given request.
	// Returns nil if no healthy backends are available.
	// The request is passed to allow algorithms like IPHash to make
	// consistent routing decisions based on client IP.
	Next(r *http.Request) *Backend

	// Backends returns all backends in the pool (healthy and unhealthy).
	// Used by health checkers to iterate all backends, and by metrics
	// to report per-backend statistics.
	Backends() []*Backend
}

// RoundRobinPool selects backends using round-robin rotation.
//
// Round-robin is the simplest load balancing algorithm: each backend gets
// requests in turn. Backend 1, Backend 2, Backend 3, Backend 1, ...
//
// Properties:
//   - Time complexity: O(n) worst case per call (when all but one backend is dead)
//   - Space complexity: O(1) beyond the backend slice
//   - Fairness: Equal distribution when all backends are healthy
//   - Deterministic: Same sequence for same number of calls
//
// Concurrency:
//   - The round-robin counter uses atomic.Uint64 for lock-free increment.
//     At 100k RPS, this adds ~500μs/second of total CPU time (5ns × 100k).
//   - The backend slice is protected by sync.RWMutex. Reads (every request)
//     take a shared lock. Writes (add/remove backend) take an exclusive lock.
//     Since writes are extremely rare (only during config reload or manual
//     changes), reads almost never contend.
//
// Limitations:
//   - Doesn't account for backend capacity or current load.
//   - A slow backend gets the same number of requests as a fast one.
//   - Phase 3 will add algorithms that address this (LeastConnections, Weighted).
type RoundRobinPool struct {
	// backends is the ordered list of backend servers.
	// Protected by mu for add/remove operations.
	backends []*Backend

	// current is the round-robin counter. Incremented atomically on each
	// call to Next(). We use modulo arithmetic to wrap around.
	//
	// Why Uint64 and not Int64?
	//   - No negative values needed.
	//   - Uint64 wraps at 2^64 (~18 quintillion), effectively never overflows.
	//   - Even at 1 million RPS, overflow would take ~584,942 years.
	current atomic.Uint64

	// mu protects the backends slice for concurrent read/write access.
	// Read lock: Next() and Backends() — called on every request.
	// Write lock: AddBackend() — called only during setup or config reload.
	mu sync.RWMutex

	logger *zap.Logger
}

// NewRoundRobinPool creates a new round-robin backend pool.
//
// The pool starts empty. Call AddBackend() to add backends.
// This pattern allows building the pool incrementally, which is
// useful when constructing from a config file where backends
// are created one at a time.
func NewRoundRobinPool(logger *zap.Logger) *RoundRobinPool {
	return &RoundRobinPool{
		backends: make([]*Backend, 0),
		logger:   logger,
	}
}

// Next selects the next healthy backend using round-robin.
//
// Algorithm:
//  1. Atomically increment the counter.
//  2. Starting from counter % len(backends), scan forward.
//  3. Return the first backend that is alive and not at capacity.
//  4. If no healthy backend is found after scanning all, return nil.
//
// The scan handles the case where some backends are down. Without it,
// we'd serve a dead backend every Nth request and return 502.
//
// Example with 3 backends where Backend 2 is down:
//
//	counter=0 → try B1 (alive) → return B1
//	counter=1 → try B2 (dead) → try B3 (alive) → return B3
//	counter=2 → try B3 (alive) → return B3
//	counter=3 → try B1 (alive) → return B1
//
// Note: When backends come back alive, the counter hasn't changed, so
// distribution naturally rebalances. No special "rebalance" logic needed.
func (p *RoundRobinPool) Next(r *http.Request) *Backend {
	p.mu.RLock()
	defer p.mu.RUnlock()

	backendCount := len(p.backends)
	if backendCount == 0 {
		return nil
	}

	// Atomically get and increment the counter.
	// This is the only contention point, and it's lock-free.
	next := p.current.Add(1)

	// Try each backend starting from the round-robin position.
	for i := 0; i < backendCount; i++ {
		// Modulo to wrap around the backend list.
		idx := int((next + uint64(i)) % uint64(backendCount))
		backend := p.backends[idx]

		if backend.IsAlive() && !backend.IsAtCapacity() {
			return backend
		}
	}

	// All backends are either dead or at capacity.
	p.logger.Warn("no healthy backends available",
		zap.Int("total_backends", backendCount),
	)
	return nil
}

// Backends returns all backends in the pool.
//
// Returns a copy of the slice header (not the underlying array) to prevent
// callers from modifying the pool's internal state. The Backend pointers
// themselves are shared, which is intentional — callers need access to
// the actual Backend objects for health checking and metrics.
func (p *RoundRobinPool) Backends() []*Backend {
	p.mu.RLock()
	defer p.mu.RUnlock()

	// Return a copy of the slice to prevent external modification.
	result := make([]*Backend, len(p.backends))
	copy(result, p.backends)
	return result
}

// AddBackend adds a backend to the pool.
//
// Thread-safe: uses an exclusive write lock. This is only called during
// startup or config reload, so the write lock doesn't impact request
// processing performance.
func (p *RoundRobinPool) AddBackend(b *Backend) {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.backends = append(p.backends, b)
	p.logger.Info("backend added to pool",
		zap.String("backend", b.Name()),
		zap.String("url", b.URL()),
		zap.Int("pool_size", len(p.backends)),
	)
}
