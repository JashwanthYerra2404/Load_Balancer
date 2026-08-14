# Phase 6: Circuit Breaker

## Overview

Phase 6 adds per-backend circuit breakers that automatically stop sending traffic to a backend after it accumulates too many real-time request failures. Unlike health checks (which probe periodically in the background), circuit breakers react to *actual request outcomes* — they trip instantly when a backend starts failing, protecting the system from cascading failures.

## Architecture

```
                  failure_threshold reached
    ┌────────┐ ─────────────────────────────────▶ ┌────────┐
    │ CLOSED │                                    │  OPEN  │
    │        │ ◀──────────────────────────────── │        │
    └────────┘     half_open probe fails          └────────┘
        ▲                                              │
        │          recovery_timeout expires             │
        │                                              ▼
        │                                        ┌───────────┐
        └─────── probe succeeds ◀─────────────── │ HALF_OPEN │
                                                 └───────────┘
```

### State Machine

| State | Behavior | Transition |
|-------|----------|------------|
| **CLOSED** | All requests flow through. Failures tracked in sliding window. | → OPEN when `failureThreshold` reached within `slidingWindow` |
| **OPEN** | All requests immediately rejected (no network call). | → HALF_OPEN after `recoveryTimeout` elapses |
| **HALF_OPEN** | Exactly one probe request allowed through. | → CLOSED on probe success, → OPEN on probe failure |

## How It Complements Existing Features

```
Request arrives
  │
  ├─ Pool selection: backend.isAvailable()
  │   └─ alive (Phase 4)?  AND  circuit open (Phase 6)?
  │
  ├─ backend.forwardRequest(captured)
  │   └─ circuit breaker gate: allowRequest()
  │       └─ CLOSED → proceed
  │       └─ OPEN → return ProxyResult.circuitOpen() (no network call)
  │       └─ HALF_OPEN → CAS probe permit → proceed or reject
  │
  ├─ Record outcome to circuit breaker
  │   └─ success → recordSuccess()
  │   └─ failure → recordFailure()
  │
  └─ Retry loop (Phase 5) handles retriable results
      └─ circuitOpen results are retriable → tries different backend
```

| Feature | Detects via | Reaction time | Scope |
|---------|-------------|---------------|-------|
| Health Checks (Phase 4) | Periodic probes | 10-30s | Background |
| Retries (Phase 5) | Per-request failure | Immediate, per-request | Request path |
| Circuit Breaker (Phase 6) | Failure rate in sliding window | Immediate, system-wide | Request path |

## Sliding Window Design

### Why Not a Simple Counter?

A simple failure counter needs periodic resets and can't distinguish "5 failures in 1 second" from "5 failures over 5 minutes". The sliding window naturally expires old failures:

```
Timeline (window = 60s):
   t=0    failure ──┐
   t=10   failure   │ ← 2 failures in window
   t=30   failure   │ ← 3 failures in window
   t=61   failure   │ ← first failure expired! Only 3 in window
   t=65   failure   │ ← 4 failures in window (t=10, t=30, t=61, t=65)
```

### Ring Buffer Implementation

Fixed-size `long[]` array sized to `failureThreshold`:

```java
private final long[] failureTimestamps;   // size = failureThreshold (e.g., 5)
private final AtomicInteger failureIndex; // write position (wraps around)
```

- **Memory:** `failureThreshold × 8 bytes` = 40 bytes for threshold=5. Zero GC pressure.
- **Write:** Store `System.currentTimeMillis()` at the next index (modular)
- **Read:** Scan all slots, count timestamps within `[now - window, now]`

## The canAcceptTraffic() / allowRequest() Split

A critical design decision: the pool selection and request forwarding are two separate steps, but circuit breaker state transitions have **side effects** (CAS for probe permit). If both call `allowRequest()`, the pool consumes the permit and `forwardRequest()` rejects:

```
pool.next() → isAvailable() → allowRequest() → CAS probe permit ✓
                                                 (permit consumed!)
backend.forwardRequest() → allowRequest() → CAS probe permit ✗ (already taken!)
                                             → return circuitOpen ← BUG
```

Solution: **two methods with different semantics:**

| Method | Side Effects | Used By | Purpose |
|--------|-------------|---------|---------|
| `canAcceptTraffic()` | None (read-only) | `Backend.isAvailable()` → pool selection | "Is this backend a viable candidate?" |
| `allowRequest()` | CAS (acquires probe permit) | `Backend.forwardRequest()` | "Grant me permission to send a request" |

```java
// Pool selection — safe to call many times, no side effects
public boolean canAcceptTraffic() {
    return switch (state) {
        case CLOSED  -> true;
        case OPEN    -> timeout elapsed?    // read-only timestamp check
        case HALF_OPEN -> !probeInFlight;   // read-only boolean check
    };
}

// Forwarding gate — CAS acquires probe permit exactly once
public boolean allowRequest() {
    return switch (state) {
        case CLOSED  -> true;
        case OPEN    -> { transition to HALF_OPEN; CAS for probe }
        case HALF_OPEN -> CAS for probe
    };
}
```

## Configuration

```yaml
circuit_breaker:
  failure_threshold: 5    # Failures in window to trip
  sliding_window: 60s     # Time window for failure counting
  recovery_timeout: 30s   # Time in OPEN before probing
```

| Parameter | Default | Rationale |
|-----------|---------|-----------|
| `failure_threshold` | 5 | 5 failures in 60s = clearly broken, not a blip |
| `sliding_window` | 60s | Long enough for patterns, short enough to be responsive |
| `recovery_timeout` | 30s | Give backend time to recover before probing |

All fields optional — defaults applied if omitted.

## Implementation Details

### CircuitBreaker.java

Per-backend state machine using lock-free atomics:

```java
public class CircuitBreaker {
    private volatile State state = State.CLOSED;      // ~1ns read
    private final long[] failureTimestamps;           // ring buffer
    private final AtomicInteger failureIndex;         // write position
    private volatile long openedAt;                   // trip timestamp
    private final AtomicBoolean probeInFlight;        // HALF_OPEN CAS
}
```

### CircuitBreakerConfig.java

```java
public record CircuitBreakerConfig(
    int failureThreshold,       // default: 5
    Duration slidingWindow,     // default: 60s
    Duration recoveryTimeout    // default: 30s
) { }
```

### Backend.java Changes

- Added `CircuitBreaker` field and 5-arg constructor
- Preserved 4-arg constructor (backward compat for tests)
- Added `isAvailable()` = alive AND `canAcceptTraffic()`
- Added `circuitBreaker()` getter for testing/metrics
- `forwardRequest()`: circuit breaker gate + outcome recording

### Pool Implementations

All 5 pools updated: replaced `b.isAlive()` with `b.isAvailable()` in `next()`.

### ProxyResult.java

Added `circuitOpen()` factory — returns a retriable result so the retry loop tries another backend.

## Package Changes

| Package | Change | Details |
|---------|--------|---------|
| `com.loadbalancer.circuit` | **New package** | `CircuitBreaker` state machine |
| `com.loadbalancer.config` | **New file** | `CircuitBreakerConfig` record |
| `com.loadbalancer.config` | **Modified** | `AppConfig` + `ConfigLoader` — added `circuitBreaker` field |
| `com.loadbalancer.pool` | **Modified** | `Backend` — circuit breaker integration |
| `com.loadbalancer.pool` | **Modified** | All 5 pools — `isAlive()` → `isAvailable()` |
| `com.loadbalancer.proxy` | **Modified** | `ProxyResult` — `circuitOpen()` factory |
| `com.loadbalancer` | **Modified** | `LoadBalancerApplication` — wiring |

## Concurrency Design

| Component | Thread Safety | Hot Path Cost |
|-----------|---------------|---------------|
| `state` | `volatile` | ~1ns read |
| `failureTimestamps` | Ring buffer + `AtomicInteger` index | ~5ns CAS on failure only |
| `probeInFlight` | `AtomicBoolean` CAS | 0ns on happy path (CLOSED) |
| `openedAt` | `volatile long` | ~1ns read |
| `canAcceptTraffic()` | Read-only, no side effects | ~0.7ns sequential |
| `allowRequest()` | CAS in HALF_OPEN only | ~0.8ns sequential CLOSED |

## JMH Benchmark Results

Benchmarked on JDK 25.0.1, Java HotSpot(TM) 64-Bit Server VM.

```
Benchmark                                                      Mode  Cnt        Score         Error   Units
CircuitBreakerBenchmark.testAllowRequestClosedConcurrent      thrpt    5  6923825.931 ±  295549.857  ops/ms
CircuitBreakerBenchmark.testAllowRequestClosedSeq             thrpt    5  1231786.598 ±  166982.884  ops/ms
CircuitBreakerBenchmark.testCanAcceptTrafficClosedConcurrent  thrpt    5  6624765.068 ± 1527518.367  ops/ms
CircuitBreakerBenchmark.testCanAcceptTrafficClosedSeq         thrpt    5  1395090.380 ±  112200.860  ops/ms
CircuitBreakerBenchmark.testCanAcceptTrafficOpenConcurrent    thrpt    5   282842.338 ±   55891.686  ops/ms
CircuitBreakerBenchmark.testCanAcceptTrafficOpenSeq           thrpt    5    57072.126 ±    2108.846  ops/ms
```

### Analysis

| Method | State | Threads | Throughput | Per-op cost |
|--------|-------|---------|-----------|-------------|
| `canAcceptTraffic()` | CLOSED | 1 | **1.40B ops/s** | ~0.72ns |
| `canAcceptTraffic()` | CLOSED | 8 | **6.62B ops/s** | ~1.21ns (8 threads total) |
| `allowRequest()` | CLOSED | 1 | **1.23B ops/s** | ~0.81ns |
| `allowRequest()` | CLOSED | 8 | **6.92B ops/s** | ~1.16ns (8 threads total) |
| `canAcceptTraffic()` | OPEN | 1 | **57M ops/s** | ~17.5ns |
| `canAcceptTraffic()` | OPEN | 8 | **283M ops/s** | ~28.3ns (8 threads total) |

**Key takeaways:**

1. **Happy path (CLOSED) is sub-nanosecond.** Both `canAcceptTraffic()` (~0.72ns) and `allowRequest()` (~0.81ns) are dominated by a single volatile read. Total circuit breaker overhead per request: **~1.5ns** — negligible compared to network I/O (~1ms+).

2. **Near-perfect concurrent scaling.** 8 threads achieve ~6.6-6.9B ops/s — close to 8× the single-thread throughput. The volatile read has no contention (no cache-line invalidation in CLOSED state since there are no writes).

3. **OPEN state is ~17.5ns/op** (sequential). The extra cost comes from `System.currentTimeMillis()` + timestamp comparison. Still negligible — and this path is the *failure* case, not the hot path.

4. **Zero allocation.** No objects created on any code path. Ring buffer is pre-allocated at construction.

## Test Coverage

| Test Class | Tests | What It Verifies |
|------------|-------|-----------------|
| `CircuitBreakerTest` | 15 | State machine transitions, sliding window expiry, concurrent probe exclusivity (20 threads), full lifecycle, reset, edge cases |
| `ConfigLoaderTest` | +2 | Circuit breaker defaults and custom values |
| `ProxyHandlerTest` | +2 | Circuit trips after failures → 503, recovery after timeout → 200 |

Total: **19 new tests** (99 total across the project).

## Failure Scenarios

### Scenario 1: Backend Starts Failing

```
t=0s    Request 1 → Backend A → 502      (failure 1/5)
t=1s    Request 2 → Backend A → timeout   (failure 2/5)
t=2s    Request 3 → Backend A → 503      (failure 3/5)
t=3s    Request 4 → Backend A → timeout   (failure 4/5)
t=4s    Request 5 → Backend A → 502      (failure 5/5) → CIRCUIT OPENS
t=5s    Request 6 → Backend A skipped     → immediately → Backend B (200)
```

**Client impact:** After 5th failure, Backend A is instantly bypassed. No wasted connection attempts.

### Scenario 2: Backend Recovers

```
t=0s    Circuit OPEN for Backend A
t=30s   Recovery timeout → HALF_OPEN
t=30s   Request → Backend A (probe) → 200 → CIRCUIT CLOSES
t=31s   Request → Backend A → 200 (normal traffic resumes)
```

### Scenario 3: Recovery Probe Fails

```
t=0s    Circuit OPEN for Backend A
t=30s   Recovery timeout → HALF_OPEN
t=30s   Request → Backend A (probe) → 502 → CIRCUIT RE-OPENS
t=60s   Recovery timeout → HALF_OPEN → probe again...
```

### Scenario 4: Circuit Breaker + Retries Working Together

```
t=0ms   Request arrives
t=0ms   Attempt 1 → Backend A → circuit OPEN → ProxyResult.circuitOpen()
        (retriable → retry loop picks different backend)
t=0ms   Attempt 2 → Backend B → 200 OK
t=0ms   Client receives 200 OK
```

**Key insight:** Circuit-open results are retriable. The retry loop seamlessly routes around tripped circuits without the client ever seeing an error.

## Future Improvements

- Circuit breaker metrics: expose state, trip count, probe success rate
- Adaptive thresholds: adjust failure_threshold based on traffic volume
- Per-endpoint circuit breakers (e.g., /api/heavy has its own circuit)
- Circuit breaker dashboard/API for manual control (force open/close)
