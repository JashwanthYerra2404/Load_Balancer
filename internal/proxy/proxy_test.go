package proxy

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"go.uber.org/zap"
)

// newTestLogger creates a no-op logger for tests.
// We don't want log output cluttering test output.
func newTestLogger() *zap.Logger {
	return zap.NewNop()
}

// TestNew_ValidURL verifies that New() accepts a valid backend URL.
func TestNew_ValidURL(t *testing.T) {
	rp, err := New("http://localhost:9001", newTestLogger())
	if err != nil {
		t.Fatalf("New() returned unexpected error: %v", err)
	}
	if rp == nil {
		t.Fatal("New() returned nil proxy")
	}
}

// TestNew_InvalidURL verifies that New() rejects invalid URLs.
func TestNew_InvalidURL(t *testing.T) {
	_, err := New("://invalid", newTestLogger())
	if err == nil {
		t.Fatal("New() should return error for invalid URL")
	}
}

// TestProxy_ForwardsRequest verifies end-to-end request forwarding.
//
// This is the most important test in Phase 1: it proves that a request
// to the proxy reaches the backend with the correct method, path, headers,
// and body, and that the response comes back to the client correctly.
//
// Test design:
//   1. Start a mock backend using httptest.Server
//   2. Create a ReverseProxy pointing to it
//   3. Send a request through the proxy
//   4. Verify the backend received the correct request
//   5. Verify the client received the correct response
func TestProxy_ForwardsRequest(t *testing.T) {
	// Start a mock backend that echoes request details.
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("X-Backend", "test-backend")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{
			"method": r.Method,
			"path":   r.URL.Path,
			"host":   r.Host,
		})
	}))
	defer backend.Close()

	// Create the proxy.
	rp, err := New(backend.URL, newTestLogger())
	if err != nil {
		t.Fatalf("New() error: %v", err)
	}

	// Create a test request and response recorder.
	req := httptest.NewRequest(http.MethodGet, "/api/test?foo=bar", nil)
	w := httptest.NewRecorder()

	// Serve the request through the proxy.
	rp.ServeHTTP(w, req)

	resp := w.Result()
	defer resp.Body.Close()

	// Verify status code.
	if resp.StatusCode != http.StatusOK {
		t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusOK)
	}

	// Verify the backend header was forwarded.
	if got := resp.Header.Get("X-Backend"); got != "test-backend" {
		t.Errorf("X-Backend = %q, want %q", got, "test-backend")
	}

	// Verify proxy added Via header.
	if got := resp.Header.Get("Via"); !strings.Contains(got, "load-balancer") {
		t.Errorf("Via = %q, should contain 'load-balancer'", got)
	}

	// Verify proxy added X-Proxy header.
	if got := resp.Header.Get("X-Proxy"); got != "load-balancer" {
		t.Errorf("X-Proxy = %q, want %q", got, "load-balancer")
	}

	// Verify the response body contains the correct path.
	body, _ := io.ReadAll(resp.Body)
	var result map[string]string
	if err := json.Unmarshal(body, &result); err != nil {
		t.Fatalf("failed to parse response body: %v", err)
	}
	if result["path"] != "/api/test" {
		t.Errorf("backend received path %q, want %q", result["path"], "/api/test")
	}
	if result["method"] != "GET" {
		t.Errorf("backend received method %q, want %q", result["method"], "GET")
	}
}

// TestProxy_ForwardsHeaders verifies that client headers are forwarded
// to the backend, and that proxy-specific headers are added.
func TestProxy_ForwardsHeaders(t *testing.T) {
	var receivedHeaders http.Header

	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedHeaders = r.Header.Clone()
		w.WriteHeader(http.StatusOK)
	}))
	defer backend.Close()

	rp, err := New(backend.URL, newTestLogger())
	if err != nil {
		t.Fatalf("New() error: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Authorization", "Bearer token123")
	req.Header.Set("X-Custom-Header", "custom-value")
	w := httptest.NewRecorder()

	rp.ServeHTTP(w, req)

	// Verify custom headers were forwarded.
	if got := receivedHeaders.Get("Authorization"); got != "Bearer token123" {
		t.Errorf("Authorization = %q, want %q", got, "Bearer token123")
	}
	if got := receivedHeaders.Get("X-Custom-Header"); got != "custom-value" {
		t.Errorf("X-Custom-Header = %q, want %q", got, "custom-value")
	}

	// Verify X-Real-IP was set.
	// Note: httptest.NewRequest sets RemoteAddr to "192.0.2.1:1234"
	if got := receivedHeaders.Get("X-Real-IP"); got == "" {
		t.Error("X-Real-IP header should be set")
	}
}

// TestProxy_ForwardsBody verifies that POST/PUT request bodies are
// correctly forwarded to the backend without corruption.
func TestProxy_ForwardsBody(t *testing.T) {
	var receivedBody string

	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		receivedBody = string(body)
		w.WriteHeader(http.StatusOK)
	}))
	defer backend.Close()

	rp, err := New(backend.URL, newTestLogger())
	if err != nil {
		t.Fatalf("New() error: %v", err)
	}

	requestBody := `{"key": "value", "count": 42}`
	req := httptest.NewRequest(http.MethodPost, "/api/data", strings.NewReader(requestBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	rp.ServeHTTP(w, req)

	if receivedBody != requestBody {
		t.Errorf("backend received body %q, want %q", receivedBody, requestBody)
	}
}

// TestProxy_BackendDown verifies that the proxy returns 502 Bad Gateway
// when the backend is unreachable.
//
// This simulates a backend crash — a critical failure scenario that
// must be handled gracefully.
func TestProxy_BackendDown(t *testing.T) {
	// Point to a port that nothing is listening on.
	rp, err := New("http://localhost:1", newTestLogger())
	if err != nil {
		t.Fatalf("New() error: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	w := httptest.NewRecorder()

	rp.ServeHTTP(w, req)

	resp := w.Result()
	defer resp.Body.Close()

	// Should return 502, not panic or hang.
	if resp.StatusCode != http.StatusBadGateway {
		t.Errorf("status = %d, want %d (Bad Gateway)", resp.StatusCode, http.StatusBadGateway)
	}

	// Should return JSON error body.
	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), "bad gateway") {
		t.Errorf("response body should contain 'bad gateway', got %q", string(body))
	}
}

// TestProxy_BackendReturnsError verifies that HTTP-level errors from the
// backend (e.g., 500 Internal Server Error) are forwarded as-is.
//
// Important distinction: a 500 from the backend is NOT a proxy error.
// The proxy successfully reached the backend and got a response. The
// ErrorHandler should NOT be triggered — only connection-level failures
// trigger it.
func TestProxy_BackendReturnsError(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("internal server error"))
	}))
	defer backend.Close()

	rp, err := New(backend.URL, newTestLogger())
	if err != nil {
		t.Fatalf("New() error: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	w := httptest.NewRecorder()

	rp.ServeHTTP(w, req)

	resp := w.Result()
	defer resp.Body.Close()

	// The proxy should forward the 500, not convert it to 502.
	if resp.StatusCode != http.StatusInternalServerError {
		t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusInternalServerError)
	}
}

// TestProxy_PreservesQueryParams verifies that URL query parameters
// are correctly forwarded to the backend.
func TestProxy_PreservesQueryParams(t *testing.T) {
	var receivedQuery string

	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedQuery = r.URL.RawQuery
		w.WriteHeader(http.StatusOK)
	}))
	defer backend.Close()

	rp, err := New(backend.URL, newTestLogger())
	if err != nil {
		t.Fatalf("New() error: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/search?q=hello&page=2&sort=desc", nil)
	w := httptest.NewRecorder()

	rp.ServeHTTP(w, req)

	if receivedQuery != "q=hello&page=2&sort=desc" {
		t.Errorf("backend received query %q, want %q", receivedQuery, "q=hello&page=2&sort=desc")
	}
}

// TestProxy_MultipleHTTPMethods verifies that the proxy correctly handles
// different HTTP methods (GET, POST, PUT, DELETE, PATCH).
func TestProxy_MultipleHTTPMethods(t *testing.T) {
	methods := []string{
		http.MethodGet,
		http.MethodPost,
		http.MethodPut,
		http.MethodDelete,
		http.MethodPatch,
		http.MethodHead,
		http.MethodOptions,
	}

	for _, method := range methods {
		t.Run(method, func(t *testing.T) {
			var receivedMethod string

			backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				receivedMethod = r.Method
				w.WriteHeader(http.StatusOK)
			}))
			defer backend.Close()

			rp, err := New(backend.URL, newTestLogger())
			if err != nil {
				t.Fatalf("New() error: %v", err)
			}

			req := httptest.NewRequest(method, "/", nil)
			w := httptest.NewRecorder()

			rp.ServeHTTP(w, req)

			if receivedMethod != method {
				t.Errorf("backend received method %q, want %q", receivedMethod, method)
			}
		})
	}
}

// BenchmarkProxy_ServeHTTP benchmarks the proxy's per-request overhead.
//
// This measures the cost of Director + header manipulation + response
// modification — NOT the backend latency. The backend returns immediately.
//
// Use this as a baseline. In later phases, we'll track regressions when
// adding middleware (rate limiting, circuit breaking, etc.).
func BenchmarkProxy_ServeHTTP(b *testing.B) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("OK"))
	}))
	defer backend.Close()

	rp, err := New(backend.URL, zap.NewNop())
	if err != nil {
		b.Fatalf("New() error: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/", nil)

	b.ResetTimer()
	b.ReportAllocs()

	for i := 0; i < b.N; i++ {
		w := httptest.NewRecorder()
		rp.ServeHTTP(w, req)
	}
}
