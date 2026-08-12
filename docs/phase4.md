# Phase 4: Health Checks

## Overview

Phase 4 adds a background health checker that periodically probes each backend to determine if it's healthy. Unhealthy backends are automatically removed from the load balancing rotation and restored when they recover. This is the key to self-healing — before Phase 4, a dead backend stayed in rotation until manually removed.

## Architecture

```
┌──────────┐         ┌──────────────────────────────┐         ┌──────────────┐
│          │         │   Load Balancer               │    ┌───▶│  Backend 1   │
│  Client  │──HTTP──▶│   :8080                      │    │    │  :9001  ✅   │
│          │◀────────│                              │    │    └──────────────┘
└──────────┘         │  ┌────────────────┐          │    │    ┌──────────────┐
                     │  │  BackendPool   │──────────┼────┤───▶│  Backend 2   │
                     │  │  (Strategy)    │          │    │    │  :9002  ❌   │
                     │  └────────────────┘          │    │    └──────────────┘
                     │         ▲                    │    │    ┌──────────────┐
                     │         │ setAlive()         │    └───▶│  Backend 3   │
                     │  ┌──────┴─────────┐         │         │  :9003  ✅   │
                     │  │ HealthChecker  │─────GET──┼────────▶│  /health     │
                     │  │ (background)   │  10s     │         └──────────────┘
                     │  └────────────────┘         │
                     └──────────────────────────────┘
```

## Health Check Algorithm

The health checker uses a **threshold-based state machine** to prevent flapping. A single failed probe doesn't immediately kill a backend — it takes `failure_threshold` consecutive failures.

```
                    success × success_threshold
              ┌──────────────────────────────────┐
              │                                  │
              ▼                                  │
         ┌─────────┐                        ┌─────────┐
         │  ALIVE  │                        │  DEAD   │
         │         │──────────────────────▶ │         │
         └─────────┘                        └─────────┘
              failure × failure_threshold
```

### Per-Probe Logic

```
For each backend:
  1. Send HTTP GET to backend_url + health_path
  2. If response is 2xx:
       - Reset failure counter to 0
       - Increment success counter
       - If backend is DEAD and successes >= success_threshold:
           → Mark ALIVE, reset success counter
  3. If connection fails or non-2xx:
       - Reset success counter to 0
       - Increment failure counter
       - If backend is ALIVE and failures >= failure_threshold:
           → Mark DEAD, reset failure counter
```

### Why Thresholds?

| Without thresholds | With thresholds |
|--------------------|-----------------|
| One network blip kills a backend | Requires 3 consecutive failures to mark dead |
| Backend flaps alive/dead rapidly | Smooth transitions, no flapping |
| Clients see intermittent errors | Stable routing, masked transient issues |
| Cold backend gets traffic immediately | `success_threshold` lets backends warm up |

### Default Values

| Parameter | Default | Rationale |
|-----------|---------|-----------|
| `interval` | 10s | Fast enough to detect failures, slow enough to not overload backends |
| `timeout` | 5s | Health checks should be fast; anything longer suggests a problem |
| `path` | `/health` | Industry convention (Kubernetes, AWS ALB, etc.) |
| `failure_threshold` | 3 | Tolerates 2 transient failures (~20-30s before marking dead) |
| `success_threshold` | 1 | Backends recover quickly; use >1 for warm-up sensitive services |

## Configuration

```yaml
health_check:
  interval: 10s          # Time between health check probes
  timeout: 5s            # Max wait per health check response
  path: "/health"        # HTTP path to probe on each backend
  failure_threshold: 3   # Consecutive failures before marking dead
  success_threshold: 1   # Consecutive successes before marking alive
```

All fields are optional — defaults are applied if omitted.

## Implementation Details

### HealthChecker

The core component. Uses `ScheduledExecutorService` for periodic execution (equivalent to Go's `time.Ticker` + goroutine).

```java
// Startup: check immediately, then every interval
scheduler.scheduleAtFixedRate(
    this::checkAll,
    0,                            // initial delay
    config.interval().toMillis(),
    TimeUnit.MILLISECONDS
);
```

**Key design decisions:**

1. **Dedicated HttpClient** — Isolated from proxy traffic. A slow health check probe won't consume proxy connection pool resources. Uses `HttpClient.Redirect.NEVER` and `BodyHandlers.discarding()` to minimize overhead.

2. **Daemon thread** — The scheduler thread is a daemon (`t.setDaemon(true)`), so it doesn't prevent JVM shutdown. Equivalent to Go's detached goroutine.

3. **ConcurrentHashMap for counters** — Per-backend failure/success counters stored in `ConcurrentHashMap<String, Integer>`. Single writer (the scheduler thread) makes this sufficient — no need for `AtomicInteger`.

4. **Parallel probing** — Backends are checked concurrently within each cycle via `checkAll()`. `HttpClient.sendAsync()` fires off non-blocking HTTP requests for each backend, ensuring that a slow or unresponsive backend does not block or delay the health checks of other backends in the pool.

### HealthCheckConfig

A Java `record` with a `withDefaults()` factory method — same pattern as `ServerConfig` and `BackendConfig`.

```java
public record HealthCheckConfig(
    Duration interval,
    Duration timeout,
    String path,
    int failureThreshold,
    int successThreshold
) {}
```

### Integration with BackendPool

The health checker interacts with backends through the existing `BackendPool.backends()` method and `Backend.setAlive()` / `Backend.isAlive()` atomics. **No changes were needed to the BackendPool interface or any pool implementation** — Phase 4 operates entirely through the existing backend state API.

```
HealthChecker ──reads──▶ pool.backends()      (list of Backend instances)
              ──writes──▶ backend.setAlive()   (AtomicBoolean, lock-free)
              ──reads──▶ backend.isAlive()     (AtomicBoolean, lock-free)
```

### Application Wiring

The `LoadBalancerApplication` creates and starts the health checker after building the pool, and registers a shutdown hook:

```java
HealthChecker healthChecker = new HealthChecker(pool, config.healthCheck());
healthChecker.start();

Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    healthChecker.stop();
}, "health-checker-shutdown"));
```

## Concurrency Design

| Component | Primitive | Thread Safety |
|-----------|-----------|---------------|
| `Backend.alive` | `AtomicBoolean` | Lock-free read/write; health checker writes, request threads read |
| `HealthChecker.failureCounts` | `ConcurrentHashMap` | Single writer (scheduler), safe for concurrent reads |
| `HealthChecker.successCounts` | `ConcurrentHashMap` | Single writer (scheduler), safe for concurrent reads |
| `HealthChecker.scheduler` | `ScheduledExecutorService` | Single-threaded; no concurrent probe overlap |
| `HealthChecker.httpClient` | `HttpClient` | Thread-safe by design (immutable after build) |

### No Contention with Request Path

The critical insight: health checks and request handling share **zero mutable state that requires locking**. The only shared state is `Backend.alive`, which is an `AtomicBoolean` — a single CAS instruction. No lock, no contention, no impact on request latency.

### Performance Characteristics (Java JMH)

We benchmarked the `isAlive()` read operation on the `Backend` to prove that reading health state adds zero overhead to the proxy routing hot path.

```text
Benchmark                                        Mode  Cnt        Score         Error   Units
HealthCheckerBenchmark.testIsAliveConcurrent    thrpt    5  6591462.298 ± 2972354.603  ops/ms  (~0.15 ns/op)
HealthCheckerBenchmark.testIsAliveSeq           thrpt    5  1339956.838 ±   38859.510  ops/ms  (~0.74 ns/op)
```

**Key takeaways:**
1. **Microscopic overhead**: Reading `AtomicBoolean` scales brilliantly across threads. At ~0.15 nanoseconds per operation under heavy load, it takes literally zero measurable time away from the load balancing proxy loop.
2. **True lock-free reads**: Because `AtomicBoolean` relies on underlying CPU cache coherency (MESI protocol) and memory barriers rather than kernel-level locking, it easily sustains **6.5 billion operations per second** across 8 cores.

## Package Changes

| Package | Change | Details |
|---------|--------|---------|
| `com.loadbalancer.health` | **New package** | `HealthChecker` — background health probing |
| `com.loadbalancer.config` | **New file** | `HealthCheckConfig` record |
| `com.loadbalancer.config` | **Modified** | `AppConfig` — added `healthCheck` field |
| `com.loadbalancer.config` | **Modified** | `ConfigLoader` — parses `health_check` YAML section |
| `com.loadbalancer` | **Modified** | `LoadBalancerApplication` — starts health checker, registers shutdown hook |
| `com.loadbalancer.pool` | **Unchanged** | No pool changes — operates through existing Backend API |
| `com.loadbalancer.proxy` | **Unchanged** | No proxy changes — health checks are orthogonal to request handling |
| `com.loadbalancer.server` | **Unchanged** | No server changes — health checks are a separate lifecycle |

## Test Coverage

Tests use **real `HttpServer` instances** (not mocks) — Java 25+ restricts mocking of `com.sun.net.httpserver` classes, and real servers provide more reliable integration testing anyway.

| Test | What It Verifies |
|------|-----------------|
| `healthyBackendStaysAlive` | 200 response keeps alive=true |
| `unhealthyBackendMarkedDeadAfterThreshold` | 3 consecutive 500s → alive=false (not after 1 or 2) |
| `unreachableBackendMarkedDead` | Connection refused → alive=false after threshold |
| `deadBackendRecoversAfterSuccessThreshold` | Dead backend → 2 successes (threshold=2) → alive=true |
| `startAndStopHealthChecker` | Lifecycle: start, run 300ms, stop without hanging |

## Failure Scenarios

### Scenario 1: Backend Crashes

```
t=0s   Backend 2 crashes
t=10s  Health check #1 fails → failures=1/3
t=20s  Health check #2 fails → failures=2/3
t=30s  Health check #3 fails → failures=3/3 → Backend 2 marked DEAD
       Traffic stops routing to Backend 2
```

**Detection latency:** ~30s (3 × 10s interval). Acceptable for most workloads; reduce `interval` for faster detection.

### Scenario 2: Backend Recovers

```
t=0s   Backend 2 comes back online
t=10s  Health check #1 succeeds → successes=1/1 → Backend 2 marked ALIVE
       Traffic resumes to Backend 2
```

**Recovery latency:** ~10s (1 × 10s interval with `success_threshold=1`).

### Scenario 3: Network Blip

```
t=0s   Network blip causes probe failure → failures=1/3
t=10s  Network recovers, probe succeeds → failures reset to 0
       Backend stays ALIVE — no impact on traffic
```

The threshold absorbs transient failures without affecting routing.

## Future Improvements

- Phase 5: Retry mechanism to automatically retry failed requests on a different backend
- Configurable health check HTTP method (HEAD vs GET)
- Custom health response validation (check response body, not just status code)
- Exponential backoff on consecutive failures (reduce probe frequency for persistently dead backends)
- Health check metrics (success/failure rates, latency percentiles per backend)
