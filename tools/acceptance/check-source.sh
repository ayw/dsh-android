#!/usr/bin/env bash
# check-source.sh - gate G09.3 (security-invariants, source-level segment).
#
# Exit discipline: 0 = PASS, 1 = FAIL (prints every violation as file:line), 3 = BLOCKED
# (nothing to inspect yet -> an unfinished tree must not read as accepted).
#
# Checks implemented (ACCEPTANCE G09.3 / DECISIONS D16):
#   (a) `android.util.Log` (and any bare `Log.x(` call) may appear only in the DshLog
#       facade file (**/DshLog.kt). Comments and string literals are stripped first, so
#       prose that merely NAMES the class is not a violation; code that calls it is.
#   (b) no anonymous or permissive X509TrustManager, no HostnameVerifier that returns true.
#   (c) no `allowBackup="true"` and no `dshremote` scheme in any SOURCE AndroidManifest.xml
#       (files under */src/**; generated build/intermediates manifests are not authored
#       invariants and are excluded to avoid false positives).
#   (d) exported components allowlist: only the launcher activity may be exported=true;
#       a component carrying an <intent-filter> without an explicit exported is also a
#       violation (minSdk 26 / targetSdk 37 requires it explicit).
#   (e) magic-number scan (docs/TUNING.md semantics).
#   (f) proguard-rules.pro must not contain `-keep class ** { *; }`.
#
# MAGIC-NUMBER RULE (chosen for low false positives; see TUNING.md):
#   A literal is a "magic number" if, after comments (// and /* */) and string/char
#   literals are stripped, it is a DECIMAL integer/long token ([0-9], optional `_` digit
#   separators, optional [lL] suffix) containing 4 or more digits (value >= 1000).
#   This is exactly the shape of every ms/byte threshold in TUNING.md (25_000, 1_500,
#   1024, 1000, 5_000, 2_000 ...). Small 2-3 digit thresholds (workerMaxRounds=3,
#   historyPageSize=50, multipliers 2.0/0.8/1.2, 60, 24, 10) are deliberately NOT scanned:
#   they collide with minSdk, indices, and unrelated constants, so scanning them would
#   raise false positives. Such values are policed by review + the SyncTuning unit tests,
#   not this regex. Allowed locations: SyncTuning.kt and any test source
#   (*/src/test/*, */src/androidTest/*). Everywhere else is a violation.
#
# This script makes NO network calls and writes outside the tree nowhere.

set -u

ROOT="."
d="$(pwd)"
while [ "$d" != "/" ]; do
  if [ -f "$d/settings.gradle.kts" ]; then ROOT="$d"; break; fi
  d="$(dirname "$d")"
done
cd "$ROOT" || exit 1

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

collect() { # append violations from a file to master list
  cat "$1" >> "$TMP/violations.txt"
}
: > "$TMP/violations.txt"

# Enumerate all Kotlin sources (exclude any /build/ directory) and source manifests.
find . -type f -name '*.kt' -not -path '*/build/*' 2>/dev/null | sort > "$TMP/kt.all"
find . -type f -path '*/src/*/AndroidManifest.xml' -not -path '*/build/*' 2>/dev/null | sort > "$TMP/manifests"
find . -type f -name 'proguard-rules.pro' -not -path '*/build/*' 2>/dev/null | sort > "$TMP/proguard"

KT_COUNT="$(wc -l < "$TMP/kt.all" | tr -d ' ')"
MF_COUNT="$(wc -l < "$TMP/manifests" | tr -d ' ')"

# If there is literally nothing to inspect, the tree is too immature to judge -> BLOCKED.
if [ "$KT_COUNT" = "0" ] && [ "$MF_COUNT" = "0" ]; then
  echo "BLOCKED: no Kotlin sources and no source AndroidManifest.xml found (G09.3 cannot run on an empty tree)"
  exit 3
fi

declare -a STATUS=()
record() { # "G09.3x PASS|FAIL" — printed sorted so the report keeps a..f order
  STATUS+=("$1")
}

# ---------------------------------------------------------------------------
# (a)/(b)/(d)/(e) handled in one python3 pass for reliability
# ---------------------------------------------------------------------------
python3 - "$TMP" <<'PY'
import sys, os, re
tmp = sys.argv[1]
kt = [l.rstrip('\n') for l in open(os.path.join(tmp,'kt.all')) if l.strip()]
manifests = [l.rstrip('\n') for l in open(os.path.join(tmp,'manifests')) if l.strip()]

def strip_code(text):
    # remove block comments, line comments, and string/char literals (best effort).
    # Block comments are replaced by the same number of newlines so callers can keep
    # reporting accurate line numbers.
    text = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), text, flags=re.S)
    out=[]
    for line in text.splitlines():
        line = re.sub(r'//.*$', '', line)
        # blank out double- and single-quoted literals so their digits don't count
        line = re.sub(r'""".*?"""', ' ', line, flags=re.S)
        line = re.sub(r'"(\\.|[^"\\])*"', '""', line)
        line = re.sub(r"'(\\.|[^'\\])*'", "''", line)
        out.append(line)
    return out

# (a) framework logger outside the facade
a=[]
logger = re.compile(r'android\.util\.Log|\bLog\.[dviwe]\s*\(')
for f in kt:
    if os.path.basename(f) == 'DshLog.kt': continue
    try: raw=open(f,encoding='utf-8',errors='replace').read()
    except Exception: continue
    for i,line in enumerate(strip_code(raw),1):
        if logger.search(line):
            a.append(f"{f}:{i}: android.util.Log outside DshLog facade")
a=sorted(set(a))
open(os.path.join(tmp,'a'),'w').write("\n".join(a)+("\n" if a else ""))

# (b) trust-all TLS
b=[]
anon_tm = re.compile(r'object\s*:[^{:]*X509TrustManager')
trust_all = re.compile(r'check(Server|Client)Trusted\s*\([^)]*\)\s*\{\s*\}')
empty_return = re.compile(r'return\s+(arrayOf\(\)|Array\s*\(\s*0)')
hnv_allow = re.compile(r'ALLOW_ALL_HOSTNAME_VERIFIER')
for f in kt:
    try: raw=open(f,encoding='utf-8',errors='replace').read()
    except Exception: continue
    for i,line in enumerate(raw.splitlines(),1):
        if anon_tm.search(line):
            b.append(f"{f}:{i}: anonymous object implementing X509TrustManager")
        if hnv_allow.search(line):
            b.append(f"{f}:{i}: ALLOW_ALL_HOSTNAME_VERIFIER")
        if 'HostnameVerifier' in raw:
            # detect a verify lambda/method that literally returns true
            for m in re.finditer(r'(verify\s*\([^)]*\)\s*=\s*true|HostnameVerifier[^{]*\{[^}]*->\s*true)', raw):
                ln = raw[:m.start()].count('\n')+1
                b.append(f"{f}:{ln}: HostnameVerifier returns true")
                break
    # empty trust-all method bodies (multi-line)
    for m in trust_all.finditer(raw):
        ln=raw[:m.start()].count('\n')+1
        b.append(f"{f}:{ln}: X509TrustManager trust-all (empty checkTrusted body)")
b=sorted(set(b))
open(os.path.join(tmp,'b'),'w').write("\n".join(b)+("\n" if b else ""))

# (d) exported components allowlist
import xml.etree.ElementTree as ET
A='{http://schemas.android.com/apk/res/android}'
d_out=[]
COMPONENT_TAGS={'activity','activity-alias','service','receiver','provider'}
for mf in manifests:
    try:
        tree=ET.parse(mf); root=tree.getroot()
    except Exception as e:
        d_out.append(f"{mf}:1: manifest parse error {e}"); continue
    app=root.find('application')
    if app is None: continue
    for comp in list(app):
        if comp.tag not in COMPONENT_TAGS: continue
        name=comp.get(A+'name','?')
        exported=comp.get(A+'exported')
        has_filter=comp.find('intent-filter') is not None
        is_launcher=False
        if has_filter:
            for cat in comp.iter('category'):
                if cat.get(A+'name','').endswith('category.LAUNCHER'):
                    is_launcher=True
        # find a representative line number for reporting
        ln=1
        for idx,line in enumerate(open(mf,encoding='utf-8',errors='replace'),1):
            if name!='?' and name in line: ln=idx; break
        if exported=='true':
            if comp.tag=='activity' and is_launcher:
                pass  # allowed: the single launcher activity
            else:
                d_out.append(f"{mf}:{ln}: exported=\"true\" component '{name}' is not the launcher activity")
        elif exported is None and has_filter:
            d_out.append(f"{mf}:{ln}: component '{name}' has an intent-filter but no explicit android:exported")
d_out=sorted(set(d_out))
open(os.path.join(tmp,'d'),'w').write("\n".join(d_out)+("\n" if d_out else ""))

# (e) magic numbers
e_out=[]
INT=re.compile(r'(?<![A-Za-z0-9_.])[0-9][0-9_]*[lL]?(?![A-Za-z0-9_.])')
for f in kt:
    base=os.path.basename(f)
    if base=='SyncTuning.kt': continue
    if '/src/test/' in f or '/src/androidTest/' in f: continue
    # TUNING.md: "除该文件与测试 fixture 外". :core:testing is the designated test-fixture
    # module, so its sources are allowed to carry fixture literals (epoch timestamps, etc.).
    if '/core/testing/' in f: continue
    try: raw=open(f,encoding='utf-8',errors='replace').read()
    except Exception: continue
    for i,line in enumerate(strip_code(raw),1):
        for m in INT.finditer(line):
            tok=m.group(0)
            digits=re.sub(r'[^0-9]','',tok)
            if len(digits)>=4:
                e_out.append(f"{f}:{i}: magic number '{tok.strip()}' outside SyncTuning.kt/test sources")
e_out=sorted(set(e_out))
open(os.path.join(tmp,'e'),'w').write("\n".join(e_out)+("\n" if e_out else ""))
PY

[ -s "$TMP/a" ] && collect "$TMP/a" && record "G09.3a FAIL" || record "G09.3a PASS"
[ -s "$TMP/b" ] && collect "$TMP/b" && record "G09.3b FAIL" || record "G09.3b PASS"
[ -s "$TMP/d" ] && collect "$TMP/d" && record "G09.3d FAIL" || record "G09.3d PASS"
[ -s "$TMP/e" ] && collect "$TMP/e" && record "G09.3e FAIL" || record "G09.3e PASS"

# ---------------------------------------------------------------------------
# (c) allowBackup="true" / dshremote scheme in source manifests
# ---------------------------------------------------------------------------
c_viol="$TMP/c"; : > "$c_viol"
if [ "$MF_COUNT" != "0" ]; then
  while IFS= read -r m; do
    grep -nE 'allowBackup="true"' "$m" 2>/dev/null | while IFS= read -r line; do
      printf '%s:%s: allowBackup="true"\n' "$m" "${line%%:*}"; done >> "$c_viol"
    grep -nE 'dshremote' "$m" 2>/dev/null | while IFS= read -r line; do
      printf '%s:%s: dshremote scheme in manifest\n' "$m" "${line%%:*}"; done >> "$c_viol"
  done < "$TMP/manifests"
fi
[ -s "$c_viol" ] && collect "$c_viol" && record "G09.3c FAIL" || record "G09.3c PASS"

# ---------------------------------------------------------------------------
# (f) proguard wildcard keep
# ---------------------------------------------------------------------------
f_viol="$TMP/f"; : > "$f_viol"
while IFS= read -r p; do
  # normalize whitespace then match the exact wildcard keep
  grep -nE '^[[:space:]]*-keep[[:space:]]+class[[:space:]]+\*\*[[:space:]]*\{[[:space:]]*\*[[:space:]]*;[[:space:]]*\}' "$p" 2>/dev/null | while IFS= read -r line; do
    printf '%s:%s: forbidden -keep class ** { *; }\n' "$p" "${line%%:*}"; done >> "$f_viol"
done < "$TMP/proguard"
[ -s "$f_viol" ] && collect "$f_viol" && record "G09.3f FAIL" || record "G09.3f PASS"

# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------
echo "G09.3 check-source: scanned $KT_COUNT Kotlin files, $MF_COUNT source manifests, $(wc -l < "$TMP/proguard" | tr -d ' ') proguard files"
for s in "${STATUS[@]}"; do echo "  [$s]"; done | sort

if [ -s "$TMP/violations.txt" ]; then
  echo ""
  echo "VIOLATIONS:"
  sort -u "$TMP/violations.txt"
  exit 1
fi
echo "check-source: ALL CHECKS PASS"
exit 0
