# Spec Driven Development

This document defines the spec-driven development process. Every feature follows a
structured path from requirements through design to implementation, with human approval
gates at each transition.

---

## Directory Structure

```
specs/
  <feature-name>/
    requirements.md   — What the system must do (EARS-formatted requirements)
    design.md         — How the system will satisfy the requirements
    tasks.md          — Ordered implementation steps
```

Each feature lives in its own directory under `specs/`. The directory name becomes the
canonical feature identifier used in traceability, status tracking, and cross-references.

---

## Feature States

| State         | Meaning                                                  |
|---------------|----------------------------------------------------------|
| `pending`     | Feature directory exists but no spec has been written.   |
| `spec_ready`  | `requirements.md` and `design.md` are complete and approved. |
| `in_progress` | Implementation has started; `tasks.md` is being worked.  |
| `done`        | All tasks complete, tests pass, traceability verified.   |
| `blocked`     | Work cannot proceed; reason must be documented in the feature directory. |

State transitions:

```
pending --> spec_ready --> in_progress --> done
                ^              |
                |              v
                +---------- blocked
```

A feature may move from `blocked` back to `in_progress` once the blocker is resolved.
A feature may **not** move from `spec_ready` back to `pending`; instead, revise the spec
in place and re-request approval.

---

## Human Approval Gates

Before a feature transitions to the next state, a human reviewer must explicitly approve
the change. There are two approval gates:

1. **Spec Gate** (`pending` -> `spec_ready`): A human reviews `requirements.md` and
   `design.md` for completeness, correctness, and adherence to the EARS rules below.
   The reviewer confirms that every requirement is verifiable and that the design covers
   all requirements.

2. **Completion Gate** (`in_progress` -> `done`): A human reviews the implementation,
   verifies that all tests pass, confirms traceability (every R\<n\> maps to a test and
   implementation), and ensures `./init.sh` finishes with `[OK]`.

No automated tooling may bypass these gates. The reviewer leaves a written approval
record in the feature directory (e.g., a commit message or comment referencing the
feature name and gate).

---

## EARS Notation Reference

All functional requirements must be written using Easy Approach to Requirements Syntax
(EARS). The table below lists the five EARS patterns.

| Pattern        | Template                                                        | Use When                                         |
|----------------|-----------------------------------------------------------------|--------------------------------------------------|
| **Ubiquitous** | The \<system\> shall \<action\>.                               | The behavior applies at all times.               |
| **Event-driven** | When \<trigger\>, the \<system\> shall \<action\>.            | The behavior is triggered by a specific event.   |
| **State-driven** | While \<state\>, the \<system\> shall \<action\>.             | The behavior applies during a specific state.    |
| **Optional**   | Where \<feature\>, the \<system\> shall \<action\>.           | The behavior applies only when a feature is active. |
| **Unwanted**   | The \<system\> shall not \<action\>.                           | The behavior describes something that must never happen. |

### EARS Hard Rules

1. **Stable IDs.** Every requirement receives a stable identifier: `R1`, `R2`, `R3`, and
   so on. IDs never change once assigned. If a requirement is removed, its ID is
   retired (not reused).

2. **Each requirement is verifiable by test.** For every R\<n\>, there must exist at
   least one test that can objectively confirm the requirement is satisfied. If you
   cannot write a test for it, it is not a valid requirement.

3. **One SHALL per requirement.** Each R\<n\> contains exactly one `shall` or
   `shall not`. Do not combine multiple obligations into a single requirement. Split
   them into separate R\<n\> entries.

4. **Only SHALL and SHALL NOT.** Use `shall` for mandatory behavior and `shall not`
   for prohibited behavior. Do not use `should`, `may`, `will`, `must`, or `can` in
   requirement text.

---

## requirements.md

This file contains the authoritative list of functional requirements for the feature.
It must follow the EARS rules above. Example structure:

```markdown
# Requirements: <feature-name>

## R1
The system shall reject login attempts after 5 consecutive failures.

## R2
When the user submits a valid credentials pair, the system shall issue an authentication token.
```

---

## design.md

The design document captures **how** the system will satisfy the requirements. It must
include the following sections:

- **Files to create or modify**: Full paths of every source file that will change, with a
  one-line description of the change per file.
- **Public signatures**: All new or changed function/method signatures, including
  parameter types and return types.
- **Exceptions and error cases**: Every error condition the implementation must handle,
  mapped to the requirement(s) that trigger it.
- **Discarded alternatives**: At least one alternative approach that was considered and
  rejected, with a brief explanation of why.

The design does not need to contain full implementation code. It must contain enough
detail that a reviewer can confirm the approach is sound and that all requirements are
covered.

---

## tasks.md

The task list breaks the design into discrete, ordered implementation steps. Format:

```markdown
# Tasks: <feature-name>

- [ ] T1: Create the authentication module skeleton
      depends_on: (none)
      refs: R1, R2

- [ ] T2: Implement failure counter logic
      depends_on: T1
      refs: R1

- [ ] T3: Implement token issuance
      depends_on: T1
      refs: R2

- [ ] T4: Write unit tests for failure counter
      depends_on: T2
      refs: R1

- [ ] T5: Write unit tests for token issuance
      depends_on: T3
      refs: R2
```

Rules:

- Each task is a single discrete step that can be completed and verified independently.
- `depends_on` lists the task IDs that must be completed first. Use `(none)` for tasks
  with no dependencies.
- `refs` lists the requirement IDs (R\<n\>) that this task contributes to satisfying.
  Every R\<n\> must be referenced by at least one task.

---

## Traceability

Traceability is mandatory. The rule is:

> **Every R\<n\> must map to exactly one test and at least one implementation artifact.**

During implementation, the implementer creates a progress document in the feature
directory:

```
specs/<feature-name>/progress/impl_<name>.md
```

Where `<name>` identifies the implementer or work session. This document must contain a
traceability table:

```markdown
| Requirement | Test(s)        | Implementation file(s) | Status |
|-------------|----------------|------------------------|--------|
| R1          | auth_test.R1   | src/auth/failure.py    | done   |
| R2          | auth_test.R2   | src/auth/token.py      | done   |
```

A feature may not transition to `done` until every row in this table has status `done`
and the completion gate reviewer has confirmed the mapping is accurate.
