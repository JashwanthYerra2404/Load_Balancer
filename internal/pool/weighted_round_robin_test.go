package pool

import (
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"go.uber.org/zap"
)

// TestWeightedRoundRobinPool_EmptyPool verifies that Next() returns nil
// when no backends are configured.
func TestWeightedRoundRobinPool_EmptyPool(t *testing.T) {
	p := NewWeightedRoundRobinPool(newTestLogger())

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	backend := p.Next(req)

	if backend != nil {
		t.Errorf("Next() should return nil for empty pool, got %v", backend)
	}
}

// TestWeightedRoundRobinPool_ProportionalDistribution verifies that
// traffic is distributed proportionally to weights.
func TestWeightedRoundRobinPool_ProportionalDistribution(t *testing.T) {
	p := NewWeightedRoundRobinPool(newTestLogger())

	// Weights: 3:2:1 → over 6 requests: b1 gets 3, b2 gets 2, b3 gets 1.
	b1, _ := NewBackend("http://localhost:9001", "heavy", 3, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "medium", 2, 0, newTestLogger())
	b3, _ := NewBackend("http://localhost:9003", "light", 1, 0, newTestLogger())

	p.AddBackend(b1)
	p.AddBackend(b2)
	p.AddBackend(b3)

	// Send 60 requests (10 full cycles of weight sum 6).
	counts := make(map[string]int)
	req := httptest.NewRequest(http.MethodGet, "/", nil)

	for i := 0; i < 60; i++ {
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil with healthy backends")
		}
		counts[selected.Name()]++
	}

	// Expected: heavy=30, medium=20, light=10.
	if counts["heavy"] != 30 {
		t.Errorf("heavy got %d requests, want 30", counts["heavy"])
	}
	if counts["medium"] != 20 {
		t.Errorf("medium got %d requests, want 20", counts["medium"])
	}
	if counts["light"] != 10 {
		t.Errorf("light got %d requests, want 10", counts["light"])
	}
}

// TestWeightedRoundRobinPool_SmoothDistribution verifies that the SWRR
// algorithm interleaves requests rather than batching them.
func TestWeightedRoundRobinPool_SmoothDistribution(t *testing.T) {
	p := NewWeightedRoundRobinPool(newTestLogger())

	// Weights: 5:1 — naive approach would batch 5 to A then 1 to B.
	// SWRR should interleave: A,A,A,B,A,A (or similar smooth pattern).
	b1, _ := NewBackend("http://localhost:9001", "A", 5, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "B", 1, 0, newTestLogger())

	p.AddBackend(b1)
	p.AddBackend(b2)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	sequence := make([]string, 12)

	for i := 0; i < 12; i++ {
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil")
		}
		sequence[i] = selected.Name()
	}

	// Verify B appears in the first 6 requests (not batched at the end).
	foundB := false
	for i := 0; i < 6; i++ {
		if sequence[i] == "B" {
			foundB = true
			break
		}
	}
	if !foundB {
		t.Errorf("SWRR should interleave — B should appear in first 6 requests, got %v", sequence[:6])
	}

	t.Logf("Smooth distribution sequence: %v", sequence)
}

// TestWeightedRoundRobinPool_EqualWeights verifies that equal weights
// degenerate to round-robin-like behavior.
func TestWeightedRoundRobinPool_EqualWeights(t *testing.T) {
	p := NewWeightedRoundRobinPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "b1", 1, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "b2", 1, 0, newTestLogger())
	b3, _ := NewBackend("http://localhost:9003", "b3", 1, 0, newTestLogger())

	p.AddBackend(b1)
	p.AddBackend(b2)
	p.AddBackend(b3)

	counts := make(map[string]int)
	req := httptest.NewRequest(http.MethodGet, "/", nil)

	for i := 0; i < 30; i++ {
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil")
		}
		counts[selected.Name()]++
	}

	// With equal weights, each should get exactly 10.
	for _, name := range []string{"b1", "b2", "b3"} {
		if counts[name] != 10 {
			t.Errorf("backend %q got %d requests, want 10", name, counts[name])
		}
	}
}

// TestWeightedRoundRobinPool_SkipsDeadBackends verifies that dead backends
// are excluded and their weight is redistributed.
func TestWeightedRoundRobinPool_SkipsDeadBackends(t *testing.T) {
	p := NewWeightedRoundRobinPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "alive-1", 3, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "dead", 2, 0, newTestLogger())
	b3, _ := NewBackend("http://localhost:9003", "alive-2", 1, 0, newTestLogger())

	p.AddBackend(b1)
	p.AddBackend(b2)
	p.AddBackend(b3)

	b2.SetAlive(false)

	counts := make(map[string]int)
	req := httptest.NewRequest(http.MethodGet, "/", nil)

	for i := 0; i < 40; i++ {
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil with alive backends")
		}
		counts[selected.Name()]++
	}

	// Dead backend should get 0 requests.
	if counts["dead"] != 0 {
		t.Errorf("dead backend got %d requests, want 0", counts["dead"])
	}

	// Alive backends should share traffic in 3:1 ratio.
	// Over 40 requests: alive-1 should get 30, alive-2 should get 10.
	if counts["alive-1"] != 30 {
		t.Errorf("alive-1 got %d requests, want 30", counts["alive-1"])
	}
	if counts["alive-2"] != 10 {
		t.Errorf("alive-2 got %d requests, want 10", counts["alive-2"])
	}
}

// TestWeightedRoundRobinPool_AllDead verifies nil is returned when all
// backends are dead.
func TestWeightedRoundRobinPool_AllDead(t *testing.T) {
	p := NewWeightedRoundRobinPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "b1", 1, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "b2", 1, 0, newTestLogger())
	p.AddBackend(b1)
	p.AddBackend(b2)

	b1.SetAlive(false)
	b2.SetAlive(false)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	selected := p.Next(req)

	if selected != nil {
		t.Errorf("Next() should return nil when all backends are dead, got %q", selected.Name())
	}
}

// TestWeightedRoundRobinPool_Backends verifies that Backends() returns all backends.
func TestWeightedRoundRobinPool_Backends(t *testing.T) {
	p := NewWeightedRoundRobinPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "b1", 1, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "b2", 1, 0, newTestLogger())
	p.AddBackend(b1)
	p.AddBackend(b2)

	backends := p.Backends()
	if len(backends) != 2 {
		t.Fatalf("Backends() returned %d, want 2", len(backends))
	}

	// Verify it's a copy.
	backends[0] = nil
	poolBackends := p.Backends()
	if poolBackends[0] == nil {
		t.Error("Backends() should return a copy, not the internal slice")
	}
}

// TestWeightedRoundRobinPool_ConcurrentAccess verifies thread safety.
func TestWeightedRoundRobinPool_ConcurrentAccess(t *testing.T) {
	p := NewWeightedRoundRobinPool(newTestLogger())

	for i := 0; i < 5; i++ {
		b, _ := NewBackend("http://localhost:9001", "b", i+1, 0, newTestLogger())
		p.AddBackend(b)
	}

	const goroutines = 100
	const requestsPerGoroutine = 100

	var wg sync.WaitGroup
	wg.Add(goroutines)

	for g := 0; g < goroutines; g++ {
		go func() {
			defer wg.Done()
			req := httptest.NewRequest(http.MethodGet, "/", nil)
			for i := 0; i < requestsPerGoroutine; i++ {
				backend := p.Next(req)
				if backend == nil {
					t.Error("Next() returned nil with healthy backends")
					return
				}
			}
		}()
	}

	wg.Wait()
}

// BenchmarkWeightedRoundRobinPool_Next benchmarks weighted round-robin selection.
func BenchmarkWeightedRoundRobinPool_Next(b *testing.B) {
	p := NewWeightedRoundRobinPool(zap.NewNop())

	weights := []int{5, 3, 2, 1, 1}
	for i, w := range weights {
		backend, _ := NewBackend("http://localhost:9001", "b", w, 0, zap.NewNop())
		_ = i
		p.AddBackend(backend)
	}

	req := httptest.NewRequest(http.MethodGet, "/", nil)

	b.ReportAllocs()

	for b.Loop() {
		p.Next(req)
	}
}

// BenchmarkWeightedRoundRobinPool_NextParallel benchmarks concurrent selection.
func BenchmarkWeightedRoundRobinPool_NextParallel(b *testing.B) {
	p := NewWeightedRoundRobinPool(zap.NewNop())

	weights := []int{5, 3, 2, 1, 1}
	for _, w := range weights {
		backend, _ := NewBackend("http://localhost:9001", "b", w, 0, zap.NewNop())
		p.AddBackend(backend)
	}

	b.ResetTimer()
	b.ReportAllocs()

	b.RunParallel(func(pb *testing.PB) {
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		for pb.Next() {
			p.Next(req)
		}
	})
}
