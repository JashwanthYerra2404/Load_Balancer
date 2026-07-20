package server

import (
	"net/http"
	"testing"
	"time"

	"go.uber.org/zap"

	"github.com/JashwanthYerra2404/Load_Balancer/internal/config"
)

// TestNew_SetsTimeouts verifies that server timeouts are correctly propagated
// from the config to the underlying http.Server.
func TestNew_SetsTimeouts(t *testing.T) {
	cfg := config.ServerConfig{
		Port:         8080,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 20 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {})
	srv := New(cfg, handler, zap.NewNop())

	if srv.httpServer.ReadTimeout != 10*time.Second {
		t.Errorf("ReadTimeout = %v, want 10s", srv.httpServer.ReadTimeout)
	}
	if srv.httpServer.WriteTimeout != 20*time.Second {
		t.Errorf("WriteTimeout = %v, want 20s", srv.httpServer.WriteTimeout)
	}
	if srv.httpServer.IdleTimeout != 60*time.Second {
		t.Errorf("IdleTimeout = %v, want 60s", srv.httpServer.IdleTimeout)
	}
	if srv.httpServer.ReadHeaderTimeout != 5*time.Second {
		t.Errorf("ReadHeaderTimeout = %v, want 5s (half of ReadTimeout)", srv.httpServer.ReadHeaderTimeout)
	}
}

// TestNew_SetsAddr verifies that the server address is correctly formatted
// from the port number.
func TestNew_SetsAddr(t *testing.T) {
	cfg := config.ServerConfig{
		Port:         9090,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {})
	srv := New(cfg, handler, zap.NewNop())

	if srv.httpServer.Addr != ":9090" {
		t.Errorf("Addr = %q, want %q", srv.httpServer.Addr, ":9090")
	}
}

// TestNew_AcceptsNilHandler verifies that the server doesn't panic
// when given a nil handler (http.DefaultServeMux is used).
func TestNew_AcceptsHandler(t *testing.T) {
	cfg := config.ServerConfig{
		Port:         8080,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// This should not panic.
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	srv := New(cfg, handler, zap.NewNop())

	if srv.httpServer.Handler == nil {
		t.Error("Handler should not be nil")
	}
}
