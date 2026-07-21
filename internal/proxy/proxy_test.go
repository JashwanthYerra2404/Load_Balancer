package proxy

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"go.uber.org/zap"

	"github.com/JashwanthYerra2404/Load_Balancer/internal/pool"
)

func newTestLogger() *zap.Logger {
	return zap.NewNop()
}

// createTestPool is a helper that starts N mock backends and returns a pool
// with backends pointing to them. The caller is responsible for closing
// the returned httptest.Server instances.
func createTestPool(t *testing.T, count int) (*pool.RoundRobinPool, []*httptest.Server) {
	t.Helper()

	p := pool.NewRoundRobinPool(newTestLogger())
	servers := make([]*httptest.Server, count)

	for i := 0; i < count; i++ {
		name := func(n int) string {
			return []string{"backend-1", "backend-2", "backend-3", "backend-4", "backend-5"}[n]
		}(i)

		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			// Capture the backend name from the request header (set by Director).
			backendName := r.Header.Get("X-Backend-Name")
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(map[string]string{
				"server": backendName,
				"path":   r.URL.Path,
				"method": r.Method,
			})
		}))
		servers[i] = srv

		b, err := pool.NewBackend(srv.URL, name, 1, 0, newTestLogger())
		if err != nil {
			t.Fatalf("NewBackend() error: %v", err)
		}
		p.AddBackend(b)
	}

	return p, servers
}

// TestProxy_ForwardsRequest verifies end-to-end request forwarding
// through the pool-based proxy.
func TestProxy_ForwardsRequest(t *testing.T) {
	p, servers := createTestPool(t, 1)
	defer servers[0].Close()

	rp := New(p, newTestLogger())

	req := httptest.NewRequest(http.MethodGet, "/api/test?foo=bar", nil)
	w := httptest.NewRecorder()

	rp.ServeHTTP(w, req)

	resp := w.Result()
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Errorf("status = %d, want 200", resp.StatusCode)
	}

	// Verify proxy headers.
	if got := resp.Header.Get("X-Backend-Name"); got != "backend-1" {
		t.Errorf("X-Backend-Name = %q, want %q", got, "backend-1")
	}
	if got := resp.Header.Get("Via"); !strings.Contains(got, "load-balancer") {
		t.Errorf("Via = %q, should contain 'load-balancer'", got)
	}

	body, _ := io.ReadAll(resp.Body)
	var result map[string]string
	json.Unmarshal(body, &result)
	if result["path"] != "/api/test" {
		t.Errorf("path = %q, want %q", result["path"], "/api/test")
	}
}

// TestProxy_RoundRobinDistribution verifies that requests are distributed
// evenly across backends.
func TestProxy_RoundRobinDistribution(t *testing.T) {
	p, servers := createTestPool(t, 3)
	defer func() {
		for _, s := range servers {
			s.Close()
		}
	}()

	rp := New(p, newTestLogger())

	// Send 30 requests, count distribution.
	counts := make(map[string]int)
	for i := 0; i < 30; i++ {
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		w := httptest.NewRecorder()
		rp.ServeHTTP(w, req)

		resp := w.Result()
		name := resp.Header.Get("X-Backend-Name")
		counts[name]++
		resp.Body.Close()
	}

	// Each backend should get exactly 10 requests.
	for _, name := range []string{"backend-1", "backend-2", "backend-3"} {
		if counts[name] != 10 {
			t.Errorf("backend %q got %d requests, want 10", name, counts[name])
		}
	}
}

// TestProxy_NoBackends verifies 503 when the pool is empty.
func TestProxy_NoBackends(t *testing.T) {
	p := pool.NewRoundRobinPool(newTestLogger())
	rp := New(p, newTestLogger())

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	w := httptest.NewRecorder()

	rp.ServeHTTP(w, req)

	resp := w.Result()
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Errorf("status = %d, want 503", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), "service unavailable") {
		t.Errorf("body should contain 'service unavailable', got %q", string(body))
	}
}

// TestProxy_AllBackendsDead verifies 503 when all backends are marked dead.
func TestProxy_AllBackendsDead(t *testing.T) {
	p, servers := createTestPool(t, 2)
	defer func() {
		for _, s := range servers {
			s.Close()
		}
	}()

	// Mark all backends as dead.
	for _, b := range p.Backends() {
		b.SetAlive(false)
	}

	rp := New(p, newTestLogger())

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	w := httptest.NewRecorder()

	rp.ServeHTTP(w, req)

	resp := w.Result()
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Errorf("status = %d, want 503", resp.StatusCode)
	}
}

// TestProxy_BackendDown verifies 502 when a selected backend is unreachable.
// This is different from "no backends available" (503).
func TestProxy_BackendDown(t *testing.T) {
	p := pool.NewRoundRobinPool(newTestLogger())

	// Point to a port that nothing is listening on.
	b, _ := pool.NewBackend("http://localhost:1", "dead", 1, 0, newTestLogger())
	p.AddBackend(b)

	rp := New(p, newTestLogger())

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	w := httptest.NewRecorder()

	rp.ServeHTTP(w, req)

	resp := w.Result()
	defer resp.Body.Close()

	// Backend is alive (not marked dead) but unreachable → 502.
	if resp.StatusCode != http.StatusBadGateway {
		t.Errorf("status = %d, want 502", resp.StatusCode)
	}
}

// TestProxy_BackendReturnsError verifies that HTTP 500 from a backend
// is forwarded as-is (not converted to 502 or 503).
func TestProxy_BackendReturnsError(t *testing.T) {
	errorServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("internal server error"))
	}))
	defer errorServer.Close()

	p := pool.NewRoundRobinPool(newTestLogger())
	b, _ := pool.NewBackend(errorServer.URL, "error-backend", 1, 0, newTestLogger())
	p.AddBackend(b)

	rp := New(p, newTestLogger())

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	w := httptest.NewRecorder()

	rp.ServeHTTP(w, req)

	resp := w.Result()
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusInternalServerError {
		t.Errorf("status = %d, want 500 (forwarded from backend)", resp.StatusCode)
	}
}

// TestProxy_ForwardsHeaders verifies that client headers are forwarded.
func TestProxy_ForwardsHeaders(t *testing.T) {
	var receivedHeaders http.Header

	headerServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedHeaders = r.Header.Clone()
		w.WriteHeader(http.StatusOK)
	}))
	defer headerServer.Close()

	p := pool.NewRoundRobinPool(newTestLogger())
	b, _ := pool.NewBackend(headerServer.URL, "header-backend", 1, 0, newTestLogger())
	p.AddBackend(b)

	rp := New(p, newTestLogger())

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Authorization", "Bearer token123")
	req.Header.Set("X-Custom-Header", "custom-value")
	w := httptest.NewRecorder()

	rp.ServeHTTP(w, req)

	if got := receivedHeaders.Get("Authorization"); got != "Bearer token123" {
		t.Errorf("Authorization = %q, want %q", got, "Bearer token123")
	}
	if got := receivedHeaders.Get("X-Custom-Header"); got != "custom-value" {
		t.Errorf("X-Custom-Header = %q, want %q", got, "custom-value")
	}
	if got := receivedHeaders.Get("X-Real-IP"); got == "" {
		t.Error("X-Real-IP should be set")
	}
}

// TestProxy_ForwardsBody verifies POST body forwarding.
func TestProxy_ForwardsBody(t *testing.T) {
	var receivedBody string

	bodyServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		receivedBody = string(body)
		w.WriteHeader(http.StatusOK)
	}))
	defer bodyServer.Close()

	p := pool.NewRoundRobinPool(newTestLogger())
	b, _ := pool.NewBackend(bodyServer.URL, "body-backend", 1, 0, newTestLogger())
	p.AddBackend(b)

	rp := New(p, newTestLogger())

	requestBody := `{"key": "value", "count": 42}`
	req := httptest.NewRequest(http.MethodPost, "/api/data", strings.NewReader(requestBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	rp.ServeHTTP(w, req)

	if receivedBody != requestBody {
		t.Errorf("body = %q, want %q", receivedBody, requestBody)
	}
}

// TestProxy_ConcurrentRequests verifies that the proxy handles concurrent
// requests correctly across multiple backends.
func TestProxy_ConcurrentRequests(t *testing.T) {
	p, servers := createTestPool(t, 3)
	defer func() {
		for _, s := range servers {
			s.Close()
		}
	}()

	rp := New(p, newTestLogger())

	const goroutines = 50
	var wg sync.WaitGroup
	wg.Add(goroutines)

	errors := make(chan error, goroutines)

	for i := 0; i < goroutines; i++ {
		go func() {
			defer wg.Done()
			req := httptest.NewRequest(http.MethodGet, "/", nil)
			w := httptest.NewRecorder()
			rp.ServeHTTP(w, req)

			resp := w.Result()
			defer resp.Body.Close()

			if resp.StatusCode != http.StatusOK {
				errors <- fmt.Errorf("status = %d, want 200", resp.StatusCode)
			}
		}()
	}

	wg.Wait()
	close(errors)

	for err := range errors {
		t.Error(err)
	}
}

// BenchmarkProxy_ServeHTTP benchmarks the full proxy path including
// pool selection and backend forwarding.
func BenchmarkProxy_ServeHTTP(b *testing.B) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("OK"))
	}))
	defer srv.Close()

	p := pool.NewRoundRobinPool(zap.NewNop())
	for i := 0; i < 3; i++ {
		backend, _ := pool.NewBackend(srv.URL, "b", 1, 0, zap.NewNop())
		p.AddBackend(backend)
	}

	rp := New(p, zap.NewNop())
	req := httptest.NewRequest(http.MethodGet, "/", nil)

	b.ResetTimer()
	b.ReportAllocs()

	for i := 0; i < b.N; i++ {
		w := httptest.NewRecorder()
		rp.ServeHTTP(w, req)
	}
}
