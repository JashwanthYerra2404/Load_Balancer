package pool

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"go.uber.org/zap"
)

// TestIPHashPool_EmptyPool verifies that Next() returns nil
// when no backends are configured.
func TestIPHashPool_EmptyPool(t *testing.T) {
	p := NewIPHashPool(newTestLogger())

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	backend := p.Next(req)

	if backend != nil {
		t.Errorf("Next() should return nil for empty pool, got %v", backend)
	}
}

// TestIPHashPool_Stickiness verifies that the same client IP always
// routes to the same backend.
func TestIPHashPool_Stickiness(t *testing.T) {
	p := NewIPHashPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "b1", 1, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "b2", 1, 0, newTestLogger())
	b3, _ := NewBackend("http://localhost:9003", "b3", 1, 0, newTestLogger())

	p.AddBackend(b1)
	p.AddBackend(b2)
	p.AddBackend(b3)

	// Same IP should always get the same backend.
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "192.168.1.100:12345"

	firstChoice := p.Next(req)
	if firstChoice == nil {
		t.Fatal("Next() returned nil")
	}

	// Verify stickiness over 20 requests.
	for i := 0; i < 20; i++ {
		r := httptest.NewRequest(http.MethodGet, "/", nil)
		r.RemoteAddr = "192.168.1.100:12345" // same IP, same port
		selected := p.Next(r)
		if selected.Name() != firstChoice.Name() {
			t.Errorf("call %d: got %q, want %q (same IP should be sticky)",
				i, selected.Name(), firstChoice.Name())
		}
	}

	// Different port, same IP — should still get the same backend.
	for i := 0; i < 10; i++ {
		r := httptest.NewRequest(http.MethodGet, "/", nil)
		r.RemoteAddr = fmt.Sprintf("192.168.1.100:%d", 20000+i)
		selected := p.Next(r)
		if selected.Name() != firstChoice.Name() {
			t.Errorf("different port: got %q, want %q (IP hash should ignore port)",
				selected.Name(), firstChoice.Name())
		}
	}
}

// TestIPHashPool_Distribution verifies that different IPs get distributed
// across backends (not all to one).
func TestIPHashPool_Distribution(t *testing.T) {
	p := NewIPHashPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "b1", 1, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "b2", 1, 0, newTestLogger())
	b3, _ := NewBackend("http://localhost:9003", "b3", 1, 0, newTestLogger())

	p.AddBackend(b1)
	p.AddBackend(b2)
	p.AddBackend(b3)

	counts := make(map[string]int)

	// 100 different IPs should distribute across backends.
	for i := 0; i < 100; i++ {
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.RemoteAddr = fmt.Sprintf("10.0.%d.%d:8080", i/256, i%256)
		selected := p.Next(req)
		if selected == nil {
			t.Fatal("Next() returned nil")
		}
		counts[selected.Name()]++
	}

	// Each backend should get at least some traffic.
	for _, name := range []string{"b1", "b2", "b3"} {
		if counts[name] == 0 {
			t.Errorf("backend %q got 0 requests from 100 different IPs", name)
		}
	}

	t.Logf("IP Hash distribution: b1=%d, b2=%d, b3=%d",
		counts["b1"], counts["b2"], counts["b3"])
}

// TestIPHashPool_FallbackOnDeadBackend verifies that when the hashed backend
// is dead, the request is routed to the next healthy backend.
func TestIPHashPool_FallbackOnDeadBackend(t *testing.T) {
	p := NewIPHashPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "b1", 1, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "b2", 1, 0, newTestLogger())
	b3, _ := NewBackend("http://localhost:9003", "b3", 1, 0, newTestLogger())

	p.AddBackend(b1)
	p.AddBackend(b2)
	p.AddBackend(b3)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "192.168.1.100:12345"

	// Get the initial choice.
	initial := p.Next(req)
	if initial == nil {
		t.Fatal("Next() returned nil")
	}

	// Kill the chosen backend.
	initial.SetAlive(false)

	// Should now route to a different backend.
	fallback := p.Next(req)
	if fallback == nil {
		t.Fatal("Next() returned nil after killing one backend")
	}
	if fallback.Name() == initial.Name() {
		t.Error("should not route to dead backend")
	}
}

// TestIPHashPool_AllDead verifies nil is returned when all backends are dead.
func TestIPHashPool_AllDead(t *testing.T) {
	p := NewIPHashPool(newTestLogger())

	b1, _ := NewBackend("http://localhost:9001", "b1", 1, 0, newTestLogger())
	b2, _ := NewBackend("http://localhost:9002", "b2", 1, 0, newTestLogger())
	p.AddBackend(b1)
	p.AddBackend(b2)

	b1.SetAlive(false)
	b2.SetAlive(false)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "192.168.1.100:12345"
	selected := p.Next(req)

	if selected != nil {
		t.Errorf("Next() should return nil when all backends are dead, got %q", selected.Name())
	}
}

// TestIPHashPool_Backends verifies that Backends() returns all backends.
func TestIPHashPool_Backends(t *testing.T) {
	p := NewIPHashPool(newTestLogger())

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

// TestIPHashPool_ConcurrentAccess verifies thread safety.
func TestIPHashPool_ConcurrentAccess(t *testing.T) {
	p := NewIPHashPool(newTestLogger())

	for i := 0; i < 5; i++ {
		b, _ := NewBackend("http://localhost:9001", "b", 1, 0, newTestLogger())
		p.AddBackend(b)
	}

	const goroutines = 100
	const requestsPerGoroutine = 100

	var wg sync.WaitGroup
	wg.Add(goroutines)

	for g := 0; g < goroutines; g++ {
		go func(id int) {
			defer wg.Done()
			for i := 0; i < requestsPerGoroutine; i++ {
				req := httptest.NewRequest(http.MethodGet, "/", nil)
				req.RemoteAddr = fmt.Sprintf("10.0.%d.%d:8080", id, i)
				backend := p.Next(req)
				if backend == nil {
					t.Error("Next() returned nil with healthy backends")
					return
				}
			}
		}(g)
	}

	wg.Wait()
}

// TestExtractIP verifies IP extraction from various RemoteAddr formats.
func TestExtractIP(t *testing.T) {
	tests := []struct {
		remoteAddr string
		wantIP     string
	}{
		{"192.168.1.1:8080", "192.168.1.1"},
		{"10.0.0.1:443", "10.0.0.1"},
		{"[::1]:8080", "::1"},
		{"[2001:db8::1]:443", "2001:db8::1"},
		{"192.168.1.1", "192.168.1.1"}, // no port
	}

	for _, tt := range tests {
		t.Run(tt.remoteAddr, func(t *testing.T) {
			got := extractIP(tt.remoteAddr)
			if got != tt.wantIP {
				t.Errorf("extractIP(%q) = %q, want %q", tt.remoteAddr, got, tt.wantIP)
			}
		})
	}
}

// BenchmarkIPHashPool_Next benchmarks IP hash selection.
func BenchmarkIPHashPool_Next(b *testing.B) {
	p := NewIPHashPool(zap.NewNop())

	for i := 0; i < 5; i++ {
		backend, _ := NewBackend("http://localhost:9001", "b", 1, 0, zap.NewNop())
		p.AddBackend(backend)
	}

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "192.168.1.100:12345"

	b.ReportAllocs()

	for b.Loop() {
		p.Next(req)
	}
}

// BenchmarkIPHashPool_NextParallel benchmarks concurrent IP hash selection.
func BenchmarkIPHashPool_NextParallel(b *testing.B) {
	p := NewIPHashPool(zap.NewNop())

	for i := 0; i < 5; i++ {
		backend, _ := NewBackend("http://localhost:9001", "b", 1, 0, zap.NewNop())
		p.AddBackend(backend)
	}

	b.ResetTimer()
	b.ReportAllocs()

	b.RunParallel(func(pb *testing.PB) {
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.RemoteAddr = "192.168.1.100:12345"
		for pb.Next() {
			p.Next(req)
		}
	})
}
