#!/usr/bin/env bash
# check-diagnostics.sh - gate G10 (diagnostics-schema), source-level segment.
#
# The gradle unit of G10 (`:core:domain:test --tests '*Diagnostics*'`) proves the type's
# behavior; this segment proves the SCHEMA and the consumer discipline:
#   1. every field ACCEPTANCE §G10 names is declared on DiagnosticsSnapshot;
#   2. a diagnostics test class exists in :core:domain (the gate's unit filter must match it);
#   3. some `:feature:*`/`:app` source consumes the typed snapshot (UI renders the type, not
#      ad-hoc strings) — DiagnosticsSnapshot or DiagnosticsRepository;
#   4. no `:feature:*`/`:app` source CONSTRUCTS a DiagnosticsSnapshot (only the data layer may:
#      a UI that fabricates a snapshot can display numbers nobody measured).
#
# Exit discipline: 0 = PASS, 1 = FAIL (prints every finding), 3 = BLOCKED (inputs absent yet).
# No network calls; nothing written outside the tree.

set -u

ROOT="."
d="$(pwd)"
while [ "$d" != "/" ]; do
  if [ -f "$d/settings.gradle.kts" ]; then ROOT="$d"; break; fi
  d="$(dirname "$d")"
done
cd "$ROOT" || exit 1

MODEL="core/model/src/main/kotlin/ai/deepseek/dsh/android/model/Diagnostics.kt"
DOMAIN_TESTS="core/domain/src/test"

if [ ! -f "$MODEL" ]; then
  echo "BLOCKED: $MODEL not found (G10 cannot judge a tree without the diagnostics type)"
  exit 3
fi
if [ ! -d "$DOMAIN_TESTS" ]; then
  echo "BLOCKED: $DOMAIN_TESTS not found (G10 cannot judge a tree without domain tests)"
  exit 3
fi

FINDINGS=()

# ---- 1. required fields ----
REQUIRED=(
  transportCursorPerChannel
  contiguousSeqLag
  needsResyncCount
  historyIncompleteSessions
  historyUnsafeSessions
  pendingCompleteness
  baselineSweepSkipped
  authState
  lastPollWaitSupported
  retentionTrimmedCount
  badJsonCount
  wsDowngradeCount
  identityRetiredPendingPurge
)
for field in "${REQUIRED[@]}"; do
  if ! grep -qE "val[[:space:]]+${field}[[:space:]]*:" "$MODEL"; then
    FINDINGS+=("$MODEL: required field '$field' is not declared")
  fi
done

# ---- 2. a *Diagnostics* test class in :core:domain ----
if ! find "$DOMAIN_TESTS" -name '*.kt' -print0 2>/dev/null | xargs -0 grep -l 'class .*Diagnostics' >/dev/null 2>&1; then
  FINDINGS+=("$DOMAIN_TESTS: no test class named *Diagnostics* (the gate's --tests '*Diagnostics*' would match nothing)")
fi

# ---- 3./4. consumer discipline in feature/app sources ----
mapfile -t UI_SOURCES < <(find app feature -type f -name '*.kt' -path '*/src/*' -not -path '*/build/*' 2>/dev/null | sort)
if [ "${#UI_SOURCES[@]}" = "0" ]; then
  echo "BLOCKED: no app/feature Kotlin sources yet (G10 consumer check cannot run)"
  exit 3
fi

consumers=0
for f in "${UI_SOURCES[@]}"; do
  if grep -qE 'DiagnosticsSnapshot|DiagnosticsRepository' "$f"; then
    consumers=$((consumers + 1))
  fi
  if grep -nE 'DiagnosticsSnapshot[[:space:]]*\(' "$f" >/dev/null 2>&1; then
    while IFS= read -r line; do
      FINDINGS+=("$f:${line%%:*}: feature/app layer constructs DiagnosticsSnapshot; only :core:data may fabricate diagnostics")
    done < <(grep -nE 'DiagnosticsSnapshot[[:space:]]*\(' "$f")
  fi
done
if [ "$consumers" = "0" ]; then
  FINDINGS+=("app/feature: no source consumes DiagnosticsSnapshot/DiagnosticsRepository (the diagnostics page would have to invent strings)")
fi

if [ "${#FINDINGS[@]}" -gt 0 ]; then
  echo "G10 check-diagnostics: ${#FINDINGS[@]} finding(s)"
  printf '%s\n' "${FINDINGS[@]}"
  exit 1
fi

echo "G10 check-diagnostics: schema complete, domain test present, UI consumes the typed snapshot"
exit 0
