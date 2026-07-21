// Package main is the entry point for the load balancer.
//
// This follows the "main is the composition root" pattern: main() wires
// together all dependencies but contains no business logic.
//
// Phase 2 changes:
//   - Creates a BackendPool from the config's backends list
//   - Creates a Backend for each configured backend
//   - Passes the pool to the proxy instead of a single URL
package main

import (
	"flag"
	"log"

	"go.uber.org/zap"

	"github.com/JashwanthYerra2404/Load_Balancer/internal/config"
	"github.com/JashwanthYerra2404/Load_Balancer/internal/pool"
	"github.com/JashwanthYerra2404/Load_Balancer/internal/proxy"
	"github.com/JashwanthYerra2404/Load_Balancer/internal/server"
)

func main() {
	configPath := flag.String("config", "configs/config.yaml", "path to configuration file")
	flag.Parse()

	// Initialize structured logger.
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
		zap.Int("backends", len(cfg.Backends)),
	)

	// Create the backend pool and populate it with backends from config.
	//
	// Each backend gets its own httputil.ReverseProxy with an isolated
	// connection pool (http.Transport). This provides fault isolation:
	// a slow backend won't exhaust connections meant for healthy ones.
	backendPool := pool.NewRoundRobinPool(logger)

	for _, backendCfg := range cfg.Backends {
		b, err := pool.NewBackend(
			backendCfg.URL,
			backendCfg.Name,
			backendCfg.Weight,
			backendCfg.MaxConnections,
			logger,
		)
		if err != nil {
			logger.Fatal("failed to create backend",
				zap.String("name", backendCfg.Name),
				zap.String("url", backendCfg.URL),
				zap.Error(err),
			)
		}
		backendPool.AddBackend(b)
	}

	// Create the reverse proxy with the backend pool.
	reverseProxy := proxy.New(backendPool, logger)

	// Create and start the HTTP server.
	srv := server.New(cfg.Server, reverseProxy, logger)

	logger.Info("load balancer starting",
		zap.Int("port", cfg.Server.Port),
		zap.Int("backends", len(cfg.Backends)),
	)

	if err := srv.Start(); err != nil {
		logger.Fatal("server error", zap.Error(err))
	}
}
