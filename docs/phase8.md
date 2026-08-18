# Phase 8: Sticky Sessions (Cookie-Based Session Affinity)

## Overview

Add cookie-based sticky sessions so that a client's requests are consistently routed to the same backend across multiple requests. This is critical for stateful applications that store session data in-memory (e.g., shopping carts, auth tokens, WebSocket upgrade negotiations).

## Why Cookies, Not IP Hash?

We already have `IPHashPool` (Phase 3) which routes based on client IP. Cookie-based affinity solves its critical limitations:

| Problem | IP Hash | Cookie-Based |
|---------|---------|--------------|
| Clients behind NAT/CGNAT | All share one IP → same backend (overloaded) | Each client has its own cookie → distributed |
| CDN/proxy in front | CDN IP, not client IP | Cookie passes through CDN transparently |
| IPv4 → IPv6 migration | Different hash → different backend | Cookie stays the same |
| Mobile clients roaming | IP changes → different backend → session lost | Cookie preserved across network changes |
| Backend scaling (add/remove) | Hash space reshuffles → all clients redistributed | Only affected backends' clients move |

## How It Works

```
First request (no affinity cookie):
  Client ─── GET /app ──────────────────────▶ Load Balancer
                                                  │
                                                  ├─ No affinity cookie found
                                                  ├─ Use normal algorithm (round_robin, etc.)
                                                  ├─ Selected: backend-2
                                                  │
  Client ◀── 200 OK ◀──────────────────────── Load Balancer
              Set-Cookie: LB_BACKEND=backend-2; Path=/; HttpOnly

Subsequent requests (affinity cookie present):
  Client ─── GET /app ──────────────────────▶ Load Balancer
              Cookie: LB_BACKEND=backend-2
                                                  │
                                                  ├─ Found affinity cookie: backend-2
                                                  ├─ Lookup backend-2 in pool
                                                  ├─ backend-2 is alive? → route to it
                                                  │  (skip normal algorithm)
                                                  │
  Client ◀── 200 OK ◀──────────────────────── Load Balancer

Backend goes down:
  Client ─── GET /app ──────────────────────▶ Load Balancer
              Cookie: LB_BACKEND=backend-2
                                                  │
                                                  ├─ Found affinity cookie: backend-2
                                                  ├─ backend-2 is NOT alive/available
                                                  ├─ Fallback: use normal algorithm
                                                  ├─ Selected: backend-1
                                                  │
  Client ◀── 200 OK ◀──────────────────────── Load Balancer
              Set-Cookie: LB_BACKEND=backend-1; Path=/; HttpOnly
              (re-pin to new backend)
```

## Architecture: Wrapper Pattern

Sticky sessions are **algorithm-agnostic** — they work with round-robin, least-connections, weighted, or any future algorithm. This is achieved via the **decorator pattern**: `StickySessionPool` wraps any existing `BackendPool`:

```
┌─────────────────────────────────────────────────────┐
│  StickySessionPool (decorator)                      │
│                                                     │
│  next(exchange):                                    │
│    1. Parse cookie → lookup pinned backend          │
│    2. If pinned && available → return it            │
│    3. Else → delegate to inner pool (round_robin)   │
│                                                     │
│  ┌───────────────────────────────────────────────┐  │
│  │  Inner BackendPool (any algorithm)            │  │
│  │  RoundRobinPool / LeastConnectionsPool / etc  │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

The `ProxyResult.writeTo()` method injects the `Set-Cookie` header when the selected backend differs from the cookie.

## Integration Point: ProxyHandler

The cookie injection happens in `ProxyResult.writeTo()`, but the decision of *which backend* is the sticky one happens in `StickySessionPool.next()`. The ProxyHandler doesn't need to know about sticky sessions — it just calls `pool.next()` as before.

However, the response needs to know which backend was ultimately selected, so it can set the cookie. This is already available: `ProxyResult.backendName()` tells us which backend served the request.

The cookie is injected in `ProxyResult.writeTo()` — after the retry loop commits to a result.

## Proposed Implementation

### Session Package

---

#### [NEW] StickySessionPool.java

Decorator that wraps any `BackendPool`:

```java
public class StickySessionPool implements BackendPool {
    private final BackendPool delegate;       // inner algorithm
    private final StickySessionConfig config; // cookie name, TTL, etc.

    @Override
    public Backend next(HttpExchange exchange) {
        // 1. Try to find pinned backend from cookie
        String cookieValue = extractAffinityCookie(exchange, config.cookieName());
        if (cookieValue != null) {
            Backend pinned = findBackendByName(cookieValue);
            if (pinned != null && pinned.isAvailable() && !pinned.isAtCapacity()) {
                return pinned;  // sticky hit — skip algorithm
            }
            // Pinned backend unavailable — fall through to algorithm
        }

        // 2. No cookie or pinned backend down — use normal algorithm
        return delegate.next(exchange);
    }
}
```

#### [NEW] StickySessionConfig.java

```java
public record StickySessionConfig(
    boolean enabled,      // default: false (opt-in)
    String cookieName,    // default: "LB_BACKEND"
    Duration ttl,         // cookie max-age (default: 1h, 0 = session cookie)
    boolean httpOnly,     // default: true (XSS protection)
    boolean secure        // default: false (set true for HTTPS)
) { }
```

### Modified Files

#### [MODIFY] ProxyResult.java

Add sticky session cookie injection in `writeTo()`:

```java
public void writeTo(HttpExchange exchange, String cookieName, Duration ttl,
                    boolean httpOnly, boolean secure) {
    // ... existing header forwarding ...

    // Inject affinity cookie
    String cookie = buildAffinityCookie(backendName, cookieName, ttl, httpOnly, secure);
    exchange.getResponseHeaders().add("Set-Cookie", cookie);

    // ... existing status/body writing ...
}
```

#### [MODIFY] ProxyHandler.java

Pass sticky session config to `ProxyResult.writeTo()`:

```java
lastResult.writeTo(exchange, stickyConfig.cookieName(), stickyConfig.ttl(),
                   stickyConfig.httpOnly(), stickyConfig.secure());
```

#### [MODIFY] AppConfig.java

Add `StickySessionConfig stickySession` field.

#### [MODIFY] ConfigLoader.java

Parse `sticky_session` YAML section.

#### [MODIFY] LoadBalancerApplication.java

Wrap pool in `StickySessionPool` if sticky sessions are enabled.

### Config YAML

```yaml
# Sticky sessions (cookie-based session affinity).
#
# When enabled, the load balancer injects a cookie that pins a client
# to a specific backend. Subsequent requests with that cookie bypass
# the load balancing algorithm and go directly to the pinned backend.
# If the pinned backend is unavailable, a new backend is selected
# and the cookie is updated.
sticky_session:
  enabled: false              # Opt-in (default: disabled)
  cookie_name: "LB_BACKEND"  # Cookie name
  ttl: 1h                    # Cookie max-age (0 = session cookie)
  http_only: true             # HttpOnly flag (XSS protection)
  secure: false               # Secure flag (set true for HTTPS)
```

## Cookie Format

```
Set-Cookie: LB_BACKEND=backend-2; Path=/; Max-Age=3600; HttpOnly
```

| Attribute | Value | Why |
|-----------|-------|-----|
| Name | `LB_BACKEND` | Configurable, distinctive prefix |
| Value | Backend name | Human-readable, matches logs |
| Path | `/` | Covers all paths |
| Max-Age | 3600 (1h) | Configurable TTL; 0 = session cookie |
| HttpOnly | Yes | Prevents JavaScript access (XSS protection) |
| Secure | Configurable | Should be true when using HTTPS |
| SameSite | Not set | Let browser defaults apply |

**Why use backend name as cookie value?** It's human-readable in browser DevTools, matches log output, and doesn't leak internal architecture (the name is already in the `X-Backend-Name` response header).

## Concurrency

`StickySessionPool` delegates to the inner pool for thread safety. The cookie parsing is per-request (no shared state). `findBackendByName()` iterates the backend list — which is already a `CopyOnWriteArrayList` in most pool implementations.

## Test Plan

| Test | Verifies |
|------|----------|
| `firstRequestGetsAffinityCookie` | Response includes Set-Cookie with selected backend |
| `subsequentRequestRoutesToPinnedBackend` | Cookie → same backend, bypassing algorithm |
| `fallbackWhenPinnedBackendDown` | Dead pinned backend → algorithm selects new one + new cookie |
| `noStickyWhenDisabled` | Disabled config → no cookie, normal algorithm |
| `cookieParsingHandlesMalformedCookies` | Garbage cookie values → graceful fallback |
| `stickyWithRoundRobin` | Works correctly when wrapping RoundRobinPool |
| `stickyWithLeastConnections` | Works correctly when wrapping LeastConnectionsPool |
| `cookieTTLInSetCookie` | Max-Age attribute matches config |
| `httpOnlyFlagInSetCookie` | HttpOnly attribute present when configured |

## Edge Cases

1. **Cookie for unknown backend** (removed from config): Treat as no cookie → algorithm selects.
2. **Cookie for dead backend**: Fallback to algorithm → re-pin with new cookie.
3. **Multiple cookies**: First matching cookie wins.
4. **Cookie value is empty**: Treat as no cookie.
5. **Sticky + circuit breaker**: If pinned backend's circuit is OPEN, fall through to algorithm.
6. **Sticky + backend at capacity**: Pinned backend at `max_connections` → fall through to algorithm (prevents pinning a client to a saturated backend).

## JMH Benchmark Results

`StickySessionBenchmark` measures the sticky-session hot paths (JMH 1.37, `-f 1 -wi 3 -i 5`, Apple Silicon, 5 backends, 3-cookie header):

| Benchmark | Threads | Score (ops/ms) | Notes |
|-----------|---------|----------------|-------|
| `parseCookieValue` | 1 | 12,383 | Pure cookie-header parsing |
| `parseCookieValue` | 8 | 54,213 | Scales with cores (stateless parse) |
| `buildCookieHeader` | 1 | 31,689 | Set-Cookie string building |
| `stickyHit` (cookie → pinned backend) | 1 | 275 | Includes DEBUG log on hit path |
| `stickyHit` | 8 | 252 | Logging dominates, not CPU contention |
| `stickyMiss` (no cookie → delegate) | 1 | 55,268 | Null-check + delegate to round-robin |
| `stickyMiss` | 8 | 21,219 | |
| `plainPool` (baseline, no decorator) | 1 | 188,629 | Round-robin alone |
| `plainPool` | 8 | 28,475 | |

**Analysis:**

- **Cookie parsing is effectively free** — ~80ns per request at 12k ops/ms single-threaded. No regex, just a `split(";")` and name comparison.
- **The sticky-hit path (~3.6µs/op) is dominated by the `logger.debug("Sticky session hit...")` call**, which is enabled under the default (DEBUG) Logback configuration used for the benchmark run. In a production configuration (INFO+) this drops to a `isDebugEnabled` guard and the true cost is the cookie parse plus an O(n) name lookup — well under a microsecond.
- **Decorator overhead on a miss** (55k vs 189k ops/ms vs plain pool) is a null header lookup plus one virtual dispatch — ~13ns, irrelevant next to any network I/O.
- In all cases the sticky layer adds single-digit microseconds per request against a proxied HTTP round-trip measured in milliseconds — **< 0.5% overhead**.

## Test Coverage

Phase 8 adds 31 tests across five classes (full suite: 130 tests, all passing):

| Test Class | Tests | Covers |
|------------|-------|--------|
| `StickySessionPoolTest` | 13 | Pinning, dead/capacity fallback, unknown/malformed/multiple/whitespace cookies, least-connections composition, delegation of `backends()`/`addBackend()` |
| `StickySessionConfigTest` | 9 | Defaults, empty cookie name, `buildCookieHeader` variants (full, session-cookie, no HttpOnly, Secure, custom name) |
| `ProxyResultTest` (sticky) | 3 | Set-Cookie injection when enabled, none when disabled, none on error results |
| `ProxyHandlerTest` (sticky) | 4 | End-to-end cookie in response, pinned routing × 5, disabled → no cookie, dead backend → fallback + re-pin |
| `ConfigLoaderTest` (sticky) | 2 | YAML defaults applied, custom values preserved |

## Failure Scenarios

**Pinned backend dies mid-session:**

```
t=0    Client gets LB_BACKEND=backend-2, requests pinned to backend-2
t=60s  backend-2 crashes
t=61s  HealthChecker marks backend-2 down (setAlive(false))
t=61s  Next request: cookie says backend-2 → not alive → algorithm picks backend-1
t=61s  Response: Set-Cookie LB_BACKEND=backend-1 → client re-pinned, no user-visible failure
```

**Pinned backend's circuit opens:**

```
t=0    Client pinned to backend-2
t=30s  backend-2 starts failing; circuit breaker opens after threshold
t=30s  isAvailable() returns false (circuit OPEN)
t=30s  Sticky lookup skips backend-2 → algorithm selects healthy backend → re-pin
       (no request is ever sent to the open circuit)
```

**Cookie names a backend that no longer exists** (removed from config):

```
t=0    Client holds stale LB_BACKEND=backend-9 (config scaled down to 3 backends)
t=1s   findBackendByName("backend-9") → null → treated as no cookie
t=1s   Algorithm selects normally, response re-pins to a live backend
```

**Client sends garbage cookie header** (`Cookie: LB_BACKEND=`):

```
t=0    parseCookieValue returns null for empty values → treated as no cookie
t=0    Algorithm selects normally — no 400, no crash, sticky cookie re-issued
```

## Future Improvements

- **Learnable cookie** (e.g., `LB_BACKEND` set only on first response, honored-only afterwards) to reduce Set-Cookie size on every response
- **Consistent hashing on backend names** so scaling events remap fewer clients
- **SameSite attribute** configurability for CSRF-sensitive deployments
- Sticky-session metrics (hit/miss ratio per backend) for the Phase 11 metrics work
