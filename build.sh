#!/usr/bin/env bash
# 疾点 · 设备端构建脚本
#
# 两套环境自动探测（App 更新可能把 Debian 环境换掉，这里不再写死）：
#   1) Debian proot：JDK17 / Android SDK / Gradle 都在固定路径（旧路径，兼容保留）
#   2) Termux 原生（Bionic）：$HOME/opt/termux-sys 里的 JDK17 + $HOME/build/gradle-8.11.1
# 产物与日志位置一致，日志默认 $HOME/<项目名>-build.log。
set -u

PROJ_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_NAME="$(basename "$PROJ_DIR")"

if [ -d /usr/lib/jvm/java-17-openjdk-arm64 ] && [ -d /storage/emulated/0/WORK/02-工具链/toolchain/gradle-8.11.1 ]; then
  # ── Debian proot ──────────────────────────────────────────────
  export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
  export ANDROID_HOME="${ANDROID_HOME:-/storage/emulated/0/WORK/02-工具链/android-sdk}"
  GRADLE_BIN="${GRADLE_BIN:-/storage/emulated/0/WORK/02-工具链/toolchain/gradle-8.11.1/bin/gradle}"
  AAPT2="${AAPT2:-/root/aapt2-bin/aapt2}"
  GRADLE_USER_HOME="${GRADLE_USER_HOME:-/storage/emulated/0/WORK/02-工具链/android-user-home}"
  LOG="${LOG:-/root/$PROJ_NAME-build.log}"
  TMP="${TMP:-/root/tmp}"
else
  # ── Termux 原生（Bionic sysroot） ─────────────────────────────
  SYS="$HOME/opt/termux-sys"
  export JAVA_HOME="${JAVA_HOME:-$SYS/lib/jvm/java-17-openjdk}"
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/opt/android-sdk}"
  GRADLE_BIN="${GRADLE_BIN:-$HOME/build/gradle-8.11.1/bin/gradle}"
  AAPT2="${AAPT2:-$HOME/opt/android-sdk/build-tools/35.0.0/aapt2}"
  GRADLE_USER_HOME="${GRADLE_USER_HOME:-/storage/emulated/0/WORK/02-工具链/android-user-home}"
  LOG="${LOG:-$HOME/$PROJ_NAME-build.log}"
  TMP="${TMP:-$HOME/tmp}"
  export LD_LIBRARY_PATH="$SYS/lib:${PREFIX:-$HOME/../usr}/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  export PATH="$JAVA_HOME/bin:$SYS/bin:${PREFIX:-$HOME/../usr}/bin:/system/bin:$PATH"
fi

export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME
export GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.native=false -Djava.io.tmpdir=$TMP -Dfile.encoding=UTF-8"
export JAVA_OPTS="-Djdk.lang.Process.launchMechanism=FORK ${JAVA_OPTS:-}"
mkdir -p "$TMP" "$(dirname "$LOG")"

if [ $# -eq 0 ]; then
  set -- :app:assembleRelease
fi

cd "$PROJ_DIR" || exit 1
bash "$GRADLE_BIN" "$@" --no-daemon \
  -Pandroid.aapt2FromMavenOverride="$AAPT2" \
  2>&1 | tee "$LOG"

STATUS=${PIPESTATUS[0]}
echo "BUILD EXIT=$STATUS"
exit "$STATUS"
