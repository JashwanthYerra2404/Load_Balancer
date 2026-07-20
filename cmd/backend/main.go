// Package main implements a configurable backend simulator for testing
// the load balancer.
//
// This is NOT a toy echo server. It simulates realistic backend behavior:
//   - Normal responses with server identification
//   - Health check endpoints
//   - Configurable latency (simulates slow databases, external API calls)
//   - Error responses (simulates application bugs, overload)
//   - Request echoing (for debugging proxy header manipulation)
//
// The simulator is essential for testing because it lets us reproduce
// production failure scenarios deterministically:
//   - Backend crashes → kill the process
//   - Slow responses → use /slow endpoint or --latency flag
//   - Errors → use /error endpoint
//
// Usage:
//
//	go run cmd/backend/main.go --port 9001 --name backend-1
//	go run cmd/backend/main.go --port 9002 --name backend-2 --latency 100ms
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"time"
)

func main() {
	port := flag.Int("port", 9001, "port to listen on")
	name := flag.String("name", "backend-1", "server name for identification")
	latency := flag.Duration("latency", 0, "artificial latency added to all responses")
	flag.Parse()

	mux := http.NewServeMux()

	// GET / — Returns server identity, timestamp, and hostname.
	//
	// This is the primary endpoint for verifying that the proxy is
	// correctly forwarding requests. The response includes enough
	// information to identify which backend handled the request
	// (critical for testing load balancing in Phase 2+).
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if *latency > 0 {
			time.Sleep(*latency)
		}

		hostname, _ := os.Hostname()
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"server":    *name,
			"hostname":  hostname,
			"path":      r.URL.Path,
			"method":    r.Method,
			"timestamp": time.Now().Format(time.RFC3339),
			"headers": map[string]string{
				"X-Forwarded-For": r.Header.Get("X-Forwarded-For"),
				"X-Real-IP":       r.Header.Get("X-Real-IP"),
				"Host":            r.Host,
			},
		})
	})

	// GET /health — Health check endpoint.
	//
	// Returns 200 with a simple status. In Phase 4, the load balancer
	// will poll this endpoint to determine backend health.
	//
	// We intentionally don't add artificial latency to health checks
	// because real health checks should be fast and lightweight.
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{
			"status": "healthy",
			"server": *name,
		})
	})

	// GET /slow — Simulates a slow response.
	//
	// Always takes 3 seconds regardless of the --latency flag.
	// This endpoint is specifically for testing timeout behavior
	// in the proxy. When we implement retries (Phase 6) and circuit
	// breakers (Phase 7), this endpoint helps verify they work.
	mux.HandleFunc("/slow", func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(3 * time.Second)

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{
			"status": "slow response",
			"server": *name,
			"delay":  "3s",
		})
	})

	// GET /error — Always returns 500 Internal Server Error.
	//
	// Simulates an application error. This is different from the
	// backend being down (connection refused): the backend is reachable
	// but the application is failing.
	mux.HandleFunc("/error", func(w http.ResponseWriter, r *http.Request) {
		if *latency > 0 {
			time.Sleep(*latency)
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		json.NewEncoder(w).Encode(map[string]string{
			"error":  "internal server error",
			"server": *name,
		})
	})

	// GET /echo — Echoes back the full request details.
	//
	// Useful for debugging: see exactly what headers, method, path,
	// and query parameters the backend receives after proxy manipulation.
	mux.HandleFunc("/echo", func(w http.ResponseWriter, r *http.Request) {
		if *latency > 0 {
			time.Sleep(*latency)
		}

		headers := make(map[string][]string)
		for key, values := range r.Header {
			headers[key] = values
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"method":      r.Method,
			"path":        r.URL.Path,
			"query":       r.URL.RawQuery,
			"headers":     headers,
			"remote_addr": r.RemoteAddr,
			"host":        r.Host,
			"server":      *name,
		})
	})

	addr := fmt.Sprintf(":%d", *port)
	log.Printf("[%s] starting backend server on %s", *name, addr)
	log.Printf("[%s] endpoints: / /health /slow /error /echo", *name)

	if *latency > 0 {
		log.Printf("[%s] artificial latency: %v", *name, *latency)
	}

	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatalf("[%s] server failed: %v", *name, err)
	}
}
