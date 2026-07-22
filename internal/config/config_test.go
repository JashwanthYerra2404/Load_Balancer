package config

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// TestLoad_ValidConfig verifies that a well-formed multi-backend config
// is parsed correctly with all fields populated.
func TestLoad_ValidConfig(t *testing.T) {
	content := `
server:
  port: 9090
  read_timeout: 10s
  write_timeout: 20s
  idle_timeout: 120s
backends:
  - url: "http://localhost:9001"
    name: "backend-1"
    weight: 3
    max_connections: 100
  - url: "http://localhost:9002"
    name: "backend-2"
    weight: 2
`
	path := writeTestConfig(t, content)

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load() returned unexpected error: %v", err)
	}

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

	if len(cfg.Backends) != 2 {
		t.Fatalf("len(Backends) = %d, want 2", len(cfg.Backends))
	}

	b1 := cfg.Backends[0]
	if b1.URL != "http://localhost:9001" {
		t.Errorf("Backends[0].URL = %q, want %q", b1.URL, "http://localhost:9001")
	}
	if b1.Name != "backend-1" {
		t.Errorf("Backends[0].Name = %q, want %q", b1.Name, "backend-1")
	}
	if b1.Weight != 3 {
		t.Errorf("Backends[0].Weight = %d, want 3", b1.Weight)
	}
	if b1.MaxConnections != 100 {
		t.Errorf("Backends[0].MaxConnections = %d, want 100", b1.MaxConnections)
	}

	b2 := cfg.Backends[1]
	if b2.Name != "backend-2" {
		t.Errorf("Backends[1].Name = %q, want %q", b2.Name, "backend-2")
	}
	if b2.Weight != 2 {
		t.Errorf("Backends[1].Weight = %d, want 2", b2.Weight)
	}
}

// TestLoad_DefaultsApplied verifies that server defaults are applied for
// minimal configs.
func TestLoad_DefaultsApplied(t *testing.T) {
	content := `
backends:
  - url: "http://localhost:9001"
    name: "backend-1"
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

// TestLoad_NoBackends verifies that an empty backends list is rejected.
func TestLoad_NoBackends(t *testing.T) {
	content := `
server:
  port: 8080
backends: []
`
	path := writeTestConfig(t, content)

	_, err := Load(path)
	if err == nil {
		t.Fatal("Load() should return error for empty backends")
	}
}

// TestLoad_MissingBackends verifies that omitting backends entirely is rejected.
func TestLoad_MissingBackends(t *testing.T) {
	content := `
server:
  port: 8080
`
	path := writeTestConfig(t, content)

	_, err := Load(path)
	if err == nil {
		t.Fatal("Load() should return error for missing backends")
	}
}

// TestLoad_InvalidBackendScheme verifies rejection of non-HTTP(S) schemes.
func TestLoad_InvalidBackendScheme(t *testing.T) {
	content := `
backends:
  - url: "ftp://localhost:9001"
    name: "bad-scheme"
`
	path := writeTestConfig(t, content)

	_, err := Load(path)
	if err == nil {
		t.Fatal("Load() should return error for ftp:// scheme")
	}
}

// TestLoad_MissingBackendName verifies that a backend without a name is rejected.
func TestLoad_MissingBackendName(t *testing.T) {
	content := `
backends:
  - url: "http://localhost:9001"
`
	path := writeTestConfig(t, content)

	_, err := Load(path)
	if err == nil {
		t.Fatal("Load() should return error for missing backend name")
	}
}

// TestLoad_DuplicateBackendNames verifies that duplicate names are rejected.
func TestLoad_DuplicateBackendNames(t *testing.T) {
	content := `
backends:
  - url: "http://localhost:9001"
    name: "same-name"
  - url: "http://localhost:9002"
    name: "same-name"
`
	path := writeTestConfig(t, content)

	_, err := Load(path)
	if err == nil {
		t.Fatal("Load() should return error for duplicate backend names")
	}
}

// TestLoad_NegativeWeight verifies that negative weights are rejected.
func TestLoad_NegativeWeight(t *testing.T) {
	content := `
backends:
  - url: "http://localhost:9001"
    name: "backend-1"
    weight: -5
`
	path := writeTestConfig(t, content)

	_, err := Load(path)
	if err == nil {
		t.Fatal("Load() should return error for negative weight")
	}
}

// TestLoad_NegativeMaxConnections verifies that negative max_connections is rejected.
func TestLoad_NegativeMaxConnections(t *testing.T) {
	content := `
backends:
  - url: "http://localhost:9001"
    name: "backend-1"
    max_connections: -1
`
	path := writeTestConfig(t, content)

	_, err := Load(path)
	if err == nil {
		t.Fatal("Load() should return error for negative max_connections")
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
			content := fmt.Sprintf("server:\n  port: %d\nbackends:\n  - url: \"http://localhost:9001\"\n    name: \"b1\"\n", tt.port)
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

// TestLoad_MultipleBackends verifies that 3+ backends are parsed correctly.
func TestLoad_MultipleBackends(t *testing.T) {
	content := `
backends:
  - url: "http://localhost:9001"
    name: "b1"
    weight: 1
  - url: "http://localhost:9002"
    name: "b2"
    weight: 2
  - url: "http://localhost:9003"
    name: "b3"
    weight: 3
`
	path := writeTestConfig(t, content)

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load() returned unexpected error: %v", err)
	}

	if len(cfg.Backends) != 3 {
		t.Fatalf("len(Backends) = %d, want 3", len(cfg.Backends))
	}

	expectedNames := []string{"b1", "b2", "b3"}
	for i, name := range expectedNames {
		if cfg.Backends[i].Name != name {
			t.Errorf("Backends[%d].Name = %q, want %q", i, cfg.Backends[i].Name, name)
		}
	}
}

// TestLoad_InvalidBackendURL verifies that a backend URL without a host is rejected.
func TestLoad_InvalidBackendURL(t *testing.T) {
	content := `
backends:
  - url: "http://"
    name: "bad-url"
`
	path := writeTestConfig(t, content)

	_, err := Load(path)
	if err == nil {
		t.Fatal("Load() should return error for URL without host")
	}
}

// TestLoad_DefaultAlgorithm verifies that the algorithm defaults to round_robin
// when not specified.
func TestLoad_DefaultAlgorithm(t *testing.T) {
	content := `
backends:
  - url: "http://localhost:9001"
    name: "b1"
`
	path := writeTestConfig(t, content)

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load() returned unexpected error: %v", err)
	}

	if cfg.Algorithm != AlgorithmRoundRobin {
		t.Errorf("Algorithm = %q, want %q", cfg.Algorithm, AlgorithmRoundRobin)
	}
}

// TestLoad_ValidAlgorithms verifies that all supported algorithm names are accepted.
func TestLoad_ValidAlgorithms(t *testing.T) {
	algorithms := []string{
		"round_robin",
		"least_connections",
		"weighted_round_robin",
		"ip_hash",
		"random",
	}

	for _, algo := range algorithms {
		t.Run(algo, func(t *testing.T) {
			content := fmt.Sprintf("algorithm: %s\nbackends:\n  - url: \"http://localhost:9001\"\n    name: \"b1\"\n", algo)
			path := writeTestConfig(t, content)

			cfg, err := Load(path)
			if err != nil {
				t.Fatalf("Load() returned unexpected error for algorithm %q: %v", algo, err)
			}
			if cfg.Algorithm != algo {
				t.Errorf("Algorithm = %q, want %q", cfg.Algorithm, algo)
			}
		})
	}
}

// TestLoad_InvalidAlgorithm verifies that unsupported algorithm names are rejected.
func TestLoad_InvalidAlgorithm(t *testing.T) {
	content := `
algorithm: "fastest_response"
backends:
  - url: "http://localhost:9001"
    name: "b1"
`
	path := writeTestConfig(t, content)

	_, err := Load(path)
	if err == nil {
		t.Fatal("Load() should return error for unsupported algorithm")
	}
}

// writeTestConfig is a test helper that writes config content to a temporary file.
func writeTestConfig(t *testing.T, content string) string {
	t.Helper()

	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")

	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		t.Fatalf("failed to write test config: %v", err)
	}

	return path
}

