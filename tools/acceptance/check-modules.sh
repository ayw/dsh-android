#!/usr/bin/env bash
# check-modules.sh - gate G17 (dependency-fence).
#
# Exit discipline: 0 = PASS, 1 = FAIL (prints the resolved dependency graph and every
# violation as file:line/module), 3 = BLOCKED (no module build files to parse yet).
#
# Fence rules implemented (ACCEPTANCE G17, refined for :app per task brief; see
# gates.json G17 notes for the doc deviation):
#   1. Every feature/* module may declare project() deps ONLY on
#      :core:model, :core:domain, :core:designsystem (never :core:protocol /
#      :core:network / :core:database, and never impl modules).
#   2. :app is the composition root: it MAY declare impl project deps, but it may only
#      IMPORT ai.deepseek.dsh.android.(protocol|database|network) inside files under
#      app/src/main/kotlin/**/di/** or named DshApplication.kt / AppScope.kt. Enforced by
#      scanning :app sources for those imports outside the allowlisted files.
#   3. :core:model declares no Android / Room / OkHttp dependency (pure JVM).
#   4. :core:domain likewise (no Room / OkHttp / Android).
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
command -v python3 >/dev/null 2>&1 || { echo "BLOCKED: python3 unavailable"; exit 3; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
find . -type f -name 'build.gradle.kts' -not -path '*/build/*' 2>/dev/null | sort > "$TMP/builds"
if [ ! -s "$TMP/builds" ]; then echo "BLOCKED: no build.gradle.kts found (G17 cannot run)"; exit 3; fi
# Require at least the settings module list to exist
[ -f settings.gradle.kts ] || { echo "BLOCKED: settings.gradle.kts missing"; exit 3; }

python3 - "$TMP" <<'PY' > "$TMP/py.out" 2>"$TMP/py.err"
import sys, os, re
tmp=sys.argv[1]
builds=[l.strip() for l in open(os.path.join(tmp,'builds')) if l.strip()]
if not builds:
    print("BLOCKED: no module build files"); sys.exit(3)

def read(p):
    try: return open(p,encoding='utf-8',errors='replace').read()
    except Exception: return ""

# Parse (config, projectDep, lineno) triples. Config = the gradle configuration name
# (implementation / api / ksp / testImplementation / androidTestImplementation / ...).
decl_re=re.compile(r'\b([A-Za-z][A-Za-z0-9_]*)\s*\(\s*(?:platform\()?\s*project\(\s*"([^"]+)"')
PROD_CFG={'implementation','api','compileOnly','runtimeOnly','ksp','kapt'}
TEST_CFG={'testimplementation','testapi','testcompileonly','androidtestimplementation','androidtestapi','debugimplementation','debugapi'}
def cfg_kind(cfg):
    c=cfg.lower()
    if c in TEST_CFG: return 'test'
    if c in PROD_CFG: return 'prod'
    return 'other'

deps={}      # build -> sorted list of project paths (for the graph)
decls={}     # build -> list of (kind, path, lineno)
for b in builds:
    txt=read(b)
    pset=set(); dl=[]
    for m in decl_re.finditer(txt):
        cfg,path=m.group(1),m.group(2)
        ln=txt[:m.start()].count('\n')+1
        pset.add(path); dl.append((cfg_kind(cfg),path,ln))
    deps[b]=sorted(pset); decls[b]=dl

graph=["RESOLVED DEPENDENCY GRAPH (project() deps per build file):"]
for b in sorted(deps):
    graph.append(f"  {b}: {', '.join(deps[b]) if deps[b] else '(none)'}")
print("\n".join(graph))

viol=[]
ALLOWED_FEATURE_PROD={":core:model",":core:domain",":core:designsystem"}
ALLOWED_FEATURE_TEST=ALLOWED_FEATURE_PROD | {":core:testing"}
IMPL_TRIO={":core:protocol",":core:network",":core:database"}

# (1) feature fence: impl trio never allowed; production deps restricted to model/domain/designsystem;
#     test deps may additionally use the :core:testing harness.
feature_builds=[b for b in deps if re.search(r'(^|/)feature/[^/]+/build\.gradle\.kts$', b)]
for b in sorted(feature_builds):
    for kind,path,ln in decls[b]:
        if path in IMPL_TRIO:
            viol.append(f"{b}:{ln}: feature module depends on impl module '{path}' (never allowed)")
        elif kind=='prod' and path not in ALLOWED_FEATURE_PROD:
            viol.append(f"{b}:{ln}: feature production dep '{path}' outside {sorted(ALLOWED_FEATURE_PROD)}")
        elif kind=='test' and path not in ALLOWED_FEATURE_TEST:
            viol.append(f"{b}:{ln}: feature test dep '{path}' outside {sorted(ALLOWED_FEATURE_TEST)}")

# (3)/(4) core:model and core:domain must be pure JVM (no android plugin / room / okhttp / androidx)
FORBID_DEP=re.compile(r'(room|okhttp|androidx|core-ktx|compose|navigation)', re.I)
for name in ("core/model","core/domain"):
    hit=[b for b in deps if b.endswith(f"/{name}/build.gradle.kts") or b==f"{name}/build.gradle.kts"]
    if not hit:
        viol.append(f"{name}: build.gradle.kts not found"); continue
    b=hit[0]; txt=read(b)
    if 'libs.plugins.android' in txt or re.search(r'\bandroid\s*\{', txt):
        viol.append(f"{b}:1: '{name}' must not apply the Android plugin (pure JVM module)")
    for m in re.finditer(r'^.*$', txt, re.M):
        line=m.group(0)
        if re.match(r'\s*(implementation|api|ksp|kapt|compileOnly|runtimeOnly)\b', line) and FORBID_DEP.search(line):
            ln=txt[:m.start()].count('\n')+1
            viol.append(f"{b}:{ln}: '{name}' declares an Android/Room/OkHttp dependency: {line.strip()}")

# (2) :app import fence
APP_SRC=re.compile(r'^\s*import\s+ai\.deepseek\.dsh\.android\.(protocol|database|network)\b')
app_files=[]
for r,_,fs in os.walk("app/src"):
    if "/build/" in r.replace(os.sep,'/'): continue
    for fn in fs:
        if fn.endswith(".kt"): app_files.append(os.path.join(r,fn))
app_scanned=len(app_files)
for f in sorted(app_files):
    fnorm=f.replace(os.sep,'/')
    allowed = ('/di/' in fnorm) or os.path.basename(f) in ('DshApplication.kt','AppScope.kt')
    if allowed: continue
    for i,line in enumerate(open(f,encoding='utf-8',errors='replace'),1):
        if APP_SRC.match(line):
            viol.append(f"{f}:{i}: :app imports an impl module outside di/, DshApplication.kt or AppScope.kt: {line.strip()}")

print(f"(:app Kotlin sources scanned: {app_scanned})")
print(f"(feature build files scanned: {len(feature_builds)})")
open(os.path.join(tmp,'viol'),'w').write("\n".join(list(dict.fromkeys(viol)))+("\n" if viol else ""))
PY
rc=$?
if [ "$rc" != "0" ]; then
  echo "FAIL: check-modules internal error (python exit $rc):"
  cat "$TMP/py.err"
  exit 1
fi
cat "$TMP/py.out"
if [ -s "$TMP/viol" ]; then
  echo "VIOLATIONS:"
  cat "$TMP/viol"
  exit 1
fi
echo "check-modules: ALL CHECKS PASS"
exit 0
