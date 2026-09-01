# Verification

This document defines how to prove that work works. Every feature must pass verification
before it can be marked as done. There are no exceptions.

---

## Verification Levels

### Level 1: Unit Tests (Mandatory)

Every feature must have unit tests. Unit tests verify individual functions, methods, and
classes in isolation. They must:

- Cover every public function and method.
- Test both the happy path and every documented error case.
- Be deterministic: running the same test twice must produce the same result.
- Be fast: the full unit test suite must complete in under 60 seconds.

### Level 2: Integration Tests (Mandatory for UI/API)

Any feature that introduces or modifies a user interface endpoint, REST API endpoint,
or inter-service boundary must include integration tests. These tests must:

- Exercise the full request/response cycle from the external interface inward.
- Use realistic inputs, including edge cases and malformed data.
- Verify both correct behavior and correct error responses.
- Run against a test environment that mirrors production configuration as closely as
  possible.

Features that are purely internal (utilities, data transformations, algorithms) do not
require integration tests but still require unit tests.

### Level 3: Smoke Tests (Optional)

Smoke tests are lightweight checks that confirm the system starts and responds to basic
requests. They are optional but recommended for:

- Services that have historically had startup regressions.
- Features that change dependency injection, configuration loading, or initialization
  order.

Smoke tests must not replace unit or integration tests. They are a supplement, not a
substitute.

### Level 4: Requirement Traceability (Mandatory for All Features)

Every requirement R\<n\> in the spec must map to at least one test that verifies it.
This mapping is documented in the progress file (see `specs.md` - Traceability section).
A feature cannot be marked as done until every requirement has a passing test that
proves it works.

---

## Anti-Patterns

The following are explicitly prohibited and will cause a review rejection:

### "It should work" without tests

Claiming that code is correct by visual inspection or informal manual testing is not
acceptable. If there is no automated test, the feature is not done. This applies to
bug fixes as well: every bug fix must include a test that would have caught the bug.

### Tests that only check no-throw

A test whose only assertion is that the code does not throw an exception is not a valid
test. Example of an invalid test:

```python
def test_login():
    result = login("user", "pass")  # no assertion on result
    # test passes if no exception is raised -- INVALID
```

Every test must assert something specific about the output, state change, or returned
value. At minimum, verify the return type and at least one expected property.

### Marking done without init.sh

No feature may be marked as `done` unless `./init.sh` completes successfully. This is
the final gate. If `init.sh` fails for any reason, the feature remains `in_progress`.

---

## Final Verification

The last step before any feature transitions to `done` is:

```
./init.sh
```

The script must finish with the output:

```
[OK]
```

If the script produces any error output, exits with a non-zero code, or does not print
`[OK]` as its final line, the feature is not done. The implementer must diagnose and
fix the issue before requesting the completion gate review.

This check is not optional. It is not a suggestion. It is the final, non-negotiable
proof that the system is in a working state.
