#!/usr/bin/env bash
# run-gates.sh - the single acceptance-gate entry point (ACCEPTANCE.md §0).
#
# Usage:
#   tools/acceptance/run-gates.sh                # run all in-scope gates
#   tools/acceptance/run-gates.sh G05 G09        # run a subset
#
# Behaviour:
#   * Exports JAVA_HOME / ANDROID_HOME itself; fails fast (G01) if a precondition is missing.
#   * Per gate: records start/end/exit and combined stdout+stderr into acceptance/logs/<id>.log,
#     classifies PASS/FAIL/BLOCKED/DEGRADED/NOT_IN_SCOPE/ADVISORY per §0, parses JUnit XML
#     (build/test-results/**/*.xml) into tests/failures/skipped, sha256s declared artifacts.
#   * Writes acceptance/manifest.json (§4 schema) with verdict.accepted computed mechanically,
#     and acceptance/REPORT.md (one line per gate + a "Not verifiable on this machine" section).
#   * Prints one machine-readable VERDICT line and exits non-zero when not accepted.
#
# Exit-code map for every gate command: 0 = PASS, 3 = BLOCKED, other = FAIL (per task brief;
# record.mjs and the check-*.sh scripts follow it).
#
# Machine limitations encoded here (no fabricated greens):
#   - gradle-type units execute ONLY when DSH_ALLOW_GRADLE=1 is set. On a half-built tree (or a
#     session that must not touch gradle) they are classified BLOCKED, so an unbuilt module is
#     never reported as PASS. Set DSH_ALLOW_GRADLE=1 for a real full run.
#   - instrumented / Compose-UI device automation is NOT_IN_SCOPE (DECISIONS D20: no emulator, no
#     system images, adb devices empty); the human device checklist is the advisory gate G20.
#
# set -u -o pipefail; makes NO network calls of its own; idempotent and re-runnable.

set -u -o pipefail
shopt -s globstar nullglob

ROOT="."
d="$(pwd)"
while [ "$d" != "/" ]; do
  if [ -f "$d/settings.gradle.kts" ]; then ROOT="$d"; break; fi
  d="$(dirname "$d")"
done
cd "$ROOT" || { echo "run-gates: cannot locate project root (settings.gradle.kts)"; exit 1; }

# --- environment ------------------------------------------------------------
export JAVA_HOME="${JAVA_HOME:-/home/keivn/jdk17/jdk-17.0.20+8}"
export ANDROID_HOME="${ANDROID_HOME:-/home/keivn/Android/Sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

GATES_FILE="acceptance/gates.json"
LOGDIR="acceptance/logs"
MANIFEST="acceptance/manifest.json"
REPORT="acceptance/REPORT.md"
mkdir -p "$LOGDIR"

command -v jq >/dev/null 2>&1 || { echo "run-gates: jq is required"; exit 1; }
[ -f "$GATES_FILE" ] || { echo "run-gates: missing $GATES_FILE"; exit 1; }

RUNSTART_EPOCH="$(date +%s)"
RUNID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# --- G01 toolchain precheck -------------------------------------------------
PRECHECK_REPORT=""
PRECHECK_FAILED=0
run_precheck() {
  local problems=""
  # JDK
  if [ -x "$JAVA_HOME/bin/java" ] && "$JAVA_HOME/bin/java" -version >/dev/null 2>&1; then
    :
  else
    problems+="missing/unusable JDK at JAVA_HOME=$JAVA_HOME; "
  fi
  # Android platform (ACCEPTANCE names platforms/android-37; this machine ships android-37.0)
  if ls -d "$ANDROID_HOME"/platforms/android-37* >/dev/null 2>&1; then :; else
    problems+="platforms/android-37* missing under ANDROID_HOME; "
  fi
  # build-tools 36.0.0
  if [ -d "$ANDROID_HOME/build-tools/36.0.0" ]; then :; else
    problems+="build-tools/36.0.0 missing; "
  fi
  # apkanalyzer
  if [ -f "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" ]; then :; else
    problems+="apkanalyzer missing; "
  fi
  # gradle wrapper version (read from properties; do NOT invoke gradle in the precheck)
  local gurl gver
  gurl="$(grep -oE 'gradle-[0-9][0-9.]*(-bin|-all)?\.zip' gradle/wrapper/gradle-wrapper.properties 2>/dev/null | head -1)"
  gver="$(printf '%s' "$gurl" | sed -E 's/gradle-([0-9.]+)-.*/\1/')"
  if [ "$gver" = "9.6.1" ]; then :; else
    problems+="gradle wrapper version is '${gver:-unknown}', expected 9.6.1; "
  fi
  # org.gradle.java.installations.paths in gradle.properties
  if grep -q '^org.gradle.java.installations.paths' gradle.properties 2>/dev/null; then :; else
    problems+="gradle.properties missing org.gradle.java.installations.paths; "
  fi
  if [ -n "$problems" ]; then
    PRECHECK_FAILED=1
    PRECHECK_REPORT="$problems"
    return 1
  fi
  PRECHECK_REPORT="JDK 17 ok; android-37* present; build-tools/36.0.0 present; apkanalyzer present; wrapper=9.6.1; installations.paths declared"
  return 0
}

# --- helpers ----------------------------------------------------------------
now_ms() { date +%s%3N 2>/dev/null || echo "$(( $(date +%s) * 1000 ))"; }

# parse JUnit XML under a module dir (or repo-wide when no dirs given) -> "tests failures skipped"
parse_junit() {
  local dirs="$1"
  python3 - "$dirs" <<'PY'
import sys, os, glob
import xml.etree.ElementTree as ET
dirs=[x for x in sys.argv[1].split('\t') if x]
if not dirs:
    roots=['.']
else:
    roots=dirs
tests=failures=skipped=0
seen=set()
for r in roots:
    for xml in glob.glob(os.path.join(r,'**','build','test-results','**','*.xml'), recursive=True):
        real=os.path.realpath(xml)
        if real in seen: continue
        seen.add(real)
        try: root=ET.parse(xml).getroot()
        except Exception: continue
        suites = root.iter('testsuite')
        found=False
        for ts in suites:
            found=True
            tests+=int(ts.get('tests') or 0)
            failures+=int(ts.get('failures') or 0)+int(ts.get('errors') or 0)
            skipped+=int(ts.get('skipped') or 0)
        if not found and root.tag=='testsuite':
            tests+=int(root.get('tests') or 0)
            failures+=int(root.get('failures') or 0)+int(root.get('errors') or 0)
            skipped+=int(root.get('skipped') or 0)
print(f"{tests}\t{failures}\t{skipped}")
PY
}

# sha256 the declared artifact globs -> writes JSON to stdout
hash_artifacts() { # one pattern per remaining arg
  local json="[]"
  local files=()
  local pat
  for pat in "$@"; do
    for f in $pat; do [ -f "$f" ] && files+=("$f"); done
  done
  if [ "${#files[@]}" = "0" ]; then echo "[]"; return; fi
  printf '%s\n' "${files[@]}" | awk '{print $1}' | sort -u | while read -r f; do
    s="$(sha256sum "$f" | cut -d' ' -f1)"
    printf '%s\t%s\n' "$f" "$s"
  done | jq -R -s 'split("\n")|map(select(length>0))|map(split("\t")|{path:.[0],sha256:.[1]})'
}

# run a single unit; echoes combined status PASS|FAIL|BLOCKED ; updates globals
UNIT_RC=0; UNIT_STATUS=""
run_unit() { # gateId unitIdx kind title-json  rest...
  local gate="$1" uidx="$2" kind="$3" title="$4"; shift 4
  local log="$LOGDIR/$gate.log"
  local argv_json="$1" prereq="$2" builtin="$3"
  local rc=0 skipped=0 start end
  printf '\n---- unit[%s] %s (%s) ----\n' "$uidx" "$title" "$kind" >> "$log"

  # decide runnability
  if [ "$kind" = "internal" ]; then
    return 0  # handled by caller (G01/G20)
  fi
  if [ "$kind" = "gradle" ]; then
    if [ "${DSH_ALLOW_GRADLE:-0}" != "1" ]; then
      echo "[BLOCKED] gradle unit '$title' not executed: DSH_ALLOW_GRADLE!=1 (half-built/offline run)" >> "$log"
      UNIT_RC=3; UNIT_STATUS="BLOCKED"; return 0
    fi
    [ -x "./gradlew" ] || { echo "[BLOCKED] ./gradlew missing" >> "$log"; UNIT_RC=3; UNIT_STATUS="BLOCKED"; return 0; }
  else
    if [ -n "$prereq" ] && [ ! -f "$prereq" ]; then
      echo "[BLOCKED] prerequisite artifact absent: $prereq" >> "$log"
      UNIT_RC=3; UNIT_STATUS="BLOCKED"; return 0
    fi
  fi

  # execute
  local arr=()
  mapfile -t arr < <(printf '%s' "$argv_json" | jq -r '.[]')
  start="$(now_ms)"
  if [ "${#arr[@]}" = "0" ]; then
    rc=127; echo "[FAIL] empty argv" >> "$log"
  else
    ( "${arr[@]}" ) >> "$log" 2>&1; rc=$?
  fi
  end="$(now_ms)"
  echo "---- exit=$rc  ${start}..${end} ms ----" >> "$log"
  UNIT_RC=$rc
  case "$rc" in
    0) UNIT_STATUS="PASS" ;;
    3) UNIT_STATUS="BLOCKED" ;;
    *) UNIT_STATUS="FAIL" ;;
  esac
  return 0
}

# --- main loop --------------------------------------------------------------
declare -a REQUESTED=("$@")
ALL_IDS=()
while IFS= read -r id; do ALL_IDS+=("$id"); done < <(jq -r '.gates[].id' "$GATES_FILE")

# Determine which gates to process.
declare -a SELECT=()
if [ "${#REQUESTED[@]}" = "0" ]; then
  SELECT=("${ALL_IDS[@]}")
else
  for want in "${REQUESTED[@]}"; do
    if printf '%s\n' "${ALL_IDS[@]}" | grep -qx "$want"; then
      SELECT+=("$want")
    else
      echo "run-gates: '$want' is not a known gate id (skipped)"
    fi
  done
  if [ "${#SELECT[@]}" = "0" ]; then
    echo "run-gates: no valid gate ids requested"; 
  fi
fi

# Run G01 precheck first (fail-fast semantics).
run_precheck || true

GATES_JSONL="$LOGDIR/.gates.$RUNID.jsonl"; : > "$GATES_JSONL"
trap 'rm -f "$GATES_JSONL"' EXIT

declare -i C_FAIL=0 C_BLOCK=0 C_PASS=0 C_DEGRADED=0 C_ADV=0 C_NIS=0
FAILING_INSCOPE=0

for gid in "${SELECT[@]}"; do
  gtitle="$(jq -r --arg g "$gid" '.gates[]|select(.id==$g)|.title' "$GATES_FILE")"
  gblock="$(jq -r --arg g "$gid" '.gates[]|select(.id==$g)|.blocking' "$GATES_FILE")"
  gscope="$(jq -r --arg g "$gid" '.gates[]|select(.id==$g)|.scope' "$GATES_FILE")"
  gdegr="$(jq -r --arg g "$gid" '.gates[]|select(.id==$g)|.degradedAllowed' "$GATES_FILE")"
  gcmd="$(jq -r --arg g "$gid" '.gates[]|select(.id==$g)|.command' "$GATES_FILE")"
  gnotes="$(jq -r --arg g "$gid" '.gates[]|select(.id==$g)|.notes' "$GATES_FILE")"
 gartifacts="$(jq -r --arg g "$gid" '.gates[]|select(.id==$g)|.artifacts[]' "$GATES_FILE")"

  log="$LOGDIR/$gid.log"; : > "$log"
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  # NOT_IN_SCOPE / G01 special handling
  if [ "$gscope" = "not-in-scope" ]; then
    echo "NOT_IN_SCOPE: gate not implemented this round" >> "$log"
    status="NOT_IN_SCOPE"; C_NIS+=1
    assertions='{"tests":0,"failures":0,"skipped":0}'; artifacts='[]'
    gnotes="$gnotes"
    jq -n --arg id "$gid" --arg title "$gtitle" --argjson blocking "$gblock" \
       --arg status "$status" --arg command "$gcmd" --argjson assertions "$assertions" \
       --argjson artifacts "$artifacts" --arg notes "$gnotes" \
       '{id:$id,title:$title,blocking:$blocking,status:$status,command:$command,assertions:$assertions,artifacts:$artifacts,notes:$notes}' >> "$GATES_JSONL"
    continue
  fi

  if [ "$gid" = "G01" ]; then
    # builtin precheck already ran
    echo "G01 toolchain precheck: $PRECHECK_REPORT" >> "$log"
    if [ "$PRECHECK_FAILED" = "1" ]; then status="FAIL"; C_FAIL+=1; else status="PASS"; C_PASS+=1; fi
    assertions='{"tests":0,"failures":0,"skipped":0}'
    artifacts="$(hash_artifacts "$log" gradle.properties gradle/wrapper/gradle-wrapper.properties app/build.gradle.kts)"
    jq -n --arg id "$gid" --arg title "$gtitle" --argjson blocking "$gblock" \
       --arg status "$status" --arg command "$gcmd" --argjson assertions "$assertions" \
       --argjson artifacts "$artifacts" --arg notes "precondition: $PRECHECK_REPORT" \
       '{id:$id,title:$title,blocking:$blocking,status:$status,command:$command,assertions:$assertions,artifacts:$artifacts,notes:$notes}' >> "$GATES_JSONL"
    continue
  fi

  # On precheck failure, fail-fast: mark remaining in-scope blocking gates FAIL, no partial execution.
  if [ "$PRECHECK_FAILED" = "1" ] && [ "$gscope" != "advisory" ]; then
    echo "FAIL: G01 toolchain precheck failed ('$PRECHECK_REPORT'); §1 mandates no partial execution" >> "$log"
    status="FAIL"; C_FAIL+=1; FAILING_INSCOPE=1
    jq -n --arg id "$gid" --arg title "$gtitle" --argjson blocking "$gblock" \
       --arg status "$status" --arg command "$gcmd" \
       --argjson assertions '{"tests":0,"failures":0,"skipped":0}' --argjson artifacts '[]' \
       --arg notes "aborted by G01 precheck failure" \
       '{id:$id,title:$title,blocking:$blocking,status:$status,command:$command,assertions:$assertions,artifacts:$artifacts,notes:$notes}' >> "$GATES_JSONL"
    continue
  fi

  # G20 builtin advisory device-checklist
  if [ "$gid" = "G20" ]; then
    if [ -f docs/DEVICE_CHECKLIST.md ] && grep -q '状态' docs/DEVICE_CHECKLIST.md; then
      echo "ADVISORY: docs/DEVICE_CHECKLIST.md present with 状态 fields; all NOT_RUN this round" >> "$log"
    else
      echo "ADVISORY: docs/DEVICE_CHECKLIST.md $( [ -f docs/DEVICE_CHECKLIST.md ] && echo 'present but lacks 状态 fields' || echo 'absent' ); device matrix NOT_IN_SCOPE per DECISIONS D20" >> "$log"
    fi
    status="ADVISORY"; C_ADV+=1; assertions='{"tests":0,"failures":0,"skipped":0}'
    artifacts="$(hash_artifacts docs/DEVICE_CHECKLIST.md)"
    jq -n --arg id "$gid" --arg title "$gtitle" --argjson blocking "$gblock" \
       --arg status "$status" --arg command "$gcmd" --argjson assertions "$assertions" \
       --argjson artifacts "$artifacts" --arg notes "$gnotes" \
       '{id:$id,title:$title,blocking:$blocking,status:$status,command:$command,assertions:$assertions,artifacts:$artifacts,notes:$notes}' >> "$GATES_JSONL"
    continue
  fi

  # Normal gate: run every unit; combine.
  unit_count="$(jq -r --arg g "$gid" '.gates[]|select(.id==$g)|.units|length' "$GATES_FILE")"
  gate_raw="PASS"     # PASS/BLOCKED/FAIL precedence: FAIL > BLOCKED > PASS
  junit_dirs=""
  i=0
  while [ "$i" -lt "$unit_count" ]; do
    ukind="$(jq -r --arg g "$gid" --argjson i "$i" '.gates[]|select(.id==$g)|.units[$i].kind' "$GATES_FILE")"
    utitle="$(jq -r --arg g "$gid" --argjson i "$i" '.gates[]|select(.id==$g)|.units[$i].title // ""' "$GATES_FILE")"
    uargv="$(jq -c --arg g "$gid" --argjson i "$i" '.gates[]|select(.id==$g)|.units[$i].argv // []' "$GATES_FILE")"
    uprereq="$(jq -r --arg g "$gid" --argjson i "$i" '.gates[]|select(.id==$g)|.units[$i].prereq // ""' "$GATES_FILE")"
    run_unit "$gid" "$i" "$ukind" "$utitle" "$uargv" "$uprereq" ""
    st="$UNIT_STATUS"
    # accumulate junit dirs for gradle units that actually ran (not BLOCKED)
    if [ "$ukind" = "gradle" ] && [ "$st" != "BLOCKED" ]; then
      md="$(printf '%s' "$uargv" | jq -r '.[]' | while read -r tok; do
              case "$tok" in
                :*:*)
                  # `:core:network:testDebugUnitTest` -> module dir `core/network` (drop the task segment).
                  p="${tok#:}"; p="${p%:*}"; p="${p//:/\/}"; printf '%s\n' "$p";;
              esac
            done | sort -u | paste -sd$'\t' -)"
      if [ -n "$md" ]; then junit_dirs="${junit_dirs}${junit_dirs:+	}${md}"; fi
    fi
    case "$st" in
      FAIL) gate_raw="FAIL" ;;
      BLOCKED) [ "$gate_raw" = "PASS" ] && gate_raw="BLOCKED" ;;
    esac
    i=$((i+1))
  done

  # Final status: DEGRADED only if allowed and raw PASS; advisory stays ADVISORY.
  if [ "$gscope" = "advisory" ]; then
    status="ADVISORY"; C_ADV+=1
  elif [ "$gate_raw" = "FAIL" ]; then
    status="FAIL"; C_FAIL+=1; [ "$gblock" = "true" ] && FAILING_INSCOPE=1
  elif [ "$gate_raw" = "BLOCKED" ]; then
    status="BLOCKED"; C_BLOCK+=1
  else
    if [ "$gdegr" = "true" ]; then status="DEGRADED"; C_DEGRADED+=1; else status="PASS"; C_PASS+=1; fi
  fi

  # JUnit parse (only meaningful when we actually ran a gradle unit)
  tests=0; failures=0; skipped=0
  if [ -n "$junit_dirs" ]; then
    parsed="$(parse_junit "$junit_dirs")"
    tests="$(printf '%s' "$parsed" | cut -f1)"; failures="$(printf '%s' "$parsed" | cut -f2)"; skipped="$(printf '%s' "$parsed" | cut -f3)"
  fi
  assertions="$(jq -n --argjson t "${tests:-0}" --argjson f "${failures:-0}" --argjson s "${skipped:-0}" '{tests:$t,failures:$f,skipped:$s}')"

  # artifacts (the per-gate log is always attached as reproducible command evidence,
  # so every executed gate — and especially every PASS — satisfies §0 "assertions or artifacts")
  local_art_files=()
  [ -f "$log" ] && local_art_files+=("$log")
  for pat in $gartifacts; do
    for f in $pat; do [ -f "$f" ] && local_art_files+=("$f"); done
  done
  if [ "${#local_art_files[@]}" = "0" ]; then artifacts='[]'
  else
    artifacts="$(printf '%s\n' "${local_art_files[@]}" | sort -u | while read -r f; do printf '%s\t%s\n' "$f" "$(sha256sum "$f"|cut -d' ' -f1)"; done | jq -R -s 'split("\n")|map(select(length>0))|map(split("\t")|{path:.[0],sha256:.[1]})')"
  fi

  jq -n --arg id "$gid" --arg title "$gtitle" --argjson blocking "$gblock" \
     --arg status "$status" --arg command "$gcmd" --argjson assertions "$assertions" \
     --argjson artifacts "$artifacts" --arg notes "$gnotes" \
     '{id:$id,title:$title,blocking:$blocking,status:$status,command:$command,assertions:$assertions,artifacts:$artifacts,notes:$notes}' >> "$GATES_JSONL"
done

# --- verdict ----------------------------------------------------------------
# accepted = no in-scope blocking gate is FAIL or BLOCKED, and none ended in a non-PASS/DEGRADED state.
ACCEPTED=true
# Recompute from the assembled gate objects to be authoritative.
read -r V_FAIL V_BLOCK V_NONOK < <(jq -s -r '
  [ .[] | select(.blocking==true) ] |
  { f: ([.[]|select(.status=="FAIL")]|length),
    b: ([.[]|select(.status=="BLOCKED")]|length),
    nonok: ([.[]|select(.status!="PASS" and .status!="DEGRADED" and .status!="NOT_IN_SCOPE")]|length)
  } | "\(.f) \(.b) \(.nonok)"' "$GATES_JSONL")
if [ "${V_NONOK:-0}" -gt 0 ]; then ACCEPTED=false; fi

DEGRADED_LIST="$(jq -s '[ .[]|select(.status=="DEGRADED")|.id ]' "$GATES_JSONL")"
BLOCKING_FAILS="${V_FAIL:-0}"; BLOCKED_INSCOPE="${V_BLOCK:-0}"

# --- manifest.json ----------------------------------------------------------
jdk_ver="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | tr -d '\r')"
gradle_ver="$(grep -oE 'gradle-[0-9][0-9.]*(|-bin|-all)' gradle/wrapper/gradle-wrapper.properties 2>/dev/null | head -1 | sed -E 's/gradle-([0-9.]+).*/\1/')"
agp_ver="$(grep -E '^agp' gradle/libs.versions.toml 2>/dev/null | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
compile_sdk="$(grep -E 'compileSdk' app/build.gradle.kts 2>/dev/null | head -1 | sed -E 's/.*= *([0-9]+).*/\1/')"
RUNEND_EPOCH="$(date +%s)"
DURATION=$((RUNEND_EPOCH - RUNSTART_EPOCH))
FINISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

jq -n \
  --argjson schemaVersion 1 \
  --arg runId "$RUNID" \
  --arg startedAt "$STARTED_AT" \
  --arg finishedAt "$FINISHED_AT" \
  --argjson durationSeconds "$DURATION" \
  --arg jdk "$jdk_ver" \
  --arg gradle "${gradle_ver:-9.6.1}" \
  --arg agp "${agp_ver:-9.4.1}" \
  --argjson compileSdk "${compile_sdk:-37}" \
  --slurpfile gatesArr <(jq -s '.' "$GATES_JSONL") \
  --argjson blockingFailures "$BLOCKING_FAILS" \
  --argjson blockedInScope "$BLOCKED_INSCOPE" \
  --argjson degraded "$DEGRADED_LIST" \
  --argjson accepted "$ACCEPTED" \
  '{schemaVersion:$schemaVersion, runId:$runId, startedAt:$startedAt, finishedAt:$finishedAt,
    durationSeconds:$durationSeconds,
    toolchain:{jdk:$jdk, gradle:$gradle, agp:$agp, compileSdk:$compileSdk},
    gates:$gatesArr[0],
    verdict:{accepted:$accepted, blockingFailures:$blockingFailures, blockedInScope:$blockedInScope, degraded:$degraded}}' \
  > "$MANIFEST"

# --- REPORT.md --------------------------------------------------------------
{
  echo "# Acceptance Report"
  echo
  echo "- runId: \`$RUNID\`"
  echo "- startedAt: $STARTED_AT  finishedAt: $FINISHED_AT  duration: ${DURATION}s"
  echo "- toolchain: jdk=\`${jdk_ver}\` gradle=${gradle_ver:-9.6.1} agp=${agp_ver:-9.4.1} compileSdk=${compile_sdk:-37}"
  echo "- verdict: **accepted=$ACCEPTED**  blockingFailures=$BLOCKING_FAILS  blockedInScope=$BLOCKED_INSCOPE  degraded=$(printf '%s' "$DEGRADED_LIST" | jq -r 'join(",")' 2>/dev/null || echo '')"
  echo
  echo "## Gates"
  echo
  echo "| Gate | Status | Tests/Fail/Skip | Artifacts | Log |"
  echo "|---|---|---|---|---|"
  jq -s -r '.[] | "| \(.id) \(.title) | \(.status) | \(.assertions.tests)/\(.assertions.failures)/\(.assertions.skipped) | \(.artifacts|length) | acceptance/logs/\(.id).log |"' "$GATES_JSONL"
  echo
  echo "## Not verifiable on this machine"
  echo
  echo "- Instrumented / Compose-UI device tests: **NOT_IN_SCOPE** (DECISIONS D20 — no emulator, no system images, \`adb devices\` empty)."
  jq -s -r '.[] | select(.status=="NOT_IN_SCOPE" or .status=="ADVISORY" or .status=="BLOCKED") | "- \(.id) \(.title): \(.status) — \((.notes|split(".")[0]) // "")"' "$GATES_JSONL"
} > "$REPORT"

# --- final line -------------------------------------------------------------
PROCESSED="$(jq -s 'length' "$GATES_JSONL")"
echo "run-gates: ${DURATION}s, ${PROCESSED} gate(s) processed -> $MANIFEST and $REPORT"
echo "VERDICT accepted=$ACCEPTED failed=${BLOCKING_FAILS:-0} blocked=${BLOCKED_INSCOPE:-0} degraded=$(printf '%s' "$DEGRADED_LIST" | jq -r 'length')"

if [ "$ACCEPTED" = "true" ]; then exit 0; else exit 1; fi
