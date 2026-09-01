# redis-toolkit

**A rate-limiting toolkit for LLM gateways.** It ships a small, dependency-free Java domain that
enforces per-client quotas with atomic token-bucket accounting, plus a runnable Javalin gateway
demo that exposes an OpenAI-style `POST /v1/completions` endpoint guarded by standard
`X-RateLimit-*` headers and a correct `429 + Retry-After`.

The goal is not another production API gateway. It is a focused study in how to keep a
rate limiter **correct under concurrency**, **pluggable across storage backends**, and
**predictable on failure** — expressed as clean hexagonal boundaries, deterministic tests, and
honest trade-off notes.

---

## Why this exists

LLM front-ends share a common hazard: inference is expensive, so unbounded traffic turns into a
denial-of-service against your own wallet and latency budget almost instantly. Most solutions are
either heavyweight frameworks you don't want inside a tight review loop, or ad-hoc counters that
race when two replicas serve the same client at once.

This project sits deliberately between those extremes. It answers three questions concretely:

1. **How do we count fairly?** A token bucket keyed per client, where each decision is computed
   atomically — never a network read-modify-write that can slip a token past the limit.
2. **What happens when the counter breaks?** On Redis failure the store degrades gracefully to a
   local fallback instead of mass-quarantining every client with a `5xx`, while loudly flagging
   the condition through metrics.
3. **Can I trust it without reading the source?** Every behaviour is pinned by a contract suite
   shared between the in-memory and Redis implementations, plus concurrency and parity tests.

If any of those matter to you, keep reading. If not, the Quickstart below gets you a working
gateway in under a minute.

---

## Quickstart

**Prerequisites:** JDK 25, Maven 3.9+. Docker is only needed for the compose demo.

### 1. Verify the environment

```bash
./init.sh                                  # harness check + full test suite (mvn test)
```

A green run means every module compiles, all tests pass, and the feature list is coherent.

### 2. Run the gateway on plain JVM

```bash
mvn -q -pl gateway-demo -am package -DskipTests
java -jar gateway-demo/target/gateway-demo-0.1.0-SNAPSHOT.jar
```

The gateway listens on <http://localhost:8080>. With no `REDIS_HOST` configured, quotas live in
memory (single instance), so this works out of the box:

```bash
curl -si -X POST localhost:8080/v1/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"stub","prompt":"hello"}'
```

Under the limit you get **HTTP 200** plus `X-RateLimit-Limit / Remaining / Reset` headers and a
completion body. Once your quota is exhausted you get **HTTP 429** plus `Retry-After`.

### 3. Run with Redis via Docker Compose

```bash
docker compose up --build      # redis + gateway on :8080
bash scripts/smoke-compose.sh  # end-to-end smoke test; exit 0 = pass
```

Optional real inference through a local llama.cpp server (expects `models/model.gguf`):

```bash
BACKEND=llama docker compose --profile llama up --build
```

---

## Usage & configuration

| Env var          | Default    | Meaning                                                        |
|------------------|------------|----------------------------------------------------------------|
| `GATEWAY_PORT`   | `8080`     | HTTP port (first CLI argument wins)                            |
| `REDIS_HOST`     | unset      | Redis backend host; unset → in-memory store                   |
| `REDIS_PORT`     | `6379`     | Redis port                                                     |
| `LIMIT_PER_MINUTE` | `60`     | Token-bucket capacity per client key                         |
| `BACKEND`        | `stub`     | Inference backend: `stub` or `llama`                          |
| `LLM_BASE_URL`   | —          | Required when `BACKEND=llama` (e.g. `http://localhost:8080/v1`) |

### Endpoints

- **`POST /v1/completions`** — the guarded route. The JSON body is validated, quota-checked, and
  then forwarded verbatim to the inference backend.
- **`GET /metrics`** — Prometheus text exposition (`ratelimit_decisions_total`,
  `ratelimit_store_failures_total`, `ratelimit_degraded`, …).

### Response headers

On every decision the mapper renders standard rate-limit headers:

```
X-RateLimit-Limit: <limit>
X-RateLimit-Remaining: <remaining>
X-RateLimit-Reset: <epoch_seconds>
Retry-After: <seconds>      # only on HTTP 429
```

### Testing the rate limit

The client key is derived from the request IP, so every call from the same host shares one budget.
With no `REDIS_HOST` configured the store is in-memory and single-instance; with `REDIS_HOST` set,
all replicas share one budget.

Under the limit you get `200` plus `X-RateLimit-Limit / Remaining / Reset`:

```bash
curl -si -X POST localhost:8080/v1/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"stub","prompt":"hello"}'
# HTTP/1.1 200 OK
# X-RateLimit-Limit: 60
# X-RateLimit-Remaining: 59
```

Exhaust the quota (default `LIMIT_PER_MINUTE=60`) to observe the transition to `429 + Retry-After`.
It stops at the first denial:

```bash
for i in $(seq 1 65); do
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST localhost:8080/v1/completions \
    -H 'Content-Type: application/json' -d "{\"model\":\"stub\",\"prompt\":\"p-$i\"}")
  [ "$code" = "429" ] && { echo "429 on request #$i"; break; }
done
# 429 Too Many Requests · X-RateLimit-Remaining: 0 · Retry-After: <seconds>
```

The budget refills automatically as the token bucket refills over time, or restart by redeploying
the gateway. The same behaviour is automated end-to-end in [`scripts/smoke-compose.sh`](scripts/smoke-compose.sh),
which asserts a `200` followed by a `429` with a valid `Retry-After`.

---

## Architecture

A hexagonal layout in a Maven multi-module reactor. **All arrows point inward**: `ratelimit-core`
depends on the JDK alone, so infrastructure can never leak into the domain.

| Module           | Role                                                                 | Depends on     |
|------------------|----------------------------------------------------------------------|----------------|
| `ratelimit-core` | Domain value objects, ports (`Clock`, `QuotaStore`), `RateLimiterService` use case | JDK only       |
| `ratelimit-infra`| `RedisQuotaStore` (Jedis + atomic Lua token bucket), `InMemoryQuotaStore`, resilience wrapper | core          |
| `ratelimit-api`  | HTTP-facing DTOs, `KeyExtractor`, `RateLimitRegistry`, decision→response mapper | core (+infra) |
| `gateway-demo`   | Javalin entrypoint, inference backends (`StubBackend`, `LlamaServerBackend`) | api (+infra)  |

**Request flow.** `KeyExtractor` builds the client key → the registry resolves the
`RateLimitSpec` → `evaluateAndConsume(key, spec, now)` runs atomically inside the store → the
mapper renders either headers or a `429`. When Redis fails, `ResilientQuotaStore` falls back to
the local store and reports it through metrics instead of dropping traffic silently.

```
HTTP request
   │  KeyExtractor(dimension, value) ──> QuotaKey
   │  RateLimitRegistry.lookup(spec)
   ▼
RateLimiterService.evaluate(key, spec)  --injects-->  Clock.now()
   ▼
QuotaStore.evaluateAndConsume(key, spec, now)
   │  no REDIS_HOST        → InMemoryQuotaStore
   │  REDIS_HOST present   → ResilientQuotaStore(DEGRADED_LOCAL)
   │                           primary   = RedisQuotaStore (Jedis + atomic Lua)
   │                           fallback  = InMemoryQuotaStore (+ failure metric/degraded flag)
   ▼
Decision(allowed, remaining, limit, retryAfter, reason)
   ▼
Mapper → ApiResponse<T> + X-RateLimit-* / Retry-After
   ▼
HTTP response — allowed: body from InferenceBackend; denied: 429
```

Time is always obtained through the injected `Clock`, so unit tests are fully deterministic. See
[`docs/architecture.md`](docs/architecture.md) for the full quality standards and discarded
alternatives.

---

## Testing & verification

The suite is organised around a **shared contract**: both stores must satisfy the same
`QuotaStore` contract test, so behavioural differences between them are treated as bugs.

| Concern                     | Where it lives                                             |
|-----------------------------|------------------------------------------------------------|
| Value-object behaviour      | domain VOs: validation, refill, header rendering           |
| Store parity                | shared `QuotaStoreContractTest` against each store          |
| Concurrency                 | 100 parallel requests admit exactly the configured limit    |
| Multi-replica accounting    | several keys sharing one budget exhaust it only once        |
| Redis integration           | Testcontainers Redis with the atomic Lua script             |
| Gateway wiring              | Javalin routes, mapper, shutdown, metrics                    |

Run everything with `./init.sh` (which invokes `mvn test`). A green run is the gate before any
feature is marked `done`.

---

## Benchmark

Sequential single-client load against `POST /v1/completions` with `LIMIT_PER_MINUTE=10000`
(in-memory store, stub backend). These are directional numbers from one laptop-class machine and
are **not** an SLA:

| n     | result                                                                 |
|-------|------------------------------------------------------------------------|
| 100   | `ok=100 failed=0 mean_ms=1.31 p95_ms=2.73 p99_ms=3.59 rps=762.4`       |
| 500   | `ok=500 failed=0 mean_ms=0.85 p95_ms=2.01 p99_ms=3.72 rps=1179.1`      |

Reproduce: start the gateway (see Quickstart), then run the built-in runner:

```bash
java -cp gateway-demo/target/gateway-demo-0.1.0-SNAPSHOT.jar \
  net.jordimp.redistoolkit.gateway.bench.BenchmarkRunner http://localhost:8080 500
# ok=500 failed=0 mean_ms=... p95_ms=... p99_ms=... rps=...
```

Requests that hit the limit count as `failed`; raise `LIMIT_PER_MINUTE` for headroom.

---

## Trade-offs & design decisions

- **Token bucket over sliding window.** Smooth burst handling and a trivially atomic single-key
  Lua update; sliding window needs more state and complicates the script.
- **Atomic Lua `EVAL` over app-level locking.** A read-modify-write across the network races under
  concurrency/replicas; a single server-side script removes the race.
- **Fail-open (`DEGRADED_LOCAL`) over fail-closed.** On store failure traffic is served from the
  local fallback and flagged via metrics rather than dropped — availability beats strict global
  accuracy in this demo's threat model.
- **Synchronous Jedis over Lettuce.** One blocking call per decision maps cleanly onto the atomic
  script; the async model buys nothing at this scale.
- **Single IP-dimension key.** `KeyExtractor` keys by client IP; API-key / tenant dimensions are
  reserved for later features.
- **Demo-grade scope.** No auth, TLS, or multi-route policies yet — but the module boundaries
  keep those additions cheap.

Full details and discarded alternatives live in
[`docs/architecture.md`](docs/architecture.md).

---

## Project conventions

This repo was built with the
[harness-standard](https://github.com/jordimarsal/harness-standard) harness: Spec Driven Development
with a human approval gate before any code, and every requirement `R<n>` backed by at least one
passing test.

---

## License

Provided as-is for demonstration and study purposes.
