package pool

import (
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"go.uber.org/zap"
)

// TestLeastConnectionsPool_EmptyPool verifies that Next() returns nil
// when no backends are configured.
func TestLeastConnectionsPool_EmptyPool(t *testing.T) {
	p := NewLeastConnectionsPool(newTestLogger())

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	backend := p.Next(req)

	if backend != nil {
		t.Errorf("Next() should return nil for empty pool, got %v", backend)
	}
}

// TestLeastConnectionsPool_SelectsLeastLoaded verifies that the backend
// with the fewest active connections is selected.
func TestLeastConnectionsPool_SelectsLeastLoaded(t *testing.T) {
	p := NewLeastConnectionsPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "busy", 1, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "idle", 1, 0, newTestLogger())
	b3, _ := NewBackend("http://localhost:9003", "moderate", 1, 0, newTestLogger())

	p.AddBackend(b1)
	p.AddBackend(b2)
	p.AddBackend(b3)

	// Simulate different loads.
	b1.activeConnections.Store(10)
	b2.activeConnections.Store(1)
	b3.activeConnections.Store(5)

	req := httptest.NewRequest(http.MethodGet, "/", nil)

	// The idle backend (1 connection) should be selected every time.
	for i := 0; i < 20; i++ {
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil with healthy backends")
		}
		if selected.Name() != "idle" {
			t.Errorf("call %d: got %q, want %q (least connections)", i, selected.Name(), "idle")
		}
	}
}

// TestLeastConnectionsPool_SkipsDeadBackends verifies that dead backends
// are excluded even if they have fewer connections.
func TestLeastConnectionsPool_SkipsDeadBackends(t *testing.T) {
	p := NewLeastConnectionsPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "alive-busy", 1, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "dead-idle", 1, 0, newTestLogger())

	p.AddBackend(b1)
	p.AddBackend(b2)

	// Dead backend has fewer connections but shouldn't be selected.
	b1.activeConnections.Store(10)
	b2.activeConnections.Store(0)
	b2.SetAlive(false)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	selected := p.Next(req)

	if selected == nil {
		t.Fatal("Next() returned nil with one alive backend")
	}
	if selected.Name() != "alive-busy" {
		t.Errorf("got %q, want %q (dead backend should be skipped)", selected.Name(), "alive-busy")
	}
}

// TestLeastConnectionsPool_SkipsAtCapacity verifies that backends at their
// connection limit are skipped.
func TestLeastConnectionsPool_SkipsAtCapacity(t *testing.T) {
	p := NewLeastConnectionsPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "full", 1, 2, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "available", 1, 0, newTestLogger())

	p.AddBackend(b1)
	p.AddBackend(b2)

	b1.activeConnections.Store(2) // at capacity
	b2.activeConnections.Store(5)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	selected := p.Next(req)

	if selected == nil {
		t.Fatal("Next() returned nil")
	}
	if selected.Name() != "available" {
		t.Errorf("got %q, want %q", selected.Name(), "available")
	}
}

// TestLeastConnectionsPool_AllDead verifies nil is returned when all
// backends are dead.
func TestLeastConnectionsPool_AllDead(t *testing.T) {
	p := NewLeastConnectionsPool(newTestLogger())

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

// TestLeastConnectionsPool_TieBreaking verifies that when multiple backends
// have equal connection counts, requests are distributed (not all to the first).
func TestLeastConnectionsPool_TieBreaking(t *testing.T) {
	p := NewLeastConnectionsPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "b1", 1, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "b2", 1, 0, newTestLogger())
	b3, _ := NewBackend("http://localhost:9003", "b3", 1, 0, newTestLogger())

	p.AddBackend(b1)
	p.AddBackend(b2)
	p.AddBackend(b3)

	// All backends have 0 connections — tie-breaker should distribute.
	counts := make(map[string]int)
	req := httptest.NewRequest(http.MethodGet, "/", nil)

	for i := 0; i < 30; i++ {
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil")
		}
		counts[selected.Name()]++
	}

	// All backends should receive at least some traffic.
	for _, name := range []string{"b1", "b2", "b3"} {
		if counts[name] == 0 {
			t.Errorf("backend %q got 0 requests during tie-breaking", name)
		}
	}
	t.Logf("Tie-breaking distribution: b1=%d, b2=%d, b3=%d",
		counts["b1"], counts["b2"], counts["b3"])
}

// TestLeastConnectionsPool_Backends verifies that Backends() returns all backends.
func TestLeastConnectionsPool_Backends(t *testing.T) {
	p := NewLeastConnectionsPool(newTestLogger())

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

// TestLeastConnectionsPool_ConcurrentAccess verifies thread safety under
// concurrent load.
func TestLeastConnectionsPool_ConcurrentAccess(t *testing.T) {
	p := NewLeastConnectionsPool(newTestLogger())

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

// BenchmarkLeastConnectionsPool_Next benchmarks least-connections selection.
func BenchmarkLeastConnectionsPool_Next(b *testing.B) {
	p := NewLeastConnectionsPool(zap.NewNop())

	for i := 0; i < 5; i++ {
		backend, _ := NewBackend("http://localhost:9001", "b", 1, 0, zap.NewNop())
		// Give each backend different connection counts.
		backend.activeConnections.Store(int64(i * 3))
		p.AddBackend(backend)
	}

	req := httptest.NewRequest(http.MethodGet, "/", nil)

	b.ReportAllocs()

	for b.Loop() {
		p.Next(req)
	}
}

// BenchmarkLeastConnectionsPool_NextParallel benchmarks concurrent selection.
func BenchmarkLeastConnectionsPool_NextParallel(b *testing.B) {
	p := NewLeastConnectionsPool(zap.NewNop())

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
