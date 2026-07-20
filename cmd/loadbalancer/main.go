// Package main is the entry point for the load balancer.
//
// Responsibilities:
//   - Parse command-line flags
//   - Load and validate configuration
//   - Initialize structured logging
//   - Create the reverse proxy
//   - Start the HTTP server
//   - Handle graceful shutdown
//
// This follows the "main is the composition root" pattern: main() wires
// together all dependencies but contains no business logic. This makes
// the application testable (all logic is in internal/ packages) and
// makes dependency flow clear.
package main

import (
	"flag"
	"log"

	"go.uber.org/zap"

	"github.com/JashwanthYerra2404/Load_Balancer/internal/config"
	"github.com/JashwanthYerra2404/Load_Balancer/internal/proxy"
	"github.com/JashwanthYerra2404/Load_Balancer/internal/server"
)

func main() {
	// Parse command-line flags.
	// We use a flag rather than a hardcoded path so the binary works in
	// different environments (dev, CI, production) without recompilation.
	configPath := flag.String("config", "configs/config.yaml", "path to configuration file")
	flag.Parse()

	// Initialize the structured logger.
	//
	// We use zap.NewProduction() which:
	//   - Outputs JSON (machine-parseable for log aggregation)
	//   - Includes timestamps, caller info, and stack traces for errors
	//   - Is ~10x faster than fmt.Printf (lock-free, zero-alloc for common cases)
	//
	// In Phase 12, we'll add more sophisticated log configuration
	// (log levels, output destinations, sampling).
	logger, err := zap.NewProduction()
	if err != nil {
		log.Fatalf("failed to initialize logger: %v", err)
	}
	defer logger.Sync()

	// Load configuration.
	cfg, err := config.Load(*configPath)
	if err != nil {
		logger.Fatal("failed to load configuration",
			zap.String("path", *configPath),
			zap.Error(err),
		)
	}

	logger.Info("configuration loaded",
		zap.String("path", *configPath),
		zap.Int("port", cfg.Server.Port),
		zap.String("backend", cfg.Backend.URL),
	)

	// Create the reverse proxy.
	reverseProxy, err := proxy.New(cfg.Backend.URL, logger)
	if err != nil {
		logger.Fatal("failed to create reverse proxy",
			zap.String("backend", cfg.Backend.URL),
			zap.Error(err),
		)
	}

	// Create and start the HTTP server.
	// Start() blocks until a shutdown signal is received or an error occurs.
	srv := server.New(cfg.Server, reverseProxy, logger)

	logger.Info("load balancer starting",
		zap.Int("port", cfg.Server.Port),
		zap.String("backend", cfg.Backend.URL),
	)

	if err := srv.Start(); err != nil {
		logger.Fatal("server error", zap.Error(err))
	}
}
