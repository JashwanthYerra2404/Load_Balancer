package pool

import (
	"math/rand/v2"
	"net/http"
	"sync"

	"go.uber.org/zap"
)

// RandomPool selects a random healthy backend for each request.
//
// This is the simplest load balancing algorithm. It provides a statistical
// approximation of round-robin without the overhead of maintaining a counter.
//
// Properties:
//   - Statistically uniform distribution over many requests.
//   - No shared mutable state for selection (rand/v2 is concurrent-safe).
//   - Slightly less predictable than round-robin — distribution is probabilistic,
//     not deterministic.
//
// When to use:
//   - When simplicity is more important than perfect distribution.
//   - As a baseline for benchmarking other algorithms.
//   - In environments where adding a counter would create contention
//     (though atomic.Uint64 is already very fast).
//
// Concurrency:
//   - rand/v2 functions are concurrent-safe (Go 1.22+). No explicit seeding
//     needed — the runtime seeds it automatically.
//   - Backend slice is protected by sync.RWMutex (read-locked per request).
//
// Time complexity: O(n) worst case (all backends dead except one).
// Space complexity: O(1) beyond the backend slice.
type RandomPool struct {
	backends []*Backend
	mu       sync.RWMutex
	logger   *zap.Logger
}

// NewRandomPool creates a new random backend pool.
func NewRandomPool(logger *zap.Logger) *RandomPool {
	return &RandomPool{
		backends: make([]*Backend, 0),
		logger:   logger,
	}
}

// Next selects a random healthy backend.
//
// Algorithm:
//  1. Pick a random starting index.
//  2. Scan forward until a healthy backend is found.
//  3. Return nil if no healthy backends exist.
//
// The scan ensures we always find a healthy backend if one exists,
// while the random start provides the uniform distribution.
func (p *RandomPool) Next(r *http.Request) *Backend {
	p.mu.RLock()
	defer p.mu.RUnlock()

	backendCount := len(p.backends)
	if backendCount == 0 {
		return nil
	}

	// Pick a random starting index.
	start := rand.IntN(backendCount)

	// Scan forward from the random start to find a healthy backend.
	for i := 0; i < backendCount; i++ {
		idx := (start + i) % backendCount
		b := p.backends[idx]

		if b.IsAlive() && !b.IsAtCapacity() {
			return b
		}
	}

	p.logger.Warn("no healthy backends available",
		zap.Int("total_backends", backendCount),
	)
	return nil
}

// Backends returns all backends in the pool.
func (p *RandomPool) Backends() []*Backend {
	p.mu.RLock()
	defer p.mu.RUnlock()

	result := make([]*Backend, len(p.backends))
	copy(result, p.backends)
	return result
}

// AddBackend adds a backend to the pool.
func (p *RandomPool) AddBackend(b *Backend) {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.backends = append(p.backends, b)
	p.logger.Info("backend added to random pool",
		zap.String("backend", b.Name()),
		zap.String("url", b.URL()),
		zap.Int("pool_size", len(p.backends)),
	)
}
