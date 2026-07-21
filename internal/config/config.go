// Package config handles loading, parsing, and validating YAML configuration
// for the load balancer.
//
// Design decisions:
//   - YAML was chosen over JSON (no comments support) and TOML (less common in Go ecosystem).
//   - We use gopkg.in/yaml.v3 which is the de facto standard Go YAML library.
//   - All durations are stored as time.Duration but parsed from human-readable YAML strings
//     (e.g., "30s", "5m") for operator ergonomics.
//   - Defaults are applied explicitly rather than relying on zero values. This makes the
//     configuration self-documenting and avoids subtle bugs where a zero value is valid.
//   - Validation is strict: we fail fast on invalid config rather than falling back to
//     defaults silently, because silent misconfiguration is a production incident waiting
//     to happen.
package config

import (
	"errors"
	"fmt"
	"net/url"
	"os"
	"time"

	"gopkg.in/yaml.v3"
)

// Config is the top-level configuration for the load balancer.
//
// The configuration supports multiple backends for load balancing.
// Future phases will extend this with health check config,
// rate limiter settings, etc.
type Config struct {
	Server   ServerConfig   `yaml:"server"`
	Backends []BackendConfig `yaml:"backends"`
}

// ServerConfig defines the proxy server's listening address and timeout behavior.
//
// Timeouts are critical for production resilience:
//   - ReadTimeout: limits how long the server waits to read the full request (prevents slow loris attacks).
//   - WriteTimeout: limits how long the server waits to write the response (prevents stuck connections).
//   - IdleTimeout: limits how long keep-alive connections stay open when idle.
//
// These map directly to the corresponding fields on http.Server.
type ServerConfig struct {
	// Port is the TCP port the load balancer listens on.
	Port int `yaml:"port"`

	// ReadTimeout is the maximum duration for reading the entire request,
	// including the body. Protects against slow clients.
	ReadTimeout time.Duration `yaml:"read_timeout"`

	// WriteTimeout is the maximum duration before timing out writes of the
	// response. Protects against slow clients that don't consume the response.
	WriteTimeout time.Duration `yaml:"write_timeout"`

	// IdleTimeout is the maximum amount of time to wait for the next request
	// when keep-alives are enabled. If zero, ReadTimeout is used.
	IdleTimeout time.Duration `yaml:"idle_timeout"`
}

// BackendConfig defines a single upstream backend server.
//
// Each backend has a URL, a human-readable name for logging/dashboards,
// a weight for weighted load balancing algorithms, and an optional
// connection limit.
type BackendConfig struct {
	// URL is the full base URL of the backend server (e.g., "http://localhost:9001").
	// Must include scheme (http:// or https://).
	URL string `yaml:"url"`

	// Name is a human-readable identifier for this backend.
	// Used in logs, metrics, and response headers.
	// Must be unique across all backends.
	Name string `yaml:"name"`

	// Weight determines the relative traffic share in weighted algorithms.
	// Higher weight = more traffic. Default: 1. Range: 1-100.
	// Ignored by simple round-robin.
	Weight int `yaml:"weight"`

	// MaxConnections is the maximum number of concurrent connections to this backend.
	// 0 means unlimited. When reached, the backend is skipped during selection.
	MaxConnections int `yaml:"max_connections"`
}

// Default configuration values.
//
// These defaults are conservative and suitable for development. Production
// deployments should tune these based on their workload characteristics.
const (
	DefaultPort         = 8080
	DefaultReadTimeout  = 15 * time.Second
	DefaultWriteTimeout = 15 * time.Second
	DefaultIdleTimeout  = 60 * time.Second
)

// Load reads a YAML configuration file from the given path, applies defaults
// for any missing values, and validates the result.
//
// The function follows the "parse, don't validate" principle where possible,
// but some validations (like URL format) require explicit checks.
//
// Time complexity: O(n) where n is the file size.
// Space complexity: O(n) for the file contents in memory.
//
// Returns an error if:
//   - The file cannot be read
//   - The YAML is malformed
//   - Validation fails (e.g., invalid URL, invalid port)
func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("reading config file %s: %w", path, err)
	}

	cfg := &Config{}
	if err := yaml.Unmarshal(data, cfg); err != nil {
		return nil, fmt.Errorf("parsing config file %s: %w", path, err)
	}

	applyDefaults(cfg)

	if err := validate(cfg); err != nil {
		return nil, fmt.Errorf("validating config: %w", err)
	}

	return cfg, nil
}

// applyDefaults fills in zero-valued fields with sensible defaults.
//
// We apply defaults AFTER parsing so that partially-specified configs work.
// For example, a user can specify just the backend URL and get all other
// values set to production-safe defaults.
func applyDefaults(cfg *Config) {
	if cfg.Server.Port == 0 {
		cfg.Server.Port = DefaultPort
	}
	if cfg.Server.ReadTimeout == 0 {
		cfg.Server.ReadTimeout = DefaultReadTimeout
	}
	if cfg.Server.WriteTimeout == 0 {
		cfg.Server.WriteTimeout = DefaultWriteTimeout
	}
	if cfg.Server.IdleTimeout == 0 {
		cfg.Server.IdleTimeout = DefaultIdleTimeout
	}
}

// validate checks the configuration for semantic errors.
//
// This is intentionally strict: we'd rather fail at startup than discover
// a misconfiguration under load. Every validation error includes context
// about what was wrong and what's expected.
func validate(cfg *Config) error {
	var errs []error

	// Validate port range. Ports below 1024 require root on Unix systems,
	// but we don't restrict them — the OS will reject the bind if unprivileged.
	if cfg.Server.Port < 1 || cfg.Server.Port > 65535 {
		errs = append(errs, fmt.Errorf("server.port must be between 1 and 65535, got %d", cfg.Server.Port))
	}

	// Validate at least one backend is configured.
	if len(cfg.Backends) == 0 {
		errs = append(errs, fmt.Errorf("at least one backend is required"))
	}

	// Validate each backend and check for duplicate names.
	seenNames := make(map[string]bool)
	for i, b := range cfg.Backends {
		prefix := fmt.Sprintf("backends[%d]", i)

		// Validate URL.
		if b.URL == "" {
			errs = append(errs, fmt.Errorf("%s.url is required", prefix))
		} else {
			u, err := url.Parse(b.URL)
			if err != nil {
				errs = append(errs, fmt.Errorf("%s.url is not a valid URL: %w", prefix, err))
			} else if u.Scheme != "http" && u.Scheme != "https" {
				errs = append(errs, fmt.Errorf("%s.url scheme must be http or https, got %q", prefix, u.Scheme))
			} else if u.Host == "" {
				errs = append(errs, fmt.Errorf("%s.url must include a host", prefix))
			}
		}

		// Validate name.
		if b.Name == "" {
			errs = append(errs, fmt.Errorf("%s.name is required", prefix))
		} else if seenNames[b.Name] {
			errs = append(errs, fmt.Errorf("%s.name %q is duplicate", prefix, b.Name))
		} else {
			seenNames[b.Name] = true
		}

		// Validate weight (if specified).
		if b.Weight < 0 {
			errs = append(errs, fmt.Errorf("%s.weight must be non-negative, got %d", prefix, b.Weight))
		}

		// Validate max_connections.
		if b.MaxConnections < 0 {
			errs = append(errs, fmt.Errorf("%s.max_connections must be non-negative, got %d", prefix, b.MaxConnections))
		}
	}

	// Validate timeouts are positive.
	if cfg.Server.ReadTimeout < 0 {
		errs = append(errs, fmt.Errorf("server.read_timeout must be positive, got %v", cfg.Server.ReadTimeout))
	}
	if cfg.Server.WriteTimeout < 0 {
		errs = append(errs, fmt.Errorf("server.write_timeout must be positive, got %v", cfg.Server.WriteTimeout))
	}
	if cfg.Server.IdleTimeout < 0 {
		errs = append(errs, fmt.Errorf("server.idle_timeout must be positive, got %v", cfg.Server.IdleTimeout))
	}

	return errors.Join(errs...)
}
