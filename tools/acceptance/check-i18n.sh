#!/usr/bin/env bash
# check-i18n.sh - gate G14 (i18n-parity). DECISIONS D15: default values/ is Simplified
# Chinese, values-en/ is English, key sets must be exactly equal; no hardcoded
# user-visible text in Composables.
#
# Exit discipline: 0 = PASS, 1 = FAIL (lists every finding as file:line or file:key),
# 3 = BLOCKED (no string resources and no Compose sources yet -> nothing to judge).
#
# Checks:
#   1. Per module: the key set of values/strings*.xml equals that of values-en/strings*.xml
#      (keys = string / string-array / plurals 'name' attributes). A module that has one
#      locale dir but not the other is reported.
#   2. No empty string values (<string name="x"/> or whitespace-only body).
#   3. No duplicate keys within a locale's resource set.
#   4. Compose source scan: Text("...") / Text(text = "...") with a literal string,
#      outside previews (*Preview* files) and test source sets.
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
command -v python3 >/dev/null 2>&1 || { echo "BLOCKED: python3 unavailable, cannot parse i18n XML"; exit 3; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

# Nothing to inspect?
mapfile -t STRXML < <(find . -type f -name 'strings*.xml' -path '*/src/*/res/*' -not -path '*/build/*' 2>/dev/null | sort)
mapfile -t KTS < <(find . -type f -name '*.kt' -path '*/src/*' -not -path '*/build/*' 2>/dev/null | sort)
if [ "${#STRXML[@]}" = "0" ] && [ "${#KTS[@]}" = "0" ]; then
  echo "BLOCKED: no strings*.xml and no Kotlin sources yet (G14 cannot run)"
  exit 3
fi

printf '%s\n' "${STRXML[@]}" > "$TMP/strings"
printf '%s\n' "${KTS[@]}" > "$TMP/kt"

python3 - "$TMP" <<'PY' > "$TMP/py.err" 2>&1
import sys, os, re
import xml.etree.ElementTree as ET
tmp=sys.argv[1]
strings=[l.strip() for l in open(os.path.join(tmp,'strings')) if l.strip()]
kts=[l.strip() for l in open(os.path.join(tmp,'kt')) if l.strip()]
viol=[]

NAME_TAGS={'string','string-array','plurals'}
def keys_of(paths):
    # map key -> (file,lineno) ; returns (keydict, ordered list of (key,file,ln,empty))
    res={}
    order=[]
    for p in paths:
        try: root=ET.parse(p).getroot()
        except ET.ParseError as e:
            viol.append(f"{p}:1: XML parse error: {e}"); continue
        for el in root:
            tag=el.tag
            if tag not in NAME_TAGS: continue
            name=el.get('name')
            if not name: continue
            order.append((name,p))
            if name in res:
                viol.append(f"{p}: duplicate key '{name}' (also in {res[name][0]})")
            else:
                res[name]=(p,0)
            if tag=='string':
                txt=(el.text or '').strip()
                if txt=='':
                    viol.append(f"{p}: empty value for string '{name}'")
    return res

# group by module res-root: strip trailing '/values[-xx]/file'
from collections import defaultdict
mods=defaultdict(lambda: defaultdict(list))
for p in strings:
    m=re.search(r'(.*/src/[^/]+)/res/(values[^/]*)/([^/]+\.xml)$', p)
    if not m:
        # app module uses src/main/res ; library too
        continue
    modroot=m.group(1); localedir=m.group(2); mods[modroot][localedir].append(p)

for modroot, dirs in sorted(mods.items()):
    default=dirs.get('values', [])
    en=dirs.get('values-en', [])
    if default and not en:
        # values-en dir entirely absent -> all keys missing in en
        kd=keys_of(default)
        for k in kd:
            viol.append(f"{modroot}: key '{k}' present in values/ but values-en/ missing")
        continue
    if en and not default:
        kd=keys_of(en)
        for k in kd:
            viol.append(f"{modroot}: key '{k}' present in values-en/ but values/ missing")
        continue
    if default and en:
        kd=keys_of(default); ke=keys_of(en)
        for k in sorted(set(kd)-set(ke)): viol.append(f"{modroot}: key '{k}' missing from values-en/")
        for k in sorted(set(ke)-set(kd)): viol.append(f"{modroot}: key '{k}' only in values-en/ (not in values/)")
    # empty/dup checks still run on both even when one side is missing keys
    if default: keys_of(default)
    if en: keys_of(en)

# Compose Text(...) hardcoded literal scan
txt_re=re.compile(r'\bText\s*\(\s*(?:text\s*=\s*)?(".*?"|\$\{)')
for f in kts:
    if '/src/test/' in f or '/src/androidTest/' in f: continue
    if 'Preview' in os.path.basename(f): continue
    try: raw=open(f,encoding='utf-8',errors='replace').read()
    except Exception: continue
    for i,line in enumerate(raw.splitlines(),1):
        s=line.lstrip()
        if s.startswith('//') or s.startswith('*'): continue
        m=txt_re.search(line)
        if m and m.group(1).startswith('"') and m.group(1)!='""':
            viol.append(f"{f}:{i}: hardcoded user-visible text in Text(): {line.strip()[:80]}")

open(os.path.join(tmp,'viol'),'w').write("\n".join(list(dict.fromkeys(viol)))+("\n" if viol else ""))
open(os.path.join(tmp,'summary'),'w').write(f"scanned {len(strings)} strings files across {len(mods)} modules; {len(kts)} Kotlin files\n")
PY
py_rc=$?
if [ "$py_rc" != "0" ]; then
  echo "FAIL: check-i18n internal error (python exit $py_rc):"
  cat "$TMP/py.err"
  exit 1
fi

cat "$TMP/summary"
if [ -s "$TMP/viol" ]; then
  echo "VIOLATIONS:"
  cat "$TMP/viol"
  exit 1
fi
echo "check-i18n: ALL CHECKS PASS"
exit 0
