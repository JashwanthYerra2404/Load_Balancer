package config

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// TestLoad_ValidConfig verifies that a well-formed config file is parsed correctly
// and all fields are populated as expected.
func TestLoad_ValidConfig(t *testing.T) {
	content := `
server:
  port: 9090
  read_timeout: 10s
  write_timeout: 20s
  idle_timeout: 120s
backend:
  url: "http://localhost:9001"
`
	path := writeTestConfig(t, content)

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load() returned unexpected error: %v", err)
	}

	// Verify each field explicitly rather than using reflect.DeepEqual.
	// This makes test failures much more readable.
	if cfg.Server.Port != 9090 {
		t.Errorf("Server.Port = %d, want 9090", cfg.Server.Port)
	}
	if cfg.Server.ReadTimeout != 10*time.Second {
		t.Errorf("Server.ReadTimeout = %v, want 10s", cfg.Server.ReadTimeout)
	}
	if cfg.Server.WriteTimeout != 20*time.Second {
		t.Errorf("Server.WriteTimeout = %v, want 20s", cfg.Server.WriteTimeout)
	}
	if cfg.Server.IdleTimeout != 120*time.Second {
		t.Errorf("Server.IdleTimeout = %v, want 120s", cfg.Server.IdleTimeout)
	}
	if cfg.Backend.URL != "http://localhost:9001" {
		t.Errorf("Backend.URL = %q, want %q", cfg.Backend.URL, "http://localhost:9001")
	}
}

// TestLoad_DefaultsApplied verifies that missing fields get filled with
// sensible defaults. Operators should be able to specify a minimal config
// with just the backend URL and get everything else defaulted safely.
func TestLoad_DefaultsApplied(t *testing.T) {
	content := `
backend:
  url: "http://localhost:9001"
`
	path := writeTestConfig(t, content)

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load() returned unexpected error: %v", err)
	}

	if cfg.Server.Port != DefaultPort {
		t.Errorf("Server.Port = %d, want default %d", cfg.Server.Port, DefaultPort)
	}
	if cfg.Server.ReadTimeout != DefaultReadTimeout {
		t.Errorf("Server.ReadTimeout = %v, want default %v", cfg.Server.ReadTimeout, DefaultReadTimeout)
	}
	if cfg.Server.WriteTimeout != DefaultWriteTimeout {
		t.Errorf("Server.WriteTimeout = %v, want default %v", cfg.Server.WriteTimeout, DefaultWriteTimeout)
	}
	if cfg.Server.IdleTimeout != DefaultIdleTimeout {
		t.Errorf("Server.IdleTimeout = %v, want default %v", cfg.Server.IdleTimeout, DefaultIdleTimeout)
	}
}

// TestLoad_MissingBackendURL verifies that validation catches a missing
// backend URL. This is a required field — without it, the proxy has nowhere
// to forward traffic.
func TestLoad_MissingBackendURL(t *testing.T) {
	content := `
server:
  port: 8080
`
	path := writeTestConfig(t, content)

	_, err := Load(path)
	if err == nil {
		t.Fatal("Load() should return error for missing backend URL")
	}
}

// TestLoad_InvalidBackendScheme verifies rejection of non-HTTP(S) schemes.
// The proxy only supports HTTP and HTTPS upstream connections.
func TestLoad_InvalidBackendScheme(t *testing.T) {
	content := `
backend:
  url: "ftp://localhost:9001"
`
	path := writeTestConfig(t, content)

	_, err := Load(path)
	if err == nil {
		t.Fatal("Load() should return error for ftp:// scheme")
	}
}

// TestLoad_InvalidPort verifies that out-of-range ports are rejected.
func TestLoad_InvalidPort(t *testing.T) {
	tests := []struct {
		name    string
		port    int
		wantErr bool
	}{
		{"zero port uses default", 0, false},
		{"negative port", -1, true},
		{"too high", 70000, true},
		{"valid low", 1, false},
		{"valid high", 65535, false},
		{"common port", 8080, false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			content := fmt.Sprintf("server:\n  port: %d\nbackend:\n  url: \"http://localhost:9001\"\n", tt.port)
			path := writeTestConfig(t, content)

			_, err := Load(path)
			if tt.wantErr && err == nil {
				t.Errorf("Load() should return error for port %d", tt.port)
			}
			if !tt.wantErr && err != nil {
				t.Errorf("Load() returned unexpected error for port %d: %v", tt.port, err)
			}
		})
	}
}

// TestLoad_FileNotFound verifies error handling when the config file doesn't exist.
func TestLoad_FileNotFound(t *testing.T) {
	_, err := Load("/nonexistent/config.yaml")
	if err == nil {
		t.Fatal("Load() should return error for nonexistent file")
	}
}

// TestLoad_MalformedYAML verifies error handling for unparseable YAML.
func TestLoad_MalformedYAML(t *testing.T) {
	content := `
server:
  port: [invalid yaml
`
	path := writeTestConfig(t, content)

	_, err := Load(path)
	if err == nil {
		t.Fatal("Load() should return error for malformed YAML")
	}
}

// TestLoad_InvalidBackendURL verifies that a backend URL without a host is rejected.
func TestLoad_InvalidBackendURL(t *testing.T) {
	content := `
backend:
  url: "http://"
`
	path := writeTestConfig(t, content)

	_, err := Load(path)
	if err == nil {
		t.Fatal("Load() should return error for URL without host")
	}
}

// writeTestConfig is a test helper that writes config content to a temporary file.
// It uses t.TempDir() which automatically cleans up after the test.
func writeTestConfig(t *testing.T, content string) string {
	t.Helper()

	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")

	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		t.Fatalf("failed to write test config: %v", err)
	}

	return path
}
