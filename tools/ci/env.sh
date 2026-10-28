# 本机/CI 共用的构建环境。所有脚本先 `source tools/ci/env.sh`，不依赖调用者的 shell 状态。
# 这些路径是本机事实；CI runner 上由同名环境变量覆盖（见 .github/workflows/ci.yml）。

export JAVA_HOME="${JAVA_HOME:-/home/keivn/jdk17/jdk-17.0.20+8}"
export ANDROID_HOME="${ANDROID_HOME:-/home/keivn/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

# Gradle 需要显式拿到 JDK 候选路径（本机 auto-detect 关闭，见 gradle.properties）。
export DSH_JDK17="${DSH_JDK17:-/home/keivn/jdk17/jdk-17.0.20+8}"
export DSH_JDK21="${DSH_JDK21:-/home/keivn/opt/jdk-21.0.12.1+1}"

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

# dsh-Remote 仓库位置：契约录制（G03）的前置。未设置时 G03 判 BLOCKED，不算通过。
export DSH_REMOTE_HOME="${DSH_REMOTE_HOME:-}"

# 工程根 = 本脚本上两级，供各脚本复用。
_DSH_ENV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export DSH_PROJECT_ROOT="$(cd "$_DSH_ENV_DIR/../.." && pwd)"
unset _DSH_ENV_DIR

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
