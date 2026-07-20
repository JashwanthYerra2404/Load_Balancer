# Load Balancer

A production-inspired HTTP/HTTPS Load Balancer built from scratch in Go.

Demonstrates Go concurrency, network programming, reverse proxy implementation, fault tolerance, performance engineering, and observability.

## Architecture

```
Client → Load Balancer (Reverse Proxy) → Backend Server
```

The load balancer accepts HTTP requests and forwards them to configured backend servers, adding proxy headers (`X-Forwarded-For`, `X-Real-IP`, `Via`) and handling errors gracefully.

## Project Structure

```
cmd/
  loadbalancer/     # Load balancer entry point
  backend/          # Backend simulator for testing
internal/
  config/           # YAML configuration parsing & validation
  proxy/            # Core reverse proxy logic
  server/           # HTTP server lifecycle management
configs/
  config.yaml       # Default configuration
```

## Quick Start

### Prerequisites

- Go 1.22+

### 1. Start a backend server

```bash
make backend
# or: go run cmd/backend/main.go --port 9001 --name backend-1
```

### 2. Start the load balancer (in a new terminal)

```bash
make run
# or: go run cmd/loadbalancer/main.go --config configs/config.yaml
```

### 3. Send a request

```bash
curl -v http://localhost:8080/
curl http://localhost:8080/health
curl http://localhost:8080/echo
curl http://localhost:8080/slow
curl http://localhost:8080/error
```

## Backend Simulator Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /` | Server identity, timestamp, forwarded headers |
| `GET /health` | Health check (always 200) |
| `GET /slow` | 3-second delayed response |
| `GET /error` | Always returns 500 |
| `GET /echo` | Echoes full request details |

### Backend Flags

```
--port      Port to listen on (default: 9001)
--name      Server name for identification (default: backend-1)
--latency   Artificial latency for all responses (e.g., 100ms)
```

## Testing

```bash
make test           # Run all unit tests
make test-verbose   # Verbose output
make test-race      # With race detector
make bench          # Run benchmarks
make cover          # Coverage report
```

## Current Phase

**Phase 1: Basic Reverse Proxy** ✅

- [x] Single backend reverse proxy
- [x] YAML configuration with validation
- [x] Custom HTTP transport (connection pooling, timeouts)
- [x] Proxy header injection (X-Forwarded-For, X-Real-IP, Via)
- [x] Structured error handling (502 Bad Gateway)
- [x] Backend simulator with multiple endpoints
- [x] Graceful shutdown (SIGINT/SIGTERM)
- [x] Unit tests and benchmarks

## Roadmap

- [ ] Phase 2: Multiple Backend Support
- [ ] Phase 3: Scheduling Algorithms (Round Robin, Least Connections, etc.)
- [ ] Phase 4: Health Checks
- [ ] Phase 5: Backend State Management
- [ ] Phase 6: Retry Mechanism
- [ ] Phase 7: Circuit Breaker
- [ ] Phase 8: Sticky Sessions
- [ ] Phase 9: Rate Limiter
- [ ] Phase 10: Graceful Shutdown
- [ ] Phase 11: Metrics (Prometheus)
- [ ] Phase 12: Structured Logging
- [ ] Phase 13: Configuration Reload
- [ ] Phase 14: HTTPS/TLS
- [ ] Phase 15: WebSocket Proxying
- [ ] Phase 16: Compression
- [ ] Phase 17: Caching
- [ ] Phase 18: Authentication
- [ ] Phase 19: Docker Compose
- [ ] Phase 20: Grafana Dashboard

## License

MIT
