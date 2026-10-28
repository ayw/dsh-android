#!/usr/bin/env bash
# CI 唯一入口：本机与 GitHub Actions 跑同一条命令，避免“CI 绿、本机红”的两套真相。
# 用法：tools/ci/run-ci.sh [--release] [--gates G05 G09 ...]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "$HERE/env.sh"
cd "$DSH_PROJECT_ROOT"

RELEASE=false
GATE_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) RELEASE=true; shift ;;
    --gates) shift; GATE_ARGS=("$@"); break ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "PRE-FAIL: $*" >&2; exit 1; }

# ---- 前置检查（失败即整套失败，不做部分执行，见 ACCEPTANCE §1 G01）----
[[ -x "$JAVA_HOME/bin/java" ]] || fail "JAVA_HOME=$JAVA_HOME 下没有 java"
"$JAVA_HOME/bin/java" -version || fail "java -version 执行失败"
[[ -d "$ANDROID_HOME/platforms" ]] || fail "ANDROID_HOME=$ANDROID_HOME 无 platforms"
command -v node >/dev/null || fail "缺少 node（契约录制与门禁解析需要）"

echo "== toolchain =="
"$JAVA_HOME/bin/java" -version 2>&1 | sed 's/^/  /'
echo "  android sdk: $ANDROID_HOME"
echo "  project root: $DSH_PROJECT_ROOT"

# ---- 构建 ----
GRADLE_ARGS=(--no-daemon --console=plain -x lint)
echo "== gradle build =="
./gradlew build "${GRADLE_ARGS[@]}"

if [[ "$RELEASE" == true ]]; then
  echo "== release artifacts =="
  ./gradlew :app:bundleRelease :app:assembleRelease "${GRADLE_ARGS[@]}"
fi

# ---- 门禁 ----
if [[ -x tools/acceptance/run-gates.sh || -f tools/acceptance/run-gates.sh ]]; then
  echo "== acceptance gates =="
  bash tools/acceptance/run-gates.sh "${GATE_ARGS[@]}"
else
  echo "== acceptance gates: MISSING ==" >&2
  echo "   tools/acceptance/run-gates.sh 不存在；CI 不能在没有门禁的情况下判绿。" >&2
  exit 1
fi
