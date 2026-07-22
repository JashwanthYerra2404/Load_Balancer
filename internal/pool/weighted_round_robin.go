package pool

import (
	"net/http"
	"sync"

	"go.uber.org/zap"
)

// WeightedRoundRobinPool distributes requests proportionally to backend weights
// using the Smooth Weighted Round Robin (SWRR) algorithm.
//
// This is the same algorithm used by Nginx (upstream module), HAProxy, and Envoy.
// It was originally described by the Nginx team and provides smooth distribution
// without request bursts.
//
// Why "smooth"?
//   With 3 backends weighted 5:1:1, a naive approach would send 5 requests to
//   backend A, then 1 to B, then 1 to C — a "burst" on A. SWRR interleaves:
//   A, A, B, A, C, A, A — spreading A's load more evenly over time.
//
// Algorithm (on each Next() call):
//  1. For each healthy backend i: currentWeight[i] += effectiveWeight[i]
//  2. Select the backend with the highest currentWeight.
//  3. Subtract totalWeight from the selected backend's currentWeight.
//
// The effectiveWeight equals the backend's configured weight. The currentWeight
// oscillates around zero over time, naturally producing smooth distribution.
//
// Concurrency:
//   Uses sync.Mutex (not RWMutex) because every Next() call writes to
//   currentWeights. A plain Mutex has ~20ns overhead vs ~30ns for RWMutex
//   write-lock. Since every call is a write, Mutex is strictly better here.
//
// Time complexity: O(n) per call.
// Space complexity: O(n) for the currentWeights slice.
type WeightedRoundRobinPool struct {
	backends       []*Backend
	currentWeights []int // per-backend running weights, modified on every call
	mu             sync.Mutex
	logger         *zap.Logger
}

// NewWeightedRoundRobinPool creates a new weighted round-robin backend pool.
func NewWeightedRoundRobinPool(logger *zap.Logger) *WeightedRoundRobinPool {
	return &WeightedRoundRobinPool{
		backends:       make([]*Backend, 0),
		currentWeights: make([]int, 0),
		logger:         logger,
	}
}

// Next selects the next backend using smooth weighted round-robin.
//
// The algorithm ensures that over any window of totalWeight requests,
// each backend receives exactly weight[i] requests. The "smooth" property
// means these requests are interleaved rather than batched.
func (p *WeightedRoundRobinPool) Next(r *http.Request) *Backend {
	p.mu.Lock()
	defer p.mu.Unlock()

	backendCount := len(p.backends)
	if backendCount == 0 {
		return nil
	}

	// Step 1: Calculate totalWeight of healthy backends and increment
	// currentWeights for each healthy backend.
	totalWeight := 0
	bestIdx := -1
	bestWeight := -1 << 31 // min int

	for i, b := range p.backends {
		if !b.IsAlive() || b.IsAtCapacity() {
			continue
		}

		weight := b.Weight()
		totalWeight += weight

		// Increment this backend's current weight by its effective weight.
		p.currentWeights[i] += weight

		// Track the backend with the highest current weight.
		if p.currentWeights[i] > bestWeight {
			bestWeight = p.currentWeights[i]
			bestIdx = i
		}
	}

	// No healthy backends available.
	if bestIdx == -1 {
		p.logger.Warn("no healthy backends available",
			zap.Int("total_backends", backendCount),
		)
		return nil
	}

	// Step 2: Subtract totalWeight from the selected backend's current weight.
	// This is what creates the smooth distribution — the selected backend's
	// weight drops, making other backends more likely to be chosen next.
	p.currentWeights[bestIdx] -= totalWeight

	return p.backends[bestIdx]
}

// Backends returns all backends in the pool.
func (p *WeightedRoundRobinPool) Backends() []*Backend {
	p.mu.Lock()
	defer p.mu.Unlock()

	result := make([]*Backend, len(p.backends))
	copy(result, p.backends)
	return result
}

// AddBackend adds a backend to the pool.
func (p *WeightedRoundRobinPool) AddBackend(b *Backend) {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.backends = append(p.backends, b)
	p.currentWeights = append(p.currentWeights, 0)
	p.logger.Info("backend added to weighted round-robin pool",
		zap.String("backend", b.Name()),
		zap.String("url", b.URL()),
		zap.Int("weight", b.Weight()),
		zap.Int("pool_size", len(p.backends)),
	)
}
