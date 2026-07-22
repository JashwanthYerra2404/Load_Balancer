package pool

import (
	"hash/fnv"
	"net"
	"net/http"
	"sync"

	"go.uber.org/zap"
)

// IPHashPool routes all requests from the same client IP to the same backend.
//
// This provides "sticky sessions" without requiring cookies or application-level
// session management. Common use cases:
//   - In-memory session stores (the session lives on one backend)
//   - Caching layers (maximize cache hit rates by routing similar requests together)
//   - WebSocket connections (ensure upgrade goes to the same backend)
//
// Algorithm:
//  1. Extract client IP from r.RemoteAddr (strip port).
//  2. Hash the IP using FNV-1a (fast, non-cryptographic, good distribution).
//  3. Map hash to backend index: hash % len(backends).
//  4. If that backend is unhealthy, scan forward (like RoundRobin fallback).
//
// The forward-scan fallback means that when a backend goes down, only its
// clients are redistributed — other clients are unaffected. When the backend
// recovers, its clients return to it (assuming the pool hasn't changed).
//
// Why FNV-1a and not CRC32/xxHash/SipHash?
//   - FNV-1a is in the Go stdlib (hash/fnv), zero dependencies.
//   - ~2ns per hash for typical IPs.
//   - Good distribution for IP addresses (varies in lower bytes).
//   - Not cryptographic, but we don't need that — we need speed and distribution.
//
// Concurrency:
//   - The hash computation is per-goroutine (no shared state).
//   - Backend slice is protected by sync.RWMutex (read-locked per request).
//
// Time complexity: O(1) best case, O(n) worst case (all backends dead except one).
// Space complexity: O(1) beyond the backend slice.
type IPHashPool struct {
	backends []*Backend
	mu       sync.RWMutex
	logger   *zap.Logger
}

// NewIPHashPool creates a new IP-hash backend pool.
func NewIPHashPool(logger *zap.Logger) *IPHashPool {
	return &IPHashPool{
		backends: make([]*Backend, 0),
		logger:   logger,
	}
}

// Next selects a backend based on the client's IP address.
//
// The same client IP will always be routed to the same backend, as long as
// that backend is healthy and the pool size hasn't changed.
func (p *IPHashPool) Next(r *http.Request) *Backend {
	p.mu.RLock()
	defer p.mu.RUnlock()

	backendCount := len(p.backends)
	if backendCount == 0 {
		return nil
	}

	// Extract client IP (strip port from RemoteAddr).
	clientIP := extractIP(r.RemoteAddr)

	// Hash the client IP to get a backend index.
	idx := hashIP(clientIP) % uint32(backendCount)

	// Try the hashed backend first, then scan forward if it's unhealthy.
	for i := 0; i < backendCount; i++ {
		target := int((uint32(i) + idx) % uint32(backendCount))
		b := p.backends[target]

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
func (p *IPHashPool) Backends() []*Backend {
	p.mu.RLock()
	defer p.mu.RUnlock()

	result := make([]*Backend, len(p.backends))
	copy(result, p.backends)
	return result
}

// AddBackend adds a backend to the pool.
func (p *IPHashPool) AddBackend(b *Backend) {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.backends = append(p.backends, b)
	p.logger.Info("backend added to IP hash pool",
		zap.String("backend", b.Name()),
		zap.String("url", b.URL()),
		zap.Int("pool_size", len(p.backends)),
	)
}

// extractIP extracts the IP address from a RemoteAddr string.
// RemoteAddr is typically "IP:port" or "[IPv6]:port".
func extractIP(remoteAddr string) string {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		// If SplitHostPort fails, the address might be just an IP
		// (e.g., in tests using httptest.NewRequest).
		return remoteAddr
	}
	return host
}

// hashIP computes a FNV-1a hash of the IP string.
//
// FNV-1a properties:
//   - Non-cryptographic (fast, ~2ns per hash)
//   - Good distribution for short strings like IP addresses
//   - Deterministic: same input always produces same output
func hashIP(ip string) uint32 {
	h := fnv.New32a()
	h.Write([]byte(ip))
	return h.Sum32()
}
