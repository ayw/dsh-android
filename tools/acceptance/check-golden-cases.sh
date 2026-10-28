#!/usr/bin/env bash
# check-golden-cases.sh - support for gates G05 (projector-goldens) and G13 (coverage-floor).
#
# Exit discipline: 0 = PASS, 1 = FAIL (bidirectional mismatch), 3 = BLOCKED
# (no acceptance/golden-cases-*.json yet, OR the referenced test sources do not exist
#  yet -> cannot judge an unfinished tree).
#
# Contract (written by another engineer): every acceptance/golden-cases-<name>.json has
#   {"gate":"G05","module":":core:domain","cases":[{"id":"<test fun name>","class":"<fqcn>","requires":"..."}]}
# The harness aggregates EVERY acceptance/golden-cases-*.json.
#
# Bidirectional checks:
#   forward: each declared case id must appear as a test function in the owning module's
#            test sources (*/src/test/**/*.kt or */src/androidTest/**/*.kt).
#   reverse: for every class a manifest already names, each @Test function in that class's
#            source must be listed in some manifest; unlisted ones are "extra". Bounding the
#            reverse pass to classes the manifest names keeps ordinary unit tests elsewhere out.
#
# No network calls; nothing written outside the tree.

set -u
ROOT="."
d="$(pwd)"
while [ "$d" != "/" ]; do
  if [ -f "$d/settings.gradle.kts" ]; then ROOT="$d"; break; fi
  d="$(dirname "$d")"
done
cd "$ROOT" || exit 1
command -v jq >/dev/null 2>&1 || { echo "BLOCKED: jq unavailable, cannot read golden-case manifests"; exit 3; }
command -v python3 >/dev/null 2>&1 || { echo "BLOCKED: python3 unavailable"; exit 3; }

shopt -s nullglob
MANIFESTS=(acceptance/golden-cases-*.json)
shopt -u nullglob
if [ "${#MANIFESTS[@]}" = "0" ]; then
  echo "BLOCKED: no acceptance/golden-cases-*.json manifests yet (G05/G13 case check cannot run)"
  exit 3
fi

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
: > "$TMP/cases.tsv"      # gate \t module \t id \t class \t source-manifest
for mf in "${MANIFESTS[@]}"; do
  if ! jq -e . "$mf" >/dev/null 2>&1; then
    echo "FAIL: invalid JSON in $mf"; exit 1
  fi
  jq -r --arg mf "$mf" '
      (.gate // "?") as $g | (.module // "?") as $mod |
      (.cases // [])[] |
      [ $g, $mod, (.id // ""), (.class // ""), $mf ] | @tsv
    ' "$mf" >> "$TMP/cases.tsv"
done

python3 - "$TMP" <<'PY' > "$TMP/py.out" 2>"$TMP/py.err"
import sys, os, re
tmp=sys.argv[1]
rows=[]
p=os.path.join(tmp,'cases.tsv')
if os.path.exists(p):
    for line in open(p,encoding='utf-8'):
        parts=line.rstrip('\n').split('\t')
        if len(parts)>=4 and any(parts[:4]): rows.append(parts)

def module_testdirs(modpath):
    rel=modpath.lstrip(':').replace(':','/')
    return [os.path.join(rel,sub) for sub in ("src/test","src/androidTest")
            if os.path.isdir(os.path.join(rel,sub))]

def kt_files(dirs):
    fs=[]
    for d in dirs:
        for r,_,fl in os.walk(d):
            for fn in fl:
                if fn.endswith('.kt'): fs.append(os.path.join(r,fn))
    return fs

def class_simple(fqcn): return fqcn.split('.')[-1] if fqcn else ''

mod_cache={}
def module_files(mod):
    if mod not in mod_cache: mod_cache[mod]=kt_files(module_testdirs(mod))
    return mod_cache[mod]

declared=set(); ids_by_mod={}; classes=set(); mods_in_play=set()
for r in rows:
    gate,mod,cid,cls = r[0],r[1],r[2],(r[3] if len(r)>3 else '')
    mods_in_play.add(mod)
    declared.add((mod,cls,cid))
    ids_by_mod.setdefault(mod,set()).add(cid)
    if cls: classes.add((mod,cls))

# If NO referenced module has any test source directory yet -> the code under inspection
# does not exist -> BLOCKED, not FAIL (an unfinished tree must not read as accepted).
testdirs_present=any(module_files(m) for m in mods_in_play)

viol=[]
for (mod,cls,cid) in sorted(declared):
    if not cid: continue
    files=module_files(mod)
    if not files:
        continue  # covered by the global BLOCKED decision below
    found=False
    for f in files:
        txt=open(f,encoding='utf-8',errors='replace').read()
        if (re.search(r'fun\s+'+re.escape(cid)+r'\s*[({<]', txt)
            or re.search(r'fun\s+`'+re.escape(cid)+r'`', txt)
            or ('"'+cid+'"') in txt):
            found=True; break
    if not found:
        viol.append(f"MISSING: golden id '{cid}' (class {cls or '?'}, module {mod}) not found in test sources")

if testdirs_present:
    for (mod,fqcn) in sorted(classes):
        simple=class_simple(fqcn); ids=ids_by_mod.get(mod,set())
        for f in module_files(mod):
            if os.path.basename(f)[:-3]!=simple: continue
            txt=open(f,encoding='utf-8',errors='replace').read()
            for m in re.finditer(r'@Test[\s\S]{0,220}?fun\s+(?:`([^`]+)`|([A-Za-z0-9_]+))\s*[({<]', txt):
                name=m.group(1) or m.group(2)
                if name and name not in ids:
                    viol.append(f"EXTRA: test '{name}' in {mod} class {fqcn} ({f}) is not listed in any golden-cases manifest")

nmods=len(set(r[1] for r in rows))
print(f"aggregated {len(rows)} declared cases across {nmods} modules from {len(set(r[4] for r in rows if len(r)>4))} manifest(s)")
open(os.path.join(tmp,'viol'),'w').write("\n".join(list(dict.fromkeys(viol)))+("\n" if viol else ""))
if not testdirs_present:
    sys.exit(3)
sys.exit(1 if viol else 0)
PY
rc=$?
if [ "$rc" = "2" ] || [ "$rc" = "127" ] || [ "$rc" -gt 3 ] 2>/dev/null; then
  echo "FAIL: check-golden-cases internal error (python exit $rc):"
  cat "$TMP/py.err"
  exit 1
fi
cat "$TMP/py.out"
if [ "$rc" = "3" ]; then
  echo "BLOCKED: referenced test sources do not exist yet (no */src/test or src/androidTest for the manifest modules)"
  exit 3
fi
if [ -s "$TMP/viol" ]; then
  echo "MISMATCHES:"
  cat "$TMP/viol"
  exit 1
fi
echo "check-golden-cases: manifests and test sources agree"
exit 0
