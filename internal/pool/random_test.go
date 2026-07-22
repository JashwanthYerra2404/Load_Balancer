package pool

import (
	"math"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"go.uber.org/zap"
)

// TestRandomPool_EmptyPool verifies that Next() returns nil
// when no backends are configured.
func TestRandomPool_EmptyPool(t *testing.T) {
	p := NewRandomPool(newTestLogger())

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	backend := p.Next(req)

	if backend != nil {
		t.Errorf("Next() should return nil for empty pool, got %v", backend)
	}
}

// TestRandomPool_SingleBackend verifies that a single backend is always selected.
func TestRandomPool_SingleBackend(t *testing.T) {
	p := NewRandomPool(newTestLogger())

	b, _ := NewBackend("http://localhost:9001", "only-one", 1, 0, newTestLogger())
	p.AddBackend(b)

	req := httptest.NewRequest(http.MethodGet, "/", nil)

	for i := 0; i < 20; i++ {
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil with one healthy backend")
		}
		if selected.Name() != "only-one" {
			t.Errorf("got %q, want %q", selected.Name(), "only-one")
		}
	}
}

// TestRandomPool_RoughlyUniformDistribution verifies that random selection
// produces a roughly uniform distribution over many requests.
func TestRandomPool_RoughlyUniformDistribution(t *testing.T) {
	p := NewRandomPool(newTestLogger())

	names := []string{"b1", "b2", "b3"}
	for _, name := range names {
		b, _ := NewBackend("http://localhost:9001", name, 1, 0, newTestLogger())
		p.AddBackend(b)
	}

	counts := make(map[string]int)
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	totalRequests := 3000

	for i := 0; i < totalRequests; i++ {
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil with healthy backends")
		}
		counts[selected.Name()]++
	}

	// With 3000 requests across 3 backends, each should get ~1000.
	// Allow ±15% tolerance for randomness.
	expected := float64(totalRequests) / float64(len(names))
	tolerance := expected * 0.15

	for _, name := range names {
		got := float64(counts[name])
		if math.Abs(got-expected) > tolerance {
			t.Errorf("backend %q got %d requests (expected ~%.0f ± %.0f)",
				name, counts[name], expected, tolerance)
		}
	}

	t.Logf("Random distribution over %d requests: b1=%d, b2=%d, b3=%d",
		totalRequests, counts["b1"], counts["b2"], counts["b3"])
}

// TestRandomPool_SkipsDeadBackends verifies that dead backends are not selected.
func TestRandomPool_SkipsDeadBackends(t *testing.T) {
	p := NewRandomPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "alive", 1, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "dead", 1, 0, newTestLogger())

	p.AddBackend(b1)
	p.AddBackend(b2)

	b2.SetAlive(false)

	req := httptest.NewRequest(http.MethodGet, "/", nil)

	for i := 0; i < 50; i++ {
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil with one alive backend")
		}
		if selected.Name() == "dead" {
			t.Fatal("dead backend should never be selected")
		}
	}
}

// TestRandomPool_SkipsAtCapacity verifies that full backends are skipped.
func TestRandomPool_SkipsAtCapacity(t *testing.T) {
	p := NewRandomPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "full", 1, 2, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "available", 1, 0, newTestLogger())

	p.AddBackend(b1)
	p.AddBackend(b2)

	b1.activeConnections.Store(2)

	req := httptest.NewRequest(http.MethodGet, "/", nil)

	for i := 0; i < 50; i++ {
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil")
		}
		if selected.Name() == "full" {
			t.Fatal("backend at capacity should not be selected")
		}
	}
}

// TestRandomPool_AllDead verifies nil is returned when all backends are dead.
func TestRandomPool_AllDead(t *testing.T) {
	p := NewRandomPool(newTestLogger())

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

// TestRandomPool_Backends verifies that Backends() returns all backends.
func TestRandomPool_Backends(t *testing.T) {
	p := NewRandomPool(newTestLogger())

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

// TestRandomPool_ConcurrentAccess verifies thread safety.
func TestRandomPool_ConcurrentAccess(t *testing.T) {
	p := NewRandomPool(newTestLogger())

	for i := 0; i < 5; i++ {
		b, _ := NewBackend("http://localhost:9001", "b", 1, 0, newTestLogger())
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

// BenchmarkRandomPool_Next benchmarks random selection.
func BenchmarkRandomPool_Next(b *testing.B) {
	p := NewRandomPool(zap.NewNop())

	for i := 0; i < 5; i++ {
		backend, _ := NewBackend("http://localhost:9001", "b", 1, 0, zap.NewNop())
		p.AddBackend(backend)
	}

	req := httptest.NewRequest(http.MethodGet, "/", nil)

	b.ReportAllocs()

	for b.Loop() {
		p.Next(req)
	}
}

// BenchmarkRandomPool_NextParallel benchmarks concurrent random selection.
func BenchmarkRandomPool_NextParallel(b *testing.B) {
	p := NewRandomPool(zap.NewNop())

	for i := 0; i < 5; i++ {
		backend, _ := NewBackend("http://localhost:9001", "b", 1, 0, zap.NewNop())
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
