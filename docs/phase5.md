# Phase 5: Retry Mechanism

## Overview

Phase 5 adds automatic request retries when a backend fails. If a request to a backend returns a connection error or server error (5xx), the proxy transparently retries with a different backend — the client sees a successful response instead of an error. This required an architectural change: **two-phase commit** for backend responses.

## Architecture

```
┌──────────┐         ┌──────────────────────────────────────┐
│          │         │   Load Balancer                       │
│  Client  │──HTTP──▶│                                      │
│          │◀────────│   ProxyHandler (retry loop)           │
└──────────┘         │     │                                │
                     │     ├─ attempt 1 ──▶ Backend A ──▶ [502]  ← retriable
                     │     │                backoff 100ms         │
                     │     ├─ attempt 2 ──▶ Backend B ──▶ [200]  ← success
                     │     │                                     │
                     │     └─ commit ────────────────────────────▶ Client
                     │                                      │
                     └──────────────────────────────────────┘
```

## The Two-Phase Commit Problem

Before Phase 5, `Backend.handleRequest()` wrote directly to the `HttpExchange`:

```
Before:  Backend → sendResponseHeaders() → write body  (COMMITTED, can't undo)
```

Once `exchange.sendResponseHeaders()` is called, the response is irrevocably committed. We can't take it back and try a different backend.

Phase 5 introduces a **two-phase approach**:

```
Phase 1 (Attempt):  Backend.forwardRequest() → ProxyResult  (in memory, reversible)
Phase 2 (Commit):   ProxyResult.writeTo(exchange)            (now committed)
```

The retry loop operates between the two phases — it can inspect the `ProxyResult` and decide whether to retry (discard the result) or commit (write to client).

## Retry Policy

| Condition | Retry? | Rationale |
|-----------|--------|-----------|
| Connection refused / timeout | ✅ Yes | Transient — backend might be restarting |
| HTTP 502 Bad Gateway | ✅ Yes | Upstream failure |
| HTTP 503 Service Unavailable | ✅ Yes | Backend overloaded |
| HTTP 504 Gateway Timeout | ✅ Yes | Backend too slow |
| HTTP 200-499 | ❌ No | Success or client error — not the backend's fault |
| HTTP 500 Internal Server Error | ❌ No | Application bug — retrying won't fix it |
| `InterruptedException` | ❌ No | Thread was interrupted — respect cancellation |

### Why 500 is Not Retriable

A 500 is an application-level error (unhandled exception, null pointer, etc.). The same request sent to the same application on a different backend will likely produce the same error. Only *gateway-level* errors (502/503/504) indicate the backend is unreachable or overloaded — these are transient and worth retrying.

## Retry Flow

```java
handle(HttpExchange exchange) {
    CapturedRequest captured = CapturedRequest.from(exchange);   // buffer body

    for (attempt = 0; attempt <= maxRetries; attempt++) {
        Backend backend = pool.next(exchange);                   // pick backend
        ProxyResult result = backend.forwardRequest(captured);   // attempt

        if (!result.isRetriable() || attempt == maxRetries)
            break;                                               // done

        backoff(attempt);                                        // wait
    }

    result.writeTo(exchange);                                    // commit
}
```

### Backend Avoidance

On retries, the handler tracks which backends have been tried:

```java
Set<String> triedBackends = new HashSet<>();
```

If `pool.next()` returns a previously-tried backend, the handler requests another. If no alternative is available (single-backend pool), it accepts the same backend — a retry to the same backend after a transient failure is still worthwhile.

## Exponential Backoff with Jitter

**Formula:** `min(initialBackoff × 2^attempt, maxBackoff) × jitter(0.75..1.25)`

| Attempt | Base | Capped | With Jitter |
|---------|------|--------|-------------|
| 0 | 100ms | 100ms | 75–125ms |
| 1 | 200ms | 200ms | 150–250ms |
| 2 | 400ms | 400ms | 300–500ms |
| 3 | 800ms | 800ms | 600–1000ms |
| 4 | 1600ms | 1000ms | 750–1250ms |

**Why jitter?** Without it, if a backend crashes and 100 concurrent requests all fail at the same time, they'd all retry at exactly the same moment — creating a thundering herd on the remaining backends. ±25% jitter spreads retries across a time window.

**Why exponential?** If the backend is genuinely down, retrying immediately just adds load. Each successive attempt waits longer, giving the backend time to recover.

## Implementation Details

### ProxyResult

An immutable `record` that captures a backend response without committing to the client:

```java
public record ProxyResult(
    int statusCode,
    Map<String, List<String>> headers,
    byte[] body,
    String backendName,
    Exception error         // non-null on connection failure
) {
    public boolean isRetriable() { ... }
    public void writeTo(HttpExchange exchange) { ... }
    public static ProxyResult connectionError(String name, Exception e) { ... }
}
```

`writeTo()` handles both successful responses (forward headers + body) and connection errors (generate 502 JSON response).

### CapturedRequest

```java
public record CapturedRequest(
    String method,
    URI requestURI,
    Map<String, List<String>> headers,  // hop-by-hop headers pre-filtered
    byte[] body,                        // null if streamed or bodyless
    InputStream bodyStream,             // null if buffered or bodyless
    InetSocketAddress remoteAddress,
    boolean retriable                   // false if non-idempotent or stream used
) { }
```

**Memory safety and Idempotency:** The capture mechanism uses a hybrid streaming/buffering model:
- **Idempotent methods** (`GET`, `HEAD`, `PUT`, `DELETE`): If the payload is small (<= 1MB), the body is buffered into a `byte[]` and `retriable=true`. If the payload is larger than 1MB, it is streamed instead (`InputStream`) and `retriable=false` to prevent OOM.
- **Non-idempotent methods** (`POST`, `PATCH`): These are *never* buffered and *never* retried (`retriable=false`), meaning their `InputStream` is always piped directly to the backend. This guarantees we don't accidentally retry state-mutating requests after a network blip.

### Backend.forwardRequest()

New method that returns `ProxyResult` instead of writing to the exchange. The existing `handleRequest(HttpExchange)` is refactored to delegate:

```java
public void handleRequest(HttpExchange exchange) throws IOException {
    CapturedRequest captured = CapturedRequest.from(exchange);
    ProxyResult result = forwardRequest(captured);
    result.writeTo(exchange);
}
```

Private helpers extracted from the original monolithic `handleRequest()`:
- `buildTargetUrl(URI)` — combines backend URL with request path/query
- `forwardRequestHeaders(Builder, Map)` — copies pre-filtered headers
- `addProxyHeaders(Builder, CapturedRequest)` — adds X-Real-IP, X-Forwarded-For, X-Backend-Name

### RetryConfig

```java
public record RetryConfig(
    int maxRetries,           // default: 2 (total attempts = 3)
    Duration initialBackoff,  // default: 100ms
    Duration maxBackoff       // default: 1s
) { }
```

## Configuration

```yaml
# Retry configuration.
retry:
  max_retries: 2          # Additional attempts after first failure (total = 3)
  initial_backoff: 100ms  # Wait before first retry
  max_backoff: 1s         # Cap on exponential backoff
```

All fields are optional — defaults are applied if omitted. Set `max_retries: 0` to disable retries entirely.

## Package Changes

| Package | Change | Details |
|---------|--------|---------|
| `com.loadbalancer.proxy` | **2 new files** | `ProxyResult`, `CapturedRequest` |
| `com.loadbalancer.proxy` | **Modified** | `ProxyHandler` — retry loop, accepts `RetryConfig` |
| `com.loadbalancer.config` | **New file** | `RetryConfig` record |
| `com.loadbalancer.config` | **Modified** | `AppConfig` — added `retry` field |
| `com.loadbalancer.config` | **Modified** | `ConfigLoader` — parses `retry` YAML section |
| `com.loadbalancer.pool` | **Modified** | `Backend` — added `forwardRequest()`, extracted helpers |
| `com.loadbalancer` | **Modified** | `LoadBalancerApplication` — passes `RetryConfig` to handler |

## Concurrency Design

| Component | Thread Safety | Notes |
|-----------|---------------|-------|
| `ProxyHandler.handle()` | Per-request thread | Retry loop is per-request, no shared state |
| `triedBackends` set | Thread-local | Created per request, never shared |
| `CapturedRequest` | Immutable record | Safe to pass across retry iterations |
| `ProxyResult` | Immutable record | Safe to inspect and discard |
| `ThreadLocalRandom` | Per-thread | No contention for jitter calculation |
| `Backend.forwardRequest()` | Thread-safe | Only touches `activeConnections` (AtomicLong) |

### Performance Characteristics (Java JMH)

We benchmarked the `CapturedRequest.from(exchange)` method using JMH to quantify the overhead of buffering or streaming the request body before passing it to the proxy logic.

```text
Benchmark                                        Mode  Cnt        Score         Error   Units
ProxyBenchmark.testCaptureGet                   thrpt    5    17967.262 ±    4171.932  ops/ms  (~55.6 ns/op)
ProxyBenchmark.testCapturePost                  thrpt    5    14592.446 ±    4107.981  ops/ms  (~68.5 ns/op)
```

**Key takeaways:**
1. **Negligible Proxy Overhead**: Creating the `CapturedRequest` record, filtering hop-by-hop headers, and determining idempotency takes **under 70 nanoseconds**.
2. **Buffer Allocation Speed**: Even when processing non-idempotent `POST` requests (which skips memory buffering and just passes the stream reference) or `GET` requests (which allocates a tiny empty array buffer), the JVM allocation speed is extremely fast and entirely garbage-collectible in the Young Generation. This means Phase 5 safely enables retry features without hindering raw proxy throughput.

## Test Coverage

| Test Class | Tests | What It Verifies |
|------------|-------|-----------------|
| `ProxyResultTest` | 9 | Retriability for 502/503/504, non-retriable 200/404/500, InterruptedException, writeTo, connection error writeTo |
| `CapturedRequestTest` | 4 | GET capture, POST body buffer, hop-by-hop header filtering, body replayability |
| `ProxyHandlerTest` | 9 | 503 no backends, forward success, dead backend, retry on connection failure, retry on 502, no retry on 200/404, retries disabled, backoff math |

Total: **22 new tests** (80 total across the project).

## Failure Scenarios

### Scenario 1: Single Backend Failure with Retry

```
t=0ms    Request arrives
t=0ms    Attempt 1 → Backend A → Connection refused
t=100ms  Backoff (100ms ± jitter)
t=100ms  Attempt 2 → Backend B → 200 OK
t=100ms  Client receives 200 OK
```

**Client impact:** ~100ms extra latency. No error visible.

### Scenario 2: 502 from Overloaded Backend

```
t=0ms    Request arrives
t=0ms    Attempt 1 → Backend A → 502 Bad Gateway
t=100ms  Backoff
t=100ms  Attempt 2 → Backend B → 502 Bad Gateway
t=300ms  Backoff (200ms ± jitter)
t=300ms  Attempt 3 → Backend C → 200 OK
t=300ms  Client receives 200 OK
```

**Client impact:** ~300ms extra latency. All 3 attempts used.

### Scenario 3: All Backends Down

```
t=0ms    Request arrives
t=0ms    Attempt 1 → Backend A → Connection refused
t=100ms  Attempt 2 → Backend B → Connection refused
t=300ms  Attempt 3 → Backend C → Connection refused
t=300ms  Client receives 502 Bad Gateway (last error)
```

**Client impact:** 502 after exhausting all retries. Health checker will eventually mark backends dead → future requests get 503 immediately (no wasted retry time).

### Scenario 4: POST Request (No Retry)

```
t=0ms    POST request arrives
t=0ms    CapturedRequest marks retriable=false (non-idempotent)
t=0ms    Attempt 1 → Backend A → Connection refused
t=0ms    Client receives 502 (no retry — non-idempotent)
```

**Design tradeoff:** Retrying `POST` requests automatically is dangerous (e.g. charging a credit card twice). The load balancer strictly enforces idempotency for retries. Additionally, payloads > 1MB (even if idempotent) are streamed to avoid OOM exceptions.

## Future Improvements

- Phase 6: Circuit breaker to prevent retries to backends with high failure rates
- Retry budget: limit total retry rate across all requests (prevent cascading retries)
- Request hedging: send to two backends simultaneously, use first response
- Retry metrics: track retry rate, success rate after retry, latency distribution
