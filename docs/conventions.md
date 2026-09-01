# Conventions

This document defines the coding and project conventions for the project. These rules
exist to enforce extreme homogeneity across the codebase. The goal is that any developer
can open any file and immediately understand its structure, naming, and intent without
having to learn a new style.

**Default policy: no comments.** Code must be self-documenting through clear naming and
structure. Comments are permitted only when they explain *why* a non-obvious decision was
made. Comments that describe *what* the code does are prohibited; if the code cannot be
understood without comments, rewrite the code.

---

## Style Rules

- Java 25 (`maven.compiler.release=25`). Use records for immutable data where possible;
  otherwise final fields and final classes.
- One public top-level type per file.
- Immutability by default; return a new instance instead of mutating.
- Prefer explicit control flow over clever one-liners; use streams only when they read better.
- Time is always handled with `java.time.Instant`, obtained from an injected `Clock`; never
  call `System.currentTimeMillis()` inside domain logic.
- No empty catch blocks, no swallowed exceptions, no debug prints, no commented-out code.
- Builders only when a type has more than four parameters or genuinely optional parts.

---

## Naming Rules

- Base package: `net.jordimp.redistoolkit`. Sub-packages per module/layer as in the Module Map
  in `docs/architecture.md` (e.g. `...ratelimit.domain`, `...ratelimit.port`,
  `...ratelimit.infra.redis`, `...ratelimit.api.mapper`, `...gateway.backend`).
- Classes/types: `PascalCase`. Methods/fields/local vars: `camelCase`. Constants: `UPPER_SNAKE_CASE`.
- Value objects are named after the concept (`RateLimitSpec`, `QuotaKey`, `Decision`,
  `TokenBucketState`) with no suffix.
- Ports are interfaces named by role: `Clock`, `QuotaStore`. Adapters implement the port and
  carry the technology: `RedisQuotaStore`, `InMemoryQuotaStore`. The production `Clock` is an
  inline lambda in `Main`; tests inject fakes directly.
- Enums use `UPPER_SNAKE_CASE` constants (`TENANT`, `API_KEY`, `IP`, `MODEL`; `OK`,
  `LIMIT_EXCEEDED`, `CONFIG_ERROR`, `STORE_UNAVAILABLE`).
- Tests: `<UnitUnderTest>Test` in the same package under `src/test/java`; shared behavioral
  suites end in `ContractTest` (e.g. `QuotaStoreContractTest`).

---

## File Structure

- Maven multi-module; every module uses the standard layout `src/main/java` and
  `src/test/java`.
- Dependency direction is enforced by the reactor: core <- infra <- api <- gateway-demo.
- The harness references a top-level `src/` and `tests/`; in this project those map to
  `<module>/src/main/java` and `<module>/src/test/java`. Traceability tables reference these
  concrete paths.
- A new module must be added to the parent `<modules>` list *and* to the Module Map table in
  `docs/architecture.md` before code lands there.

---

## Test Rules

- JUnit 5 + AssertJ for assertions; Mockito only where an interface fake is cleaner than a
  hand-written stub; Awaitility for async; Testcontainers Redis for integration parity and
  concurrency tests.
- TDD: write the failing test for a requirement, then implement until green. Every `R<n>` is
  covered by at least one test; the mapping lives in `specs/<feature>/progress/impl_*.md`.
- Contract tests are written once against the `QuotaStore` port and executed against BOTH
  `InMemoryQuotaStore` and `RedisQuotaStore`; any divergence is a failure.
- Determinism: inject a `FakeClock`; no real sleeping or wall-clock dependence in unit tests.
- Concurrency rule that must hold: N parallel requests with `limit = L` admit exactly `L`.

---

## Error Handling

- Domain/validation errors throw unchecked exceptions (e.g. `IllegalArgumentException`) with a
  message naming the offending value.
- Adapters translate low-level failures into the domain vocabulary: a store outage surfaces as
  `Decision.reason = STORE_UNAVAILABLE`, never as a raw driver exception leaking to callers.
- `FailurePolicy` decides behavior on store failure. Default `DEGRADED_LOCAL`: fall back to the
  local store and emit a metric/alert. An alternative `FAIL_CLOSED` returns a rejection. The
  policy is explicit configuration, never implicit.
- Always either handle an exception meaningfully or propagate it with context; never drop it.
