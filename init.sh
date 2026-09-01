#!/usr/bin/env bash
# init-verify.sh — Project verification script
#
# This script is copied to the project root as init.sh during harness installation.
# Run at session start and before marking any feature as done.

set -u
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

ok()    { printf "${GREEN}[OK]${NC}    %s\n" "$1"; }
warn()  { printf "${YELLOW}[WARN]${NC}  %s\n" "$1"; }
fail()  { printf "${RED}[FAIL]${NC}  %s\n" "$1"; }

EXIT_CODE=0

echo "── 1. Checking harness files ──────────────────────────"

for f in AGENTS.md feature_list.json progress/current.md docs/architecture.md docs/conventions.md docs/verification.md docs/specs.md CHECKPOINTS.md CLAUDE.md; do
  if [ ! -f "$f" ]; then
    fail "Missing base file: $f"
    EXIT_CODE=1
  else
    ok "Exists $f"
  fi
done

echo ""
echo "── 2. Validating feature_list.json ────────────────────"

if command -v python3 >/dev/null 2>&1; then
  python3 - <<'PY'
import json, os, sys
try:
    data = json.load(open("feature_list.json"))
    features = data.get("features", [])
    valid = {"pending", "spec_ready", "in_progress", "done", "blocked"}
    in_progress = [f for f in features if f.get("status") == "in_progress"]
    if len(in_progress) > 1:
        print(f"[FAIL]  {len(in_progress)} features in in_progress (max 1)")
        sys.exit(1)
    spec_errors = []
    requires_spec = {"spec_ready", "in_progress", "done"}
    for f in features:
        if f.get("status") not in valid:
            print(f"[FAIL]  Invalid status in feature {f.get('id')}: {f.get('status')}")
            sys.exit(1)
        if f.get("status") in requires_spec:
            spec_dir = os.path.join("specs", f["name"])
            for fname in ("requirements.md", "design.md", "tasks.md"):
                if not os.path.isfile(os.path.join(spec_dir, fname)):
                    spec_errors.append(
                        f"feature {f['id']} ({f['name']}) in {f['status']} "
                        f"without {spec_dir}/{fname}"
                    )
    if spec_errors:
        for e in spec_errors:
            print(f"[FAIL]  {e}")
        sys.exit(1)
    print(f"[OK]    feature_list.json valid ({len(features)} features)")
    if in_progress:
        print(f"[OK]    Feature in progress: {in_progress[0]['name']}")
except SystemExit:
    raise
except Exception as e:
    print(f"[FAIL]  feature_list.json invalid: {e}")
    sys.exit(1)
PY
  if [ $? -ne 0 ]; then EXIT_CODE=1; fi
else
  warn "python3 not available — skipping feature_list.json validation"
fi

echo ""
echo "── 3. Running tests ──────────────────────────────────"

# Auto-detect test command based on stack
TEST_CMD=""
if [ -f "tsconfig.json" ]; then
  TEST_CMD="npx vitest run 2>&1"
elif [ -f "package.json" ]; then
  TEST_CMD="npm test 2>&1"
elif [ -f "Cargo.toml" ]; then
  TEST_CMD="cargo test 2>&1"
elif [ -f "build.gradle" ]; then
  TEST_CMD="./gradlew test 2>&1"
elif [ -f "pom.xml" ]; then
  TEST_CMD="mvn test 2>&1"
elif [ -d "tests" ] && command -v python3 >/dev/null 2>&1; then
  TEST_CMD="python3 -m unittest discover -s tests -v 2>&1"
fi

if [ -n "$TEST_CMD" ]; then
  echo "Running: $TEST_CMD"
  if eval "$TEST_CMD"; then
    ok "All tests pass"
  else
    fail "Tests failing"
    EXIT_CODE=1
  fi
else
  warn "No test framework detected — skipping test run"
fi

echo ""
echo "── 4. Summary ────────────────────────────────────────"

if [ $EXIT_CODE -eq 0 ]; then
  ok "Environment ready. You can start working."
else
  fail "Environment NOT ready. Fix errors before proceeding."
fi

exit $EXIT_CODE
