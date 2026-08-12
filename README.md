# Load Balancer

A production-inspired HTTP Load Balancer built from scratch in Java 21+.

Demonstrates Java concurrency (virtual threads, atomics, locks), network programming, reverse proxy implementation, fault tolerance, and health checking.

## Architecture

```
                    ┌──────────────────────────┐
                    │   Load Balancer (:8080)   │
                    │                          │
 Client ──HTTP──►   │  ProxyHandler            │──►  Backend 1 (:9001)
                    │     ↓                    │──►  Backend 2 (:9002)
                    │  BackendPool (Strategy)   │──►  Backend 3 (:9003)
                    │     ↓                    │
                    │  HealthChecker (bg)       │
                    └──────────────────────────┘
```

The load balancer accepts HTTP requests and distributes them across configured backend servers using a pluggable algorithm. Health checks run in the background, automatically removing unhealthy backends and restoring them when they recover.

## Project Structure

```
src/main/java/com/loadbalancer/
  LoadBalancerApplication.java    # Composition root (main)
  BackendSimulator.java           # Backend simulator for testing
  config/
    AppConfig.java                # Top-level config record
    ServerConfig.java             # Server settings
    BackendConfig.java            # Per-backend settings
    HealthCheckConfig.java        # Health check settings
    ConfigLoader.java             # YAML parsing & validation
    ConfigValidationException.java
  pool/
    BackendPool.java              # Strategy interface
    Backend.java                  # Single upstream server
    RoundRobinPool.java           # Equal rotation
    LeastConnectionsPool.java     # Fewest active connections
    WeightedRoundRobinPool.java   # Proportional to weight (SWRR)
    IPHashPool.java               # Sticky sessions by client IP
    RandomPool.java               # Random selection
  proxy/
    ProxyHandler.java             # HTTP handler (thin orchestrator)
  server/
    LoadBalancerServer.java       # HTTP server with virtual threads
  health/
    HealthChecker.java            # Background health probing
configs/
  config.yaml                    # Default configuration
docs/
  phase1.md, phase2.md, phase3.md # Design documents
```

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.8+

### 1. Build

```bash
make build
# or: mvn clean package -DskipTests
```

### 2. Start backends (each in a separate terminal)

```bash
make backend     # backend-1 on port 9001
make backend2    # backend-2 on port 9002
make backend3    # backend-3 on port 9003
```

### 3. Start the load balancer

```bash
make run
```

### 4. Send requests

```bash
curl http://localhost:8080/
curl http://localhost:8080/health
curl http://localhost:8080/echo
curl http://localhost:8080/slow
curl http://localhost:8080/error
```

## Load Balancing Algorithms

| Algorithm | Config Value | Best For |
|-----------|-------------|----------|
| Round Robin | `round_robin` | Equal backends, predictable rotation |
| Least Connections | `least_connections` | Variable request durations |
| Weighted Round Robin | `weighted_round_robin` | Heterogeneous backend capacities |
| IP Hash | `ip_hash` | Sticky sessions, caching |
| Random | `random` | Simplicity, zero-contention |

Set in `configs/config.yaml`:

```yaml
algorithm: round_robin
```

## Health Checks

The health checker automatically probes each backend at a configurable interval:

```yaml
health_check:
  interval: 10s          # Time between probes
  timeout: 5s            # Max wait for response
  path: "/health"        # HTTP path to probe
  failure_threshold: 3   # Failures before marking dead
  success_threshold: 1   # Successes before marking alive
```

## Backend Simulator Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /` | Server identity, timestamp, forwarded headers |
| `GET /health` | Health check (always 200) |
| `GET /slow` | 3-second delayed response |
| `GET /error` | Always returns 500 |
| `GET /echo` | Echoes full request details |

## Testing

```bash
make test           # Run all unit tests
make test-verbose   # Verbose output
```

## Completed Phases

- [x] Phase 1: Basic Reverse Proxy
- [x] Phase 2: Multiple Backend Support
- [x] Phase 3: Load Balancing Algorithms (5 algorithms)
- [x] Phase 4: Health Checks

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
