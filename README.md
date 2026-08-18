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
    RetryConfig.java              # Retry settings
    CircuitBreakerConfig.java     # Circuit breaker settings
    StickySessionConfig.java      # Sticky session settings
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
    ProxyResult.java              # Backend response + retry/retriability logic
    CapturedRequest.java          # Snapshot of the inbound request
  session/
    StickySessionPool.java        # Cookie-based affinity (decorator)
  circuit/
    CircuitBreaker.java           # Per-backend circuit breaker
  ratelimit/
    RateLimiter.java              # Limiter interface
    TokenBucketRateLimiter.java   # Lock-free per-client token bucket
  util/
    ClientIp.java                 # Shared client-IP resolution
  server/
    LoadBalancerServer.java       # HTTP server with virtual threads
  health/
    HealthChecker.java            # Background health probing
configs/
  config.yaml                    # Default configuration
docs/
  phase1.md ... phase9.md        # Design documents per phase
src/test/java/com/loadbalancer/
  benchmark/                     # JMH benchmarks (run via run-benchmarks.sh)
  circuit/ config/ health/ pool/ proxy/ ratelimit/ session/ util/
run-benchmarks.sh                # Compiles and runs the JMH suite
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

## Sticky Sessions

Optional cookie-based session affinity pins a client to a specific backend so in-memory session state (shopping carts, auth tokens) survives across requests. It works with any algorithm — `StickySessionPool` is a decorator around the configured pool. If the pinned backend dies or its circuit breaker opens, the client falls back to the normal algorithm and is re-pinned to a new backend.

```yaml
sticky_session:
  enabled: false              # Opt-in
  cookie_name: "LB_BACKEND"   # Affinity cookie name
  ttl: 1h                     # Sliding max-age, refreshed every response (0 = session cookie)
  http_only: true             # XSS protection
  secure: false               # Set true for HTTPS
```

Response header format: `Set-Cookie: LB_BACKEND=backend-2; Path=/; Max-Age=3600; HttpOnly`

## Rate Limiting

Optional per-client rate limiting protects backends from abusive clients. Each client IP gets a lock-free token bucket (single-CAS admission): a sustained rate plus a bounded burst. Excess requests get `429 Too Many Requests` with a `Retry-After` header — rejected before body buffering or backend selection, at ~24ns per decision.

```yaml
rate_limit:
  enabled: false              # Opt-in
  requests_per_second: 100    # Sustained rate per client
  burst: 100                  # Max back-to-back requests after idle (default: = rate)
```

Limits are per-client — one abusive client cannot affect others. The bucket design (tokens + refill time packed into one AtomicLong) is analyzed in [docs/phase9.md](docs/phase9.md).

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
./run-benchmarks.sh # Run the JMH benchmark suite
```

## Completed Phases

- [x] Phase 1: Basic Reverse Proxy
- [x] Phase 2: Multiple Backend Support
- [x] Phase 3: Load Balancing Algorithms (5 algorithms)
- [x] Phase 4: Health Checks
- [x] Phase 5: Backend State Management
- [x] Phase 6: Retry Mechanism
- [x] Phase 7: Circuit Breaker
- [x] Phase 8: Sticky Sessions
- [x] Phase 9: Rate Limiter

## Roadmap

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
