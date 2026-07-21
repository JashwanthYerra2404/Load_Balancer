package pool

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"go.uber.org/zap"
)

func newTestLogger() *zap.Logger {
	return zap.NewNop()
}

// TestNewBackend_Valid verifies basic backend creation.
func TestNewBackend_Valid(t *testing.T) {
	b, err := NewBackend("http://localhost:9001", "backend-1", 1, 0, newTestLogger())
	if err != nil {
		t.Fatalf("NewBackend() error: %v", err)
	}

	if b.Name() != "backend-1" {
		t.Errorf("Name() = %q, want %q", b.Name(), "backend-1")
	}
	if b.URL() != "http://localhost:9001" {
		t.Errorf("URL() = %q, want %q", b.URL(), "http://localhost:9001")
	}
	if b.Weight() != 1 {
		t.Errorf("Weight() = %d, want 1", b.Weight())
	}
	if !b.IsAlive() {
		t.Error("new backend should be alive")
	}
	if b.ActiveConnections() != 0 {
		t.Errorf("ActiveConnections() = %d, want 0", b.ActiveConnections())
	}
}

// TestNewBackend_InvalidURL verifies error handling for bad URLs.
func TestNewBackend_InvalidURL(t *testing.T) {
	_, err := NewBackend("://invalid", "bad", 1, 0, newTestLogger())
	if err == nil {
		t.Fatal("NewBackend() should return error for invalid URL")
	}
}

// TestNewBackend_DefaultWeight verifies that zero/negative weight defaults to 1.
func TestNewBackend_DefaultWeight(t *testing.T) {
	b, _ := NewBackend("http://localhost:9001", "test", 0, 0, newTestLogger())
	if b.Weight() != 1 {
		t.Errorf("Weight() = %d, want 1 (default)", b.Weight())
	}

	b2, _ := NewBackend("http://localhost:9001", "test", -5, 0, newTestLogger())
	if b2.Weight() != 1 {
		t.Errorf("Weight() = %d, want 1 (default for negative)", b2.Weight())
	}
}

// TestBackend_AliveToggle verifies atomic alive status updates.
func TestBackend_AliveToggle(t *testing.T) {
	b, _ := NewBackend("http://localhost:9001", "test", 1, 0, newTestLogger())

	if !b.IsAlive() {
		t.Fatal("should start alive")
	}

	b.SetAlive(false)
	if b.IsAlive() {
		t.Error("should be dead after SetAlive(false)")
	}

	b.SetAlive(true)
	if !b.IsAlive() {
		t.Error("should be alive after SetAlive(true)")
	}
}

// TestBackend_ActiveConnections verifies atomic connection counting.
func TestBackend_ActiveConnections(t *testing.T) {
	b, _ := NewBackend("http://localhost:9001", "test", 1, 0, newTestLogger())

	if b.ActiveConnections() != 0 {
		t.Fatalf("initial connections = %d, want 0", b.ActiveConnections())
	}

	// Simulate connection tracking without HTTP.
	b.activeConnections.Add(1)
	if b.ActiveConnections() != 1 {
		t.Errorf("after +1: connections = %d, want 1", b.ActiveConnections())
	}

	b.activeConnections.Add(1)
	if b.ActiveConnections() != 2 {
		t.Errorf("after +2: connections = %d, want 2", b.ActiveConnections())
	}

	b.activeConnections.Add(-1)
	if b.ActiveConnections() != 1 {
		t.Errorf("after -1: connections = %d, want 1", b.ActiveConnections())
	}
}

// TestBackend_IsAtCapacity verifies connection limit enforcement.
func TestBackend_IsAtCapacity(t *testing.T) {
	// Unlimited (maxConns=0)
	b, _ := NewBackend("http://localhost:9001", "test", 1, 0, newTestLogger())
	b.activeConnections.Store(1000)
	if b.IsAtCapacity() {
		t.Error("should never be at capacity with maxConns=0")
	}

	// Limited (maxConns=5)
	b2, _ := NewBackend("http://localhost:9001", "test", 1, 5, newTestLogger())
	b2.activeConnections.Store(4)
	if b2.IsAtCapacity() {
		t.Error("4/5 should not be at capacity")
	}

	b2.activeConnections.Store(5)
	if !b2.IsAtCapacity() {
		t.Error("5/5 should be at capacity")
	}

	b2.activeConnections.Store(6)
	if !b2.IsAtCapacity() {
		t.Error("6/5 should be at capacity")
	}
}

// TestBackend_ServeHTTP verifies request forwarding and connection tracking.
func TestBackend_ServeHTTP(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{
			"path":   r.URL.Path,
			"method": r.Method,
		})
	}))
	defer backend.Close()

	b, err := NewBackend(backend.URL, "test-backend", 1, 0, newTestLogger())
	if err != nil {
		t.Fatalf("NewBackend() error: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/api/test", nil)
	w := httptest.NewRecorder()

	b.ServeHTTP(w, req)

	resp := w.Result()
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Errorf("status = %d, want 200", resp.StatusCode)
	}

	// Verify connection count returned to 0 after request completes.
	if b.ActiveConnections() != 0 {
		t.Errorf("ActiveConnections() = %d, want 0 after request", b.ActiveConnections())
	}

	// Verify proxy headers were added.
	if got := resp.Header.Get("X-Backend-Name"); got != "test-backend" {
		t.Errorf("X-Backend-Name = %q, want %q", got, "test-backend")
	}
	if got := resp.Header.Get("Via"); !strings.Contains(got, "load-balancer") {
		t.Errorf("Via = %q, should contain 'load-balancer'", got)
	}

	// Verify body was forwarded correctly.
	body, _ := io.ReadAll(resp.Body)
	var result map[string]string
	json.Unmarshal(body, &result)
	if result["path"] != "/api/test" {
		t.Errorf("path = %q, want %q", result["path"], "/api/test")
	}
}

// TestBackend_ServeHTTP_BackendDown verifies 502 when backend is unreachable.
func TestBackend_ServeHTTP_BackendDown(t *testing.T) {
	b, _ := NewBackend("http://localhost:1", "dead-backend", 1, 0, newTestLogger())

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	w := httptest.NewRecorder()

	b.ServeHTTP(w, req)

	resp := w.Result()
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusBadGateway {
		t.Errorf("status = %d, want 502", resp.StatusCode)
	}

	// Connection count should still be 0 (defer handles decrement).
	if b.ActiveConnections() != 0 {
		t.Errorf("ActiveConnections() = %d, want 0 after failed request", b.ActiveConnections())
	}
}
