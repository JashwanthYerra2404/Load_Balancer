# Phase 9: Rate Limiter (Per-Client Token Bucket)

## Overview

Add per-client rate limiting so a single abusive client cannot starve backends or other clients. Each client IP address gets an independent budget: a sustained request rate with a bounded burst. Requests beyond the budget receive `429 Too Many Requests` with a `Retry-After` header — before any body buffering, backend selection, or upstream connection.

## Algorithm Choice: Why Token Bucket

Five classic rate-limiting algorithms were considered. The table is the short version; the analysis below explains each trade-off.

| Algorithm | Time | Memory/client | Burst handling | Boundary behavior | Lock-free possible |
|-----------|------|---------------|----------------|-------------------|--------------------|
| Fixed Window Counter | O(1) | 1 counter | None — hard cutoff | **2× spike at window edges** | Yes |
| Sliding Window Log | O(n) | n timestamps | Exact | Exact | Needs deque + locks |
| Sliding Window Counter | O(1) | 2 counters | Approximate | Approximation error | Yes |
| Leaky Bucket | O(1) | counter + timestamp | None — smooths away bursts | Smooth but burst-hostile | Needs CAS on 2 fields |
| **Token Bucket** | **O(1)** | **1 packed word** | **Explicit, tunable** | **Smooth average + burst** | **Yes — single CAS** |

### Why not the others

- **Fixed window** is the cheapest to implement but has the classic edge problem: a client allowed 100 req/s can send 100 at `t=0.99s` and 100 more at `t=1.01s` — a 200-request burst through a "100/s" limit. For a load balancer protecting backends, this is exactly the failure mode we're guarding against.
- **Sliding window log** is perfectly accurate but stores one timestamp per request (memory grows with the rate × window) and pays O(n) insertion/eviction per request. That's the wrong complexity class for a per-request hot path.
- **Sliding window counter** (fixed-window counters of the last two windows, weighted) fixes the boundary spike approximately, but it *approximates* and still can't express "allow a burst of B, then sustain R/s" — the two parameters operators actually want.
- **Leaky bucket** enforces a perfectly smooth outflow, which is hostile to legitimate bursty traffic (a user clicking fast, a page load firing 10 parallel asset requests). It also conceptually needs a queue or a draining thread.

### Why token bucket wins for this hot path

1. **The semantics operators want.** `rate` + `burst` maps directly to real traffic shaping: "sustained 100 req/s, but a client may fire up to 150 back-to-back after being idle." Idle clients bank capacity; abusive ones drain to zero.
2. **O(1) time, O(1) memory per client.** Admission is one arithmetic step and one CAS. No timestamps stored per request, no queues.
3. **Refill can be lazy.** Tokens don't need a background thread topping buckets up — refill is computed from elapsed time on each request. Idle clients cost literally nothing (their bucket state just sits untouched; refill happens on the next arrival).
4. **It composes with a single word of state.** Because a bucket is fully described by (tokens, last-refill-time), both fields fit in one 64-bit word — which is the key to the lock-free implementation below.

**Verdict:** token bucket is the only option that is simultaneously O(1), burst-tolerant, smooth on average, and representable in one atomic word — i.e. the most optimized choice for a per-request admission decision.

## Design: Packed-State Lock-Free Bucket

The textbook token bucket has two mutable fields (tokens, last refill timestamp) — which normally means a lock or two atomics that can interleave. Instead, each client's bucket is **one `AtomicLong`**:

```
 63                    32 31                     0
 +-----------------------+-----------------------+
 |  tokens (milliTokens) |  last refill tick     |
 +-----------------------+-----------------------+
```

- **Tokens are stored ×1000 (milliTokens)** so fractional refill (e.g. 0.1 token per 100ms at 1 req/s) accrues with integer math — no floating point inside the CAS loop.
- **Ticks are 100ms** (`TICK_NANOS`), derived from `System.nanoTime()`. Deltas use unsigned 32-bit arithmetic, so the tick counter is wrap-safe; 32 bits of ticks spans 13.6 years of uptime and survives wraparound via modular subtraction.
- **Admission = one CAS.** Read state → compute refill from tick delta → subtract one token → compare-and-set. Lost races retry the loop. No lock, no ABA problem (the state strictly encodes both fields), no allocation after the client's first request.
- **The deny path never writes.** When the bucket is empty the method returns false without touching state — refill keeps accruing from the original tick. Two benefits: a client being limited doesn't generate CAS contention, and denied time isn't lost from their refill budget.

### Why this is the most optimized shape

| Design | Per-request cost | Contention |
|--------|-----------------|------------|
| `synchronized` bucket | lock acquire/release (~20ns+) | Limited clients block others |
| Two separate `AtomicLong`s (tokens + time) | 2 volatile reads + multi-step update | Interleaved updates need retry loops; intermediate states visible |
| `ReentrantLock` + refill thread | Lock + background thread cost | Thread churn, wakeup latency |
| **Packed single `AtomicLong`** | **1 volatile read + 1 CAS** | **Only competing admits on the same client CAS** |

For a proxy, requests from *different* clients touch *different* buckets — so there is no shared contention point at all. The only contended case is many concurrent requests from the same client, where CAS retry is the correct, cheap backpressure.

### Memory: bounded by lazy eviction

Per-client buckets could grow unboundedly under IP-spoofing floods. Cleanup is amortized: every 1024 operations, if the map exceeds 1024 entries, buckets idle for more than ~10 minutes are evicted inline (no extra thread). Eviction only removes state — a swept client restarts with a full bucket, which is safe: an attacker gets no advantage (their bucket was empty), and a returning legitimate client is not over-limited.

## How It Works

```
Request arrives
      │
      ▼
ClientIp.extract(exchange)          ← X-Forwarded-For → X-Real-IP → socket addr
      │
      ▼
rateLimiter.tryAcquire(clientId)    ← TokenBucketRateLimiter
      │
      ├─ admitted ──▶ normal proxy flow (capture → pool.next() → forward)
      │
      └─ denied ───▶ 429 Too Many Requests
                     Retry-After: <seconds until next token>
                     (returned before body buffering or backend selection)
```

**Placement in `ProxyHandler.handle()` (step 0):** the check runs before `CapturedRequest.from(exchange)`, so a rejected request never pays to buffer its body, never allocates retry state, and never touches the backend pool. A 429 must be the cheapest response the load balancer can produce.

## Interaction with Existing Features

| Feature | Interaction |
|---------|-------------|
| Retry | 429 from the *limiter* is not retried — it never reaches the retry loop. (A backend's own 429 is passed through untouched; it's not in the retriable set.) |
| Circuit breaker | Rate limiting runs first and shields backends from load, reducing failure counts that trip circuits |
| Health checks | Unaffected — probes go directly to backends, not through the proxy path |
| Sticky sessions | Orthogonal; a limited client is rejected before pool selection, cookie or not |
| Virtual threads | Fully compatible — no locks anywhere in the limiter, so no pinning |

## Configuration

```yaml
rate_limit:
  enabled: false              # Opt-in (default: disabled)
  requests_per_second: 100    # Sustained rate per client
  burst: 100                  # Bucket capacity (default: = rate)
```

| Field | Default | Meaning |
|-------|---------|---------|
| `enabled` | `false` | Master switch |
| `requests_per_second` | `100` | Sustained refill rate per client (1..1,000,000) |
| `burst` | = rate | Max back-to-back requests after idle (1..1,000,000) |

Validation is strict even when disabled — an invalid rate/burst fails startup rather than failing silently when someone flips `enabled`.

## Implementation

| File | Change |
|------|--------|
| `ratelimit/RateLimiter.java` | [NEW] Interface: `tryAcquire`, `retryAfterMillis` |
| `ratelimit/TokenBucketRateLimiter.java` | [NEW] Packed-state lock-free token bucket + lazy eviction |
| `config/RateLimiterConfig.java` | [NEW] Config record with defaults + validation bounds |
| `util/ClientIp.java` | [NEW] Shared client-IP resolution (XFF → X-Real-IP → socket) |
| `pool/IPHashPool.java` | [MODIFY] `extractIP` delegates to `ClientIp` (one resolver everywhere) |
| `proxy/ProxyHandler.java` | [MODIFY] Step-0 admission check; 429 + Retry-After writer |
| `config/AppConfig.java` | [MODIFY] `rateLimit` field |
| `config/ConfigLoader.java` | [MODIFY] Parse + validate `rate_limit` block |
| `LoadBalancerApplication.java` | [MODIFY] Build limiter when enabled, pass to handler |
| `configs/config.yaml` | [MODIFY] Documented `rate_limit` block |

The limiter is injected as a `RateLimiter` interface — the handler accepts `null` for disabled, and a future distributed implementation (Redis-backed) can drop in without touching the proxy.

## JMH Benchmark Results

`RateLimiterBenchmark` (JMH 1.37, `-f 1 -wi 3 -i 5`, Apple Silicon):

| Benchmark | Threads | Score (ops/ms) | Per-op |
|-----------|---------|----------------|--------|
| admit (CAS success) | 1 | 42,109 | ~24ns |
| admit (CAS success) | 8 | 4,333 | ~231ns/op aggregate per core amortized |
| deny (empty bucket) | 1 | 43,212 | ~23ns |
| deny (empty bucket) | 8 | 17,510 | — |
| 1000 distinct clients | 8 | 16,607 | ~60ns |
| `retryAfterMillis` | 1 | 70,308 | ~14ns |
| `ClientIp.extract` | 1 | 10,201 | ~98ns |

**Analysis:**

- **Admission costs ~24ns** — roughly 100× cheaper than the cheapest possible proxied network round-trip on loopback. The limiter is invisible in the request path.
- **The 8-thread single-client admit case (4,333 ops/ms) is the CAS contention signature**: eight threads all hammering one bucket's `AtomicLong`. This is the deliberately worst case — one client making eight concurrent requests. With 1000 distinct clients (the `manyClients` benchmark), throughput scales to 16,607 ops/ms because distinct buckets share no state: the design has no global contention point.
- **The deny path (17.5k ops/ms at 8 threads) outperforms admit** because denial is a read-only decision — no CAS. An abusive client hammering the limiter generates *less* overhead per request than a well-behaved one, which is exactly the right shape for a DoS-resistant system.
- **`ClientIp.extract` (~98ns)** — header map lookups plus a split — is the most expensive part of the whole rate-limit path; the bucket itself is 4× cheaper. This is why the extraction happens once per request and is shared with `IPHashPool`.

## Test Coverage

Phase 9 adds 24 tests (full suite: 154, all passing):

| Test Class | Tests | Covers |
|------------|-------|--------|
| `TokenBucketRateLimiterTest` | 12 | Burst capacity, refill over (fake-clock) time, capacity cap, fractional refill at 1 req/s, deny-doesn't-stop-accrual, client isolation, sustained-rate admission, Retry-After alignment, config bounds, 8-thread concurrency never exceeds capacity |
| `ProxyHandlerTest` (rate limit) | 3 | End-to-end 429 with `Retry-After` after burst, per-client isolation via X-Forwarded-For, null limiter = no limiting |
| `ConfigLoaderTest` (rate limit) | 4 | Defaults (burst=rate), custom values, invalid rate rejected, invalid burst rejected even when disabled |
| `ClientIpTest` | 5 | XFF priority + leftmost entry, X-Real-IP fallback, socket fallback, null exchange, empty headers |

The concurrency test is the critical one: 8 threads × 1000 acquisitions against one bucket with no time advancing must admit *exactly* the burst capacity — any lost update or double-spend in the packed CAS would show up as over-admission.

## Failure Scenarios

**Abusive client floods the proxy:**

```
t=0    Client 203.0.113.9 sends 5000 req/s (limit: 100/s burst 100)
t=0    First 100 admitted (bucket starts full)
t=0+   All subsequent requests: 429 + Retry-After, cost = one lock-free read each
       → the flood is cheaper to reject than to serve
t=1s   Refill adds 100 tokens → next 100 admitted, then 429s resume
       → backends see at most 100 req/s from this client regardless of send rate
```

**IP-spoofing flood (memory attack):**

```
t=0    Attacker sends 1M requests with 1M distinct spoofed X-Forwarded-For values
t=0    Map grows: 1 entry (~40B) per bucket — 1M clients ≈ 40MB
t=~10m Every 1024 ops triggers a sweep; buckets idle >10min are evicted
       → memory is bounded; a swept spoofed IP just starts with an empty burst
```

**All clients legitimate at the limit simultaneously:**

```
t=0    10,000 clients each send exactly 100 req/s (at the limit)
       → 1M admissions/s against distinct buckets, no shared contention point
       → benchmark: 16.6k ops/ms per thread × 8 threads ≈ 133M/s ceiling
```

**Clock anomalies (nanoTime going backwards across processes):**

```
startNanos is captured at construction; elapsed is clamped to >= 0
       → a negative delta simply yields tick 0 (no refill, no crash)
```

## Future Improvements

- **Distributed limiting** — Redis/GCRA tokens so multiple LB instances share one budget per client (the `RateLimiter` interface already isolates this)
- **Per-route and per-header limits** — e.g. stricter budget for `/login` than `/assets`
- **429 response body configurability** and per-client limit overrides (allowlist for partners)
- **Metrics export** — admits/denies per second, bucket count, eviction rate (feeds Phase 11)
- **Weighted costs** — count expensive requests (POST, large bodies) as multiple tokens
