# Architecture

This document defines the architectural quality standards for the project. It describes
the principles that guide all design decisions, the data flow patterns that the system
follows, and the boundaries that must never be crossed. Every contributor is expected to
understand and follow these standards. When in doubt, refer back to this document.

---

## Principles

- **Hexagonal / ports & adapters.** The domain core (`ratelimit-core`) has zero
  infrastructure dependencies. All arrows point inward: infra/api/gateway depend on core,
  never the reverse. Core depends only on the JDK.
- **Tell-don't-ask.** Value objects encapsulate their own behavior (validation, refill,
  rendering, header building). Logic is not reconstructed from bare getters outside the VO.
- **Immutability by default.** Data types are immutable (records or final fields); a new
  value is returned instead of mutating state.
- **TDD + traceability.** Every requirement `R<n>` maps to at least one test before code is
  accepted. No feature is `done` until its traceability table is complete and green.
- **Contract parity.** `InMemoryQuotaStore` and `RedisQuotaStore` satisfy the *same*
  `QuotaStore` contract suite; behavioral differences between them are bugs.
- **Atomicity under concurrency.** A rate-limit decision is computed atomically inside a
  single Redis `EVAL` (Lua) so it cannot race between replicas.
- **Pluggable backends.** The gateway demo runs out-of-the-box with a deterministic
  `StubBackend`; a real `LlamaServerBackend` is optional and swappable behind one port.
- **Explicit failure policy.** Default is `DEGRADED_LOCAL`: on store failure fall back to
  the local store and emit a metric/alert rather than failing closed silently.

---

## Module Map

Maven multi-module reactor. Base package: `net.jordimp.redistoolkit`.

| Module            | Package root                                   | Contains                                                        | Depends on   |
|-------------------|------------------------------------------------|-----------------------------------------------------------------|--------------|
| `ratelimit-core`  | `...toolkit.ratelimit.{domain,port,usecase}`   | VOs, ports (`Clock`, `QuotaStore`), `RateLimiterService`        | JDK only     |
| `ratelimit-infra` | `...toolkit.ratelimit.infra.*`                 | `RedisQuotaStore` (Jedis+Lua), `InMemoryQuotaStore`, resilience wrapper | core       |
| `ratelimit-api`   | `...toolkit.ratelimit.api.*`                   | DTOs, `KeyExtractor`, `RateLimitRegistry`, mapper, `ApiResponse` | core (+infra)|
| `gateway-demo`    | `...toolkit.gateway.*`                         | Javalin HTTP entrypoint, `InferenceBackend` + `Stub`/`LlamaServer` | api (+infra) |

Reserved for pass 2 (not created until their feature is `spec_ready`):
`jobqueue-core`, `jobqueue-infra`, `jobqueue-api`.

Rule: `src/` must only contain the modules above. Any new module requires an update to this
table first.

Root-level delivery artifacts (not Maven modules): `Dockerfile`, `docker-compose.yml`,
`.github/workflows/ci.yml`, `scripts/smoke-compose.sh`, `checkstyle.xml`, `README.md`.
Usage and benchmark reproduction instructions live in the README.

---

## Data Flow

```
HTTP request
   |
   v
[gateway-demo]  POST /v1/completions (guarded) · GET /metrics (Prometheus text)
   |  builds QuotaKey via KeyExtractor(dimension, value)
   |  looks up RateLimitSpec from RateLimitRegistry
   v
[api adapter]  RateLimitRequest -> RateLimiterService.evaluate(key, spec)
   |
   v
[core usecase] RateLimiterService  --injects-->  Clock.now()
   |
   v
[core port]    QuotaStore.evaluateAndConsume(key, spec, now)
   |
   v
[infra wiring]  Main.createStoreWiring(REDIS_HOST):
   no REDIS_HOST -> InMemoryQuotaStore directly
   REDIS_HOST    -> ResilientQuotaStore(policy=DEGRADED_LOCAL)
                      primary  = RedisQuotaStore (Jedis + atomic Lua token bucket)
                      fallback = InMemoryQuotaStore (local budget while degraded;
                                   ratelimit_store_failures_total + ratelimit_degraded)
   |
   v
         Decision VO (allowed, remaining, limit, retryAfter, reason)
   |
   v
[api mapper]  Decision -> ApiResponse<T> + headers
              X-RateLimit-Limit / -Remaining / -Reset ; Retry-After on 429
   |
   v
HTTP response — allowed: body from InferenceBackend (StubBackend default,
             LlamaServerBackend optional via BACKEND/LLM_BASE_URL); denied: 429
```

Time is always obtained through the injected `Clock` so unit tests are deterministic.

---

## Key Decisions & Discarded Alternatives

- **Token bucket over sliding window.** Token bucket gives smooth burst handling and a
  trivially atomic single-key update; sliding window was rejected for pass 1 as it needs
  more state and complicates the atomic script. (Sliding window may be added later.)
- **Atomic Lua `EVAL` over app-level locking.** A read-modify-write across the network is
  racy under concurrency/replicas; a single server-side script removes the race. Rejected:
  client-side lock (`SET NX`) because it adds latency and failure modes.
- **Jedis over Lettuce.** Synchronous, simple API that maps cleanly onto an atomic call;
  Lettuce's async model buys nothing here. (Both satisfy the port; Jedis chosen for clarity.)
- **Maven multi-module over a single module.** Enforces dependency direction at build time
  (core cannot import infra). Single module rejected because it would allow core to leak
  infrastructure imports.

---

## Do Not

- Do not let `ratelimit-core` depend on Redis, HTTP frameworks, or any adapter.
- Do not drive domain logic from raw getters; keep behavior inside the value objects.
- Do not perform quota read-modify-write outside an atomic operation.
- Do not use `should`, `may`, `will`, `must`, or `can` in requirements text; one `shall` per
  requirement.
- Do not leave debug prints, commented-out code, or context-free TODOs.
- Do not mix changes from multiple features in a single session.
