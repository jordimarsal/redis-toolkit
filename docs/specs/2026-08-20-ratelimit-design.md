# Rate Limiter — Disseny (pass 1)

> **Repo:** `redis-toolkit` (monorepo) · **Mòdul:** `ratelimit`
> **Data:** 2026-08-20 · **Estat:** Disseny aprovat · **Idioma:** Java 25 (LTS)
> **Pass 1** = libreria de rate limiting distribuït + demo de gateway LLM.
> **Pass 2** (reservada) = `jobqueue` (cua de feines sobre Redis Streams).

---

## 1. Contexte i objectius

Enginyer amb 8+ anys en Java/Python/Bash/TypeScript que vol un projecte de portfoli que
demostrí **profunditat** (fiabilitat, observabilitat, rendiment), no CRUD. El domini triat és un
**gateway d'inferència LLM/AI**: hi són centrals tant el rate limiter (#3) com la cua de feines (#1),
i el backend és real i local (`llama-server`) sense dependre d'API keys externes.

Objectiu pass 1: una **llibreria reutilitzable i fàcil d'usar** per aplicar límits compartits entre
N instàncies via Redis, més una app demo mínima que la mostri protegint un endpoint d'inferència.

### No-objectius pass 1 (YAGNI)
- Sistema d'autenticació / autorització complet.
- Històric d'ús persistit més enllà de l'estat del limitador.
- UI o admin API.
- Segon algoritme (sliding-window queda com a extensió opcional).
- Refund de token quan el backend falla (extensió opcional).

---

## 2. Decisions clau

| Àmbit | Decisió | Motiu |
|-------|---------|-------|
| Domini demo | Gateway d'inferència LLM | Ambdues libs centrals; backend local real |
| Estructura | Monorepo amb demo compartida | Història coherent #3+#1; les libs es veuen juntes |
| Enfocament | Nucle agnòstic + adaptadors fi (hexagonal) | Portabilitat com a libreria, peu mínim, tests nits |
| Estil | Hexagonal + DTOs + mappers + patró Response + VOs rics + tell-don't-ask + TDD | Preferència explícita |
| Build tool | Maven multi-mòdul (`<release>25</release>`) | Enterprise Java, CI fàcil |
| Client Redis | Jedis (darrere el port `QuotaStore`) | Lua `eval` natiu, simple, va bé amb virtual threads |
| Algoritme | Token bucket (lazy refill) | Més realista per a control de cost LLM; més vistós |
| Política de fallada | `DEGRADED_LOCAL` per defecte | Protegeix cost sense fer 429 massiu en un blip de Redis |

---

## 3. Arquitectura i estructura de mòduls

Monorepo **Maven multi-mòdul**, hexagonal pur: `core` no depèn de res extern; `infra` i `api`
depenen cap endins.

```
redis-toolkit/                    # root / parent pom
├── ratelimit-core/               # domini + ports · 0 deps externes (només JDK)
│   ├── domain/                   # VOs rics: RateLimitSpec, QuotaKey, Decision, TokenBucketState…
│   ├── usecase/                  # RateLimiterService (aplicació) · tell-don't-ask
│   └── port/                     # sortint: QuotaStore, Clock · entrant: contractes d'aplicació
├── ratelimit-infra/              # adaptadors SORTINTS
│   ├── redis/                    # RedisQuotaStore → Jedis + scripts Lua atòmics
│   ├── memory/                   # InMemoryQuotaStore (dev/test double)
│   └── clock/SystemClock.java
├── ratelimit-api/                # adaptador ENTRANT (agnòstic de framework)
│   ├── dto/                      # DTOs a la frontera (config/petició)
│   ├── mapper/                   # DTO ⇄ domini · Decision → ApiResponse
│   ├── response/                 # patró Response: ApiResponse<T> + headers X-RateLimit-*
│   └── http/                     # filtre/handler genèric (plugable a Javalin/Spring/…)
├── gateway-demo/                 # app executable: /v1/completions protegit
│   ├── backend/InferenceBackend (port) + StubBackend + LlamaServerBackend
│   └── Main (Javalin)
└── (pass 2) jobqueue-core / -infra / -api …
```

**Regles de dependència:** `core` ← `infra`, `core` ← `api`; `gateway-demo` ← tots. Cap mòdul
extern depèn d'un altre cap endins sense passar per un port.

---

## 4. Model de domini (VOs rics) + ports

Tots els VOs viuen a `ratelimit-core/domain`, inmutables, sense getters crus cap enfora.

- **`RateLimitSpec`** — la política. Camps: `limit` (N per finestra), `refillWindow` (`Duration`),
  `burst` (màx. tokens instantanis). Comportament: `validate()` (invariants: limit ≥ 1, window > 0,
  burst ≥ 1), `describe()`. Factory `perMinute(n)` → limit=n, refillWindow=60s, burst=n.
  *Mapping al Lua:* `capacity = burst`, `refill_per_sec = limit / refillWindow.seconds`.
- **`QuotaKey`** — què es limita. Camps: valor + dimensió (`TENANT | API_KEY | IP | MODEL`).
  Comportament: `render()` → clau Redis canònica i segura; `withDimension(...)`. Encapsula la
  construcció de claus perquè ningú monti strings a mà.
- **`Decision`** — el resultat d'avaluar+consumir. Camps: `allowed`, `remaining`, `limit`,
  `retryAfter` (`Instant`), `reason` (`OK | LIMIT_EXCEEDED | CONFIG_ERROR | STORE_UNAVAILABLE`).
  Comportament: `isAllowed()`, `retryAfterSeconds()`, **`headers()`** → mapa `X-RateLimit-*`.
- **`TokenBucketState`** (VO intern) — `tokens` (`double`), `lastRefill` (`Instant`).
  Comportament: `refilled(now, ratePerSec)` → nou estat; `canConsume(n)`. Aïlla la matemàtica del refill.

### Ports (`ratelimit-core/port`)
```java
// Sortint: contracte RMW atòmic (font de veritat de l'algoritme)
public interface QuotaStore {
    Decision evaluateAndConsume(QuotaKey key, RateLimitSpec spec, Instant now);
}
public interface Clock { Instant now(); }

// Entrant / aplicació
public interface RateLimiterService {
    Decision evaluate(RateLimitRequest req);   // req = QuotaKey + RateLimitSpec
}
```
`RateLimitRequest` és un VO de core que agrupa clau + política i exposa `validate()`.

---

## 5. Adaptadors i flux de dades

### Adaptador sortint — `RedisQuotaStore` (`ratelimit-infra/redis`)
Implementa `QuotaStore` amb Jedis + **un sol script Lua atòmic** (RMW sense races entre N rèpliques).
Esquema del token bucket (lazy refill):

```lua
-- KEYS[1]=bucket · ARGV: capacity, refill_per_sec, now_ms
local cap=tonumber(ARGV[1]) local rate=tonumber(ARGV[2]) local now=tonumber(ARGV[3])
local tokens=tonumber(redis.call('hget',KEYS[1],'tokens') or cap)
local last  =tonumber(redis.call('hget',KEYS[1],'last')   or now)
tokens=math.min(cap, tokens + math.max(0,(now-last)/1000)*rate)   -- lazy refill
local allowed=0
if tokens>=1 then tokens=tokens-1 allowed=1 end
redis.call('hset',KEYS[1],'tokens',tokens,'last',now)
redis.call('pexpire',KEYS[1], <ttl>)
return {allowed, math.floor(tokens), cap}   -- retryAfter es deriva quan allowed==0
```
*El camp exacte de retorn es tanca amb el TDD; l'important és que tot passi en un `EVAL`.*

### Adaptador sortint — `InMemoryQuotaStore` (`ratelimit-infra/memory`)
Mateix contracte, single-node. És el **test double** (defineix la semàntica primer via TDD) i el mode
sense infra. `RedisQuotaStore` ha de passar la *mateixa* suite → paritat.

### Adaptador entrant — `ratelimit-api/http` (agnòstic)
1. `KeyExtractor` (estratègia plugable: per API-key / IP / tenant) → `QuotaKey`.
2. `RateLimitRegistry` resol `RateLimitSpec` per clau/patró.
3. Crida `RateLimiterService.evaluate(...)`.
4. **Mapper** `Decision → ApiResponse<T>`: permès → segueix al backend; denegat →
   `ApiResponse.rateLimited(...)` = 429 + `Retry-After` + `X-RateLimit-*`.
5. Plugable a Javalin (demo) o interceptor Spring (sub-mòdul opcional).

### Backend pluggable — `gateway-demo/backend`
- Port: **`InferenceBackend { Completion complete(CompletionRequest); }`**
- `StubBackend` (per defecte): treball simulat determinista (sleep ∝ "tokens", text cuit) → tests/CI/demo sense infra.
- `LlamaServerBackend` (opcional): HTTP al `llama-server` (`/v1/completions`, compatible OpenAI) → tokens/min reals contra model real.
- *Aquest port és exactament la costura on el #1 (jobqueue) s'enganxarà a la pass 2.*

### Flux end-to-end
```
Client → http adapter (KeyExtractor+Registry)
       → RateLimiterService → QuotaStore (Redis Lua atòmic)
       → Decision ─┬─ allowed → InferenceBackend.complete() → ApiResponse(ok, body, X-RateLimit-*)
                   └─ denied  → ApiResponse(429, Retry-After, X-RateLimit-*)
```

### Gest d'errors / política de fallada
- Redis caiguda → `FailurePolicy` configurable per spec: `FAIL_OPEN | FAIL_CLOSED | DEGRADED_LOCAL`.
  Per defecte **`DEGRADED_LOCAL`**: cau a `InMemoryQuotaStore` per no tallar tothom + mètrica/alerta.
- Backend timeout/error → 5xx amb cos d'error estructurat; el token ja consumit no es retorna (extensió opcional: refund).
- Config invàlida → `validate()` del VO → 400/500 amb missatge clar.

---

## 6. Estratègia TDD

Ordre de desenvolupament (test-first, cap endins):
1. **VOs purs** (JUnit, zero Redis): `RateLimitSpec.validate()`, `QuotaKey.render()`,
   `Decision.headers()/retryAfterSeconds()`, `TokenBucketState.refilled()/canConsume()`. Defineixen la semàntica.
2. **`InMemoryQuotaStore`** contra una suite de contracte compartida (`QuotaStoreContractTest`):
   refill amb `FakeClock` injectat (cap sleep), consume/deny, `remaining`, `retryAfter`, aïllament entre claus, burst.
3. **`RedisQuotaStore` passa la MATEIXA suite** (paritat) via Testcontainers-Redis + test de concurrència:
   N consumers simultanis comparteixen un pressupost.
4. **API adapter**: mapper `Decision→ApiResponse`, headers en 200 i 429, estratègies `KeyExtractor`.
5. **E2E al gateway-demo** amb `StubBackend`; canvi a `LlamaServerBackend` opcional.
6. **Load test** que demostra el límit global amb múltiples instàncies sobre un sol Redis.

*Clau:* com que `Clock` és un port, tot el que depèn del temps és determinista en tests unitaris — sense sleeps ni flakiness.

**Eines:** JUnit 5, AssertJ, Mockito (ports on calgui), Awaitility (async), Testcontainers (Redis),
`FakeClock` implementant `Clock`.

---

## 7. Criteris d'acceptació (pass 1 "fet")

- [ ] Lib compila i publica localment (`mvn install`): `ratelimit-core/-infra/-api`.
- [ ] Suite de contracte verda per a **InMemory i Redis** (paritat demostrada).
- [ ] **Prova de concurrència**: 100 peticions paral·leles amb limit=10 → exactament 10 admeses, 90 denegades (cap sobre-admissió). *Senyal clau.*
- [ ] **Prova multi-rèplica**: 3 instàncies del gateway sobre 1 Redis → total admès == limit (pressupost compartit globalment).
- [ ] Headers correctes en 200 i 429: `X-RateLimit-Limit/Remaining/Reset`, `Retry-After`.
- [ ] Política de fallada verificada: apagar Redis → `DEGRADED_LOCAL` segueix servint + mètrica/alerta.
- [ ] Graceful shutdown sense connexions perdudes.
- [ ] Mètriques Prometheus: `requests_total{result}`, `redis_errors`, gauge `degraded_mode`.
- [ ] Docker Compose: un comandament puja Redis (+ profile opcional `llama-server`) + demo.
- [ ] CI (GitHub Actions): build + unit + integració (Testcontainers) + checkstyle + test de concurrència.
- [ ] README: diagrama d'arquitectura, ús com a libreria (snippet), referència de config, trade-offs, resultats de benchmark.

---

## 8. Pass 2 (preview): jobqueue

Mòduls reservats al mateix monorepo: `jobqueue-core / -infra / -api`. La costura natural és el port
`InferenceBackend`: la pass 2 l'envoltarà amb una cua de feines sobre **Redis Streams**
(consumer groups, PEL, retry/backoff, prioritats, backpressure) perquè les generacions llargues no
bloquegin la resposta. El gateway demo guanyarà un camí async (`/v1/completions?async=true` o `/v1/jobs`).

---

## 9. Obertures / decisions pendents

- Camps exactes del retorn del script Lua → es tanquen durant el TDD (pas 3).
- Algoritme secundari (sliding-window counter) → opcional, fora de l'abast mínim.
- Refund de token si el backend falla → opcional.
- Eina de load test definitiva (k6 vs wrk) → es tria al pla d'implementació.
