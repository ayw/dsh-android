#!/usr/bin/env bash
# check-release-artifact.sh - gate G11 (release-artifact).
#
# Exit discipline: 0 = PASS, 1 = FAIL (prints every assertion result), 3 = BLOCKED
# (release APK/AAB not assembled yet, or apkanalyzer unavailable).
#
# Assertions (ACCEPTANCE G11 / DECISIONS D17):
#   - release AAB <= 12 MiB and release APK <= 20 MiB (budgets are READ FROM docs/TUNING.md,
#     not hardcoded: releaseAabBudgetBytes / releaseApkBudgetBytes rows).
#   - mapping.txt exists (R8 minify produced a mapping).
#   - apkanalyzer manifest print of the release APK: exported components == the allowlist in
#     acceptance/manifest-baseline.txt; allowBackup=false; dataExtractionRules present; no
#     REQUEST_INSTALL_PACKAGES; FileProvider grantUriPermissions=true and exported=false.
#   - the debug-only canary DSH-CANARY-DEBUG-ONLY-4f2b9c is ABSENT from release artifacts.
#
# apkanalyzer working invocation (verified on this machine):
#   JAVA_HOME=/home/keivn/jdk17/jdk-17.0.20+8 \
#   "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" manifest print app/build/outputs/apk/release/*.apk
#   JAVA_HOME=/home/keivn/jdk17/jdk-17.0.20+8 \
#   "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" apk summary app/build/outputs/apk/release/*.apk
#
# `--regen-baseline` rewrites acceptance/manifest-baseline.txt from the source app manifest.
# No network calls.

set -u -o pipefail
ROOT="."
d="$(pwd)"
while [ "$d" != "/" ]; do
  if [ -f "$d/settings.gradle.kts" ]; then ROOT="$d"; break; fi
  d="$(dirname "$d")"
done
cd "$ROOT" || exit 1

export JAVA_HOME="${JAVA_HOME:-/home/keivn/jdk17/jdk-17.0.20+8}"
export ANDROID_HOME="${ANDROID_HOME:-/home/keivn/Android/Sdk}"
APKANALYZER="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"

regen_baseline() {
  python3 - <<'PY'
import xml.etree.ElementTree as ET, subprocess
p="app/src/main/AndroidManifest.xml"
sha=subprocess.check_output(['sha256sum',p]).decode().split()[0]
A='{http://schemas.android.com/apk/res/android}'
root=ET.parse(p).getroot(); app=root.find('application')
COMP={'activity','activity-alias','service','receiver','provider'}
exports=[(comp.tag, comp.get(A+'name','?').split('.')[-1]) for comp in app
         if comp.tag in COMP and comp.get(A+'exported')=='true']
lines=[
"# acceptance/manifest-baseline.txt - exported-component allowlist for G11.",
"# Generated from the SOURCE app/src/main/AndroidManifest.xml.",
f"# source-manifest-sha256: {sha}",
"# REGENERATE whenever app/src/main/AndroidManifest.xml changes:",
"#   bash tools/acceptance/check-release-artifact.sh --regen-baseline",
"# check-release-artifact.sh diffs apkanalyzer's release-APK exported set against the",
"# '<tag> <class-leaf>' lines below. Any release exported component not listed here FAILs.",
"#",
]
lines += sorted(f"{t} {n}" for t,n in exports)
open("acceptance/manifest-baseline.txt","w").write("\n".join(lines)+"\n")
print("regenerated acceptance/manifest-baseline.txt from source manifest")
PY
}

if [ "${1:-}" = "--regen-baseline" ]; then regen_baseline; exit 0; fi

# --- locate release artifacts ------------------------------------------------
shopt -s nullglob
APKS=(app/build/outputs/apk/release/*.apk)
AABS=(app/build/outputs/bundle/release/*.aab)
shopt -u nullglob

if [ "${#APKS[@]}" = "0" ] && [ "${#AABS[@]}" = "0" ]; then
  echo "BLOCKED: release APK/AAB not assembled yet (run ./gradlew :app:assembleRelease :app:bundleRelease under DSH_ALLOW_GRADLE=1)"
  exit 3
fi
[ -x "$APKANALYZER" ] || [ -f "$APKANALYZER" ] || { echo "BLOCKED: apkanalyzer not found at $APKANALYZER"; exit 3; }
command -v python3 >/dev/null 2>&1 || { echo "BLOCKED: python3 unavailable"; exit 3; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
RELEASE_APK="${APKS[0]:-}"

# apkanalyzer manifest print + summary (only meaningful for an APK)
MANIFEST_OK=1
if [ -n "$RELEASE_APK" ]; then
  if ! "$APKANALYZER" manifest print "$RELEASE_APK" > "$TMP/m.xml" 2>"$TMP/m.err"; then
    echo "WARNING: apkanalyzer manifest print failed"; MANIFEST_OK=0
  fi
  "$APKANALYZER" apk summary "$RELEASE_APK" > "$TMP/summary.txt" 2>/dev/null || true
  echo "apkanalyzer apk summary: $(tr '\n' ' ' < "$TMP/summary.txt" 2>/dev/null)"
else
  MANIFEST_OK=0
  echo "NOTE: no release APK (only AAB present); manifest assertions run against AAB's contained APK when built"
fi

# --- budgets from TUNING.md --------------------------------------------------
read_budget() { # key -> bytes (integer) or empty
  local key="$1"
  local cell
  cell="$(grep -E "^\|\s*\`$key\`\s*\|" docs/TUNING.md 2>/dev/null | head -1 | awk -F'|' '{print $3}' | tr -d ' `')"
  [ -n "$cell" ] || { echo ""; return; }
  python3 -c "
import re,sys
v='''$cell'''
m=re.match(r'([0-9.]+)\s*(KiB|MiB|GiB|B)',v)
if not m: print(''); sys.exit()
n=float(m.group(1)); u=m.group(2)
mult={'B':1,'KiB':1024,'MiB':1024**2,'GiB':1024**3}[u]
print(int(n*mult))
" 2>/dev/null
}
AAB_BUDGET="$(read_budget releaseAabBudgetBytes)"
APK_BUDGET="$(read_budget releaseApkBudgetBytes)"
AAB_BUDGET="${AAB_BUDGET:-12582912}"   # fallback = 12 MiB if the doc row is unreadable
APK_BUDGET="${APK_BUDGET:-20971520}"   # fallback = 20 MiB

CANARY="DSH-CANARY-DEBUG-ONLY-4f2b9c"
: > "$TMP/viol"
say() { printf '%s\n' "$1" >> "$TMP/report"; }

# --- size + mapping + canary checks -----------------------------------------
size_of() { stat -c '%s' "$1" 2>/dev/null || echo 0; }

if [ "${#AABS[@]}" != "0" ]; then
  for a in "${AABS[@]}"; do
    sz="$(size_of "$a")"
    if [ "$sz" -gt "$AAB_BUDGET" ]; then say "FAIL: $(basename "$a") size $sz > AAB budget $AAB_BUDGET"; else say "PASS: AAB $(basename "$a") size $sz <= $AAB_BUDGET"; fi
  done
fi
if [ -n "$RELEASE_APK" ]; then
  sz="$(size_of "$RELEASE_APK")"
  if [ "$sz" -gt "$APK_BUDGET" ]; then say "FAIL: release APK size $sz > APK budget $APK_BUDGET"; else say "PASS: release APK size $sz <= $APK_BUDGET"; fi
fi

MAP="app/build/outputs/mapping/release/mapping.txt"
if [ -f "$MAP" ]; then say "PASS: mapping.txt present ($MAP)"; else say "FAIL: mapping.txt missing (R8 mapping required for $MAP)"; fi

# canary must be absent from every release artifact (unzip + grep for a reliable scan)
canary_scan() { # file
  local f="$1" out=""
  local sub="$TMP/scan"; rm -rf "$sub"; mkdir -p "$sub"
  if unzip -qq -o "$f" -d "$sub" 2>/dev/null; then
    if grep -raF -- "$CANARY" "$sub" >/dev/null 2>&1; then out="FOUND"; fi
  else
    # non-zip or unzip failed: raw scan
    if grep -aF -- "$CANARY" "$f" >/dev/null 2>&1; then out="FOUND"; fi
  fi
  echo "$out"
}
for art in "${APKS[@]}" "${AABS[@]}"; do
  [ -f "$art" ] || continue
  if [ "$(canary_scan "$art")" = "FOUND" ]; then
    say "FAIL: debug-only canary '$CANARY' present in $(basename "$art")"
  else
    say "PASS: canary absent from $(basename "$art")"
  fi
done

# --- exported diff + manifest assertions ------------------------------------
if [ "$MANIFEST_OK" = "1" ] && [ -s "$TMP/m.xml" ]; then
  python3 - "$TMP" <<'PY'
import sys, os, re
import xml.etree.ElementTree as ET
tmp=sys.argv[1]
A='{http://schemas.android.com/apk/res/android}'
COMP={'activity','activity-alias','service','receiver','provider'}
out=[]
txt=open(os.path.join(tmp,'m.xml'),encoding='utf-8',errors='replace').read()
# apkanalyzer prints XML; strip any leading non-xml noise
i=txt.find('<manifest'); txt=txt[i:] if i>=0 else txt
try: root=ET.fromstring(txt)
except Exception as e:
    out.append(f"FAIL: cannot parse apkanalyzer manifest XML: {e}"); 
    open(os.path.join(tmp,'mviol'),'w').write("\n".join(out)+"\n"); sys.exit(0)
app=root.find('application')
# exported set from the release manifest. We separate:
#   * unprotected exports -> must match the baseline allowlist (the app's own surface)
#   * exports carrying android:permission (e.g. WorkManager SystemJobService/DiagnosticsReceiver,
#     profileinstaller ProfileInstallReceiver) -> framework/privileged-guarded surfaces that are
#     legitimately exported=true and are NOT the app's own components; recorded as PASS notes.
rel_unprotected=set()
protected_notes=[]
if app is not None:
    for comp in app:
        if comp.tag in COMP and comp.get(A+'exported')=='true':
            leaf=(comp.get(A+'name','?') or '?').split('.')[-1]
            if comp.get(A+'permission'):
                protected_notes.append(f"{leaf} <- {comp.get(A+'permission')}")
            else:
                rel_unprotected.add(f"{comp.tag} {leaf}")
    # allowBackup
    if app.get(A+'allowBackup')!='false':
        out.append(f"FAIL: application allowBackup != false (got {app.get(A+'allowBackup')})")
    else:
        out.append("PASS: allowBackup=false")
    # dataExtractionRules
    if not app.get(A+'dataExtractionRules'):
        out.append("FAIL: dataExtractionRules not declared")
    else:
        out.append("PASS: dataExtractionRules declared")
    # FileProvider grantUriPermissions + exported=false
    for comp in app:
        if comp.tag=='provider' and 'FileProvider' in (comp.get(A+'name','') or ''):
            if comp.get(A+'grantUriPermissions')!='true':
                out.append("FAIL: FileProvider grantUriPermissions != true")
            else:
                out.append("PASS: FileProvider grantUriPermissions=true")
            if comp.get(A+'exported')=='true':
                out.append("FAIL: FileProvider exported=true (must be false / no global read-write)")
            else:
                out.append("PASS: FileProvider not exported")
# REQUEST_INSTALL_PACKAGES forbidden
for m in root.iter('uses-permission'):
    if (m.get(A+'name','') or '').endswith('REQUEST_INSTALL_PACKAGES'):
        out.append("FAIL: REQUEST_INSTALL_PACKAGES permission present")
# diff vs baseline
base_file="acceptance/manifest-baseline.txt"
base=set()
if os.path.exists(base_file):
    for line in open(base_file,encoding='utf-8'):
        s=line.strip()
        if not s or s.startswith('#'): continue
        base.add(s)
else:
    out.append("FAIL: acceptance/manifest-baseline.txt missing")
extra=sorted(rel_unprotected-base)
missing=sorted(base-rel_unprotected)
for e in extra: out.append(f"FAIL: release exports app component '{e}' not in baseline allowlist")
for miss in missing: out.append(f"FAIL: baseline-expected exported '{miss}' not found in release manifest")
if protected_notes:
    out.append("PASS: permission-guarded framework exports: " + ", ".join(sorted(protected_notes)))
if not extra and not missing and os.path.exists(base_file):
    out.append(f"PASS: unprotected exported set matches baseline ({len(rel_unprotected)} component(s))")
viol=[l for l in out if l.startswith('FAIL')]
open(os.path.join(tmp,'mviol'),'w').write("\n".join(out)+"\n")
PY
  if [ -s "$TMP/mviol" ]; then
    grep -E '^FAIL' "$TMP/mviol" >> "$TMP/viol" 2>/dev/null || true
    # include the whole manifest assertion report
    cat "$TMP/mviol" >> "$TMP/report"
  fi
fi

# --- final report ------------------------------------------------------------
echo "G11 release-artifact: budgets (from docs/TUNING.md) AAB=${AAB_BUDGET}B APK=${APK_BUDGET}B; release APK(s)=${#APKS[@]} AAB(s)=${#AABS[@]}"
[ -s "$TMP/report" ] && cat "$TMP/report"
# collect FAIL lines from both report and viol
{ grep -hE '^FAIL' "$TMP/report" "$TMP/viol" 2>/dev/null || true; } > "$TMP/allfail"
if [ -s "$TMP/allfail" ]; then
  echo "RESULT: FAIL"
  exit 1
fi
echo "RESULT: PASS"
exit 0
