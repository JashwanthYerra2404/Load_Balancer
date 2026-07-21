package pool

import (
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"go.uber.org/zap"
)

// TestRoundRobinPool_EmptyPool verifies that Next() returns nil
// when no backends are configured.
func TestRoundRobinPool_EmptyPool(t *testing.T) {
	p := NewRoundRobinPool(newTestLogger())

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	backend := p.Next(req)

	if backend != nil {
		t.Errorf("Next() should return nil for empty pool, got %v", backend)
	}
}

// TestRoundRobinPool_SingleBackend verifies that a single backend
// always gets selected.
func TestRoundRobinPool_SingleBackend(t *testing.T) {
	p := NewRoundRobinPool(newTestLogger())

	b, _ := NewBackend("http://localhost:9001", "backend-1", 1, 0, newTestLogger())
	p.AddBackend(b)

	req := httptest.NewRequest(http.MethodGet, "/", nil)

	for i := 0; i < 10; i++ {
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil with one healthy backend")
		}
		if selected.Name() != "backend-1" {
			t.Errorf("call %d: got %q, want %q", i, selected.Name(), "backend-1")
		}
	}
}

// TestRoundRobinPool_Distribution verifies even round-robin distribution
// across multiple backends.
func TestRoundRobinPool_Distribution(t *testing.T) {
	p := NewRoundRobinPool(newTestLogger())

	names := []string{"backend-1", "backend-2", "backend-3"}
	for _, name := range names {
		b, _ := NewBackend("http://localhost:9001", name, 1, 0, newTestLogger())
		p.AddBackend(b)
	}

	// Count how many times each backend is selected over 30 requests.
	counts := make(map[string]int)
	req := httptest.NewRequest(http.MethodGet, "/", nil)

	for i := 0; i < 30; i++ {
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil with healthy backends")
		}
		counts[selected.Name()]++
	}

	// With 30 requests and 3 backends, each should get exactly 10.
	for _, name := range names {
		if counts[name] != 10 {
			t.Errorf("backend %q got %d requests, want 10", name, counts[name])
		}
	}
}

// TestRoundRobinPool_SkipsDeadBackends verifies that dead backends
// are skipped during selection.
func TestRoundRobinPool_SkipsDeadBackends(t *testing.T) {
	p := NewRoundRobinPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "alive-1", 1, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "dead", 1, 0, newTestLogger())
	b3, _ := NewBackend("http://localhost:9003", "alive-2", 1, 0, newTestLogger())

	p.AddBackend(b1)
	p.AddBackend(b2)
	p.AddBackend(b3)

	// Mark backend-2 as dead.
	b2.SetAlive(false)

	counts := make(map[string]int)
	req := httptest.NewRequest(http.MethodGet, "/", nil)

	for i := 0; i < 20; i++ {
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil when alive backends exist")
		}
		counts[selected.Name()]++
	}

	// Dead backend should get 0 requests.
	if counts["dead"] != 0 {
		t.Errorf("dead backend got %d requests, want 0", counts["dead"])
	}

	// Alive backends should share the traffic.
	if counts["alive-1"] == 0 {
		t.Error("alive-1 got 0 requests")
	}
	if counts["alive-2"] == 0 {
		t.Error("alive-2 got 0 requests")
	}

	t.Logf("Distribution: alive-1=%d, dead=%d, alive-2=%d",
		counts["alive-1"], counts["dead"], counts["alive-2"])
}

// TestRoundRobinPool_AllDead verifies nil is returned when all
// backends are dead.
func TestRoundRobinPool_AllDead(t *testing.T) {
	p := NewRoundRobinPool(newTestLogger())

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

// TestRoundRobinPool_SkipsAtCapacity verifies that backends at their
// connection limit are skipped.
func TestRoundRobinPool_SkipsAtCapacity(t *testing.T) {
	p := NewRoundRobinPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "limited", 1, 2, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "unlimited", 1, 0, newTestLogger())
	p.AddBackend(b1)
	p.AddBackend(b2)

	// Saturate b1.
	b1.activeConnections.Store(2)

	req := httptest.NewRequest(http.MethodGet, "/", nil)

	for i := 0; i < 10; i++ {
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil")
		}
		if selected.Name() == "limited" {
			t.Error("should not select backend at capacity")
		}
	}
}

// TestRoundRobinPool_BackendRecovery verifies that a backend that comes
// back alive is re-included in the rotation.
func TestRoundRobinPool_BackendRecovery(t *testing.T) {
	p := NewRoundRobinPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "b1", 1, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "b2", 1, 0, newTestLogger())
	p.AddBackend(b1)
	p.AddBackend(b2)

	req := httptest.NewRequest(http.MethodGet, "/", nil)

	// Kill b2.
	b2.SetAlive(false)

	// All traffic should go to b1.
	for i := 0; i < 5; i++ {
		selected := p.Next(req)
		if selected.Name() != "b1" {
			t.Errorf("with b2 dead, got %q, want b1", selected.Name())
		}
	}

	// Revive b2.
	b2.SetAlive(true)

	// Both should now receive traffic.
	counts := make(map[string]int)
	for i := 0; i < 10; i++ {
		selected := p.Next(req)
		counts[selected.Name()]++
	}

	if counts["b2"] == 0 {
		t.Error("b2 should receive traffic after recovery")
	}
}

// TestRoundRobinPool_Backends verifies that Backends() returns all backends.
func TestRoundRobinPool_Backends(t *testing.T) {
	p := NewRoundRobinPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "b1", 1, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "b2", 1, 0, newTestLogger())
	p.AddBackend(b1)
	p.AddBackend(b2)

	backends := p.Backends()
	if len(backends) != 2 {
		t.Fatalf("Backends() returned %d, want 2", len(backends))
	}

	// Verify it's a copy — modifying the returned slice shouldn't affect the pool.
	backends[0] = nil
	poolBackends := p.Backends()
	if poolBackends[0] == nil {
		t.Error("Backends() should return a copy, not the internal slice")
	}
}

// TestRoundRobinPool_ConcurrentAccess verifies thread safety under
// concurrent load. This is critical — the pool is accessed by every
// request goroutine simultaneously.
func TestRoundRobinPool_ConcurrentAccess(t *testing.T) {
	p := NewRoundRobinPool(newTestLogger())

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

// BenchmarkRoundRobinPool_Next benchmarks the backend selection overhead.
//
// This measures the cost of atomic increment + modulo + alive check.
// Expected: ~5-10ns per operation (dominated by atomic.Uint64.Add).
func BenchmarkRoundRobinPool_Next(b *testing.B) {
	p := NewRoundRobinPool(zap.NewNop())

	for i := 0; i < 5; i++ {
		backend, _ := NewBackend("http://localhost:9001", "b", 1, 0, zap.NewNop())
		p.AddBackend(backend)
	}

	req := httptest.NewRequest(http.MethodGet, "/", nil)

	b.ResetTimer()
	b.ReportAllocs()

	for i := 0; i < b.N; i++ {
		p.Next(req)
	}
}

// BenchmarkRoundRobinPool_NextParallel benchmarks concurrent backend selection.
//
// This tests for contention on the atomic counter under parallel load.
// We want to verify that performance scales linearly with goroutines.
func BenchmarkRoundRobinPool_NextParallel(b *testing.B) {
	p := NewRoundRobinPool(zap.NewNop())

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
