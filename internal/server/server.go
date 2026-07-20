// Package server manages the HTTP server lifecycle for the load balancer.
//
// It wraps http.Server with:
//   - Configurable timeouts from the config package
//   - Graceful shutdown via OS signal handling (SIGINT, SIGTERM)
//   - Clean startup/shutdown logging
//
// Why a separate package?
//   Separating server lifecycle from proxy logic follows the Single Responsibility
//   Principle. The proxy package doesn't need to know about TCP listeners, signal
//   handling, or shutdown draining. This separation also makes testing easier:
//   proxy tests don't need to spin up a real server.
//
// Concurrency model:
//   - Start() blocks the calling goroutine (it calls http.Server.ListenAndServe).
//   - Shutdown is triggered asynchronously via OS signals.
//   - Graceful shutdown waits for in-flight requests to complete (up to a timeout).
package server

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"go.uber.org/zap"

	"github.com/JashwanthYerra2404/Load_Balancer/internal/config"
)

// Server wraps http.Server with lifecycle management.
//
// Design: We own the http.Server rather than accepting one from the caller.
// This lets us guarantee that timeouts and shutdown behavior are configured
// correctly. The caller provides the handler (our proxy) and the config.
type Server struct {
	httpServer *http.Server
	logger     *zap.Logger
}

// New creates a new Server with the given configuration and handler.
//
// The handler is typically our ReverseProxy, but accepting http.Handler
// makes this composable — we can wrap the proxy in middleware later
// (logging, metrics, recovery) without changing this package.
func New(cfg config.ServerConfig, handler http.Handler, logger *zap.Logger) *Server {
	return &Server{
		httpServer: &http.Server{
			Addr:    fmt.Sprintf(":%d", cfg.Port),
			Handler: handler,

			// ReadTimeout covers the entire request read, including the body.
			// Protects against slow loris attacks where an attacker sends
			// headers very slowly to hold connections open.
			ReadTimeout: cfg.ReadTimeout,

			// WriteTimeout covers the time from the end of the request header
			// read to the end of the response write. Protects against slow
			// clients that don't consume the response.
			WriteTimeout: cfg.WriteTimeout,

			// IdleTimeout is for keep-alive connections. After a request
			// completes, this is how long we wait for the next request
			// before closing the connection.
			IdleTimeout: cfg.IdleTimeout,

			// ReadHeaderTimeout limits the time to read request headers.
			// This is a tighter timeout than ReadTimeout and specifically
			// targets slowloris attacks. We set it to a fraction of ReadTimeout.
			ReadHeaderTimeout: cfg.ReadTimeout / 2,
		},
		logger: logger,
	}
}

// Start begins listening for HTTP connections and blocks until shutdown.
//
// The shutdown sequence:
//  1. A goroutine listens for SIGINT or SIGTERM
//  2. When received, it calls httpServer.Shutdown()
//  3. Shutdown stops accepting new connections
//  4. Shutdown waits for in-flight requests to complete (up to shutdownTimeout)
//  5. Start() returns nil (or error if shutdown was unclean)
//
// This is the standard graceful shutdown pattern recommended by the Go team.
// See: https://pkg.go.dev/net/http#Server.Shutdown
//
// Why 30s shutdown timeout?
//   - Long enough for most in-flight requests to complete naturally
//   - Short enough that deploys don't stall (Kubernetes default grace period is 30s)
//   - In Phase 10, we'll make this configurable
func (s *Server) Start() error {
	const shutdownTimeout = 30 * time.Second

	// Channel for shutdown signals. Buffered to ensure the signal is not
	// lost if we're not ready to receive it immediately.
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	// Channel to communicate server errors from the listener goroutine.
	errCh := make(chan error, 1)

	// Start the HTTP server in a separate goroutine because
	// ListenAndServe blocks.
	go func() {
		s.logger.Info("server starting",
			zap.String("addr", s.httpServer.Addr),
		)
		if err := s.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			errCh <- err
		}
	}()

	// Wait for either:
	// 1. A shutdown signal (normal case)
	// 2. A server error (e.g., port already in use)
	select {
	case sig := <-quit:
		s.logger.Info("shutdown signal received",
			zap.String("signal", sig.String()),
		)
	case err := <-errCh:
		return fmt.Errorf("server error: %w", err)
	}

	// Graceful shutdown: give in-flight requests time to complete.
	ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()

	s.logger.Info("shutting down server",
		zap.Duration("timeout", shutdownTimeout),
	)

	if err := s.httpServer.Shutdown(ctx); err != nil {
		return fmt.Errorf("server shutdown: %w", err)
	}

	s.logger.Info("server stopped gracefully")
	return nil
}
