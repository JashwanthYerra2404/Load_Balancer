// Package main is the entry point for the load balancer.
//
// This follows the "main is the composition root" pattern: main() wires
// together all dependencies but contains no business logic.
//
// Phase 3 changes:
//   - Pool creation uses a factory switch based on the configured algorithm
//   - Supports: round_robin, least_connections, weighted_round_robin, ip_hash, random
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
		zap.String("algorithm", cfg.Algorithm),
	)

	// Create the backend pool using the configured algorithm.
	//
	// This is the Strategy pattern payoff: the proxy doesn't know or care
	// which algorithm is used. We select the implementation here at startup.
	backendPool := createPool(cfg.Algorithm, logger)

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
		zap.String("algorithm", cfg.Algorithm),
	)

	if err := srv.Start(); err != nil {
		logger.Fatal("server error", zap.Error(err))
	}
}

// createPool creates a BackendPool implementation based on the algorithm name.
//
// The algorithm name is validated by config.Load(), so we can safely assume
// it's one of the supported values here.
func createPool(algorithm string, logger *zap.Logger) pool.BackendPool {
	switch algorithm {
	case config.AlgorithmLeastConnections:
		return pool.NewLeastConnectionsPool(logger)
	case config.AlgorithmWeightedRoundRobin:
		return pool.NewWeightedRoundRobinPool(logger)
	case config.AlgorithmIPHash:
		return pool.NewIPHashPool(logger)
	case config.AlgorithmRandom:
		return pool.NewRandomPool(logger)
	default:
		// Default to round-robin (includes "" and "round_robin").
		return pool.NewRoundRobinPool(logger)
	}
}

