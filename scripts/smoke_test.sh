#!/bin/sh
# smoke_test.sh — 安装 + 启动 + 崩溃扫描 + 哈希对照（在 Android shell / Root / Shizuku 中运行）
#
# 用法：
#   sh smoke_test.sh <包名> <APK绝对路径> [Activity短名，默认 .MainActivity]
# 示例：
#   sh smoke_test.sh com.darkveil.app /storage/emulated/0/WORK/01-开发项目/护眼/dark-veil/app/build/outputs/apk/release/app-release.apk
#
set -u

if [ -z "${1:-}" ] || [ -z "${2:-}" ]; then
  echo "用法: sh smoke_test.sh <包名> <APK路径> [Activity短名]"
  exit 2
fi

PKG="$1"
APK="$2"
ACT="${3:-.MainActivity}"
TMP=/data/local/tmp/smoke_test.apk

echo "== 1. 复制并安装 =="
cp "$APK" "$TMP" || { echo "[ERR] 复制到 /data/local/tmp 失败"; exit 2; }
pm install -r "$TMP" || { echo "[ERR] 安装失败"; exit 2; }

echo "== 2. 启动 =="
am start -n "$PKG/$ACT" || echo "[WARN] am start 非 0（确认 $PKG/$ACT 正确、Activity 含 LAUNCHER）"
sleep 2

echo "== 3. 进程存活 =="
if pidof "$PKG" >/dev/null 2>&1; then
  echo "[OK] pidof: $(pidof "$PKG")"
else
  echo "[WARN] 未找到进程（可能在冷启动中，或包名/Activity 不对）"
fi

echo "== 4. 崩溃扫描（logcat 最近 300 行）=="
if logcat -d -t 300 | grep -E "FATAL EXCEPTION|AndroidRuntime.*(FATAL|Error)"; then
  echo "[WARN] 发现 FATAL 相关日志（见上）"
else
  echo "[OK] 无 FATAL"
fi

echo "== 5. sha256 对照（设备拷贝 vs 安装源）=="
D_SHA=$(sha256sum "$TMP" | awk '{print $1}')
S_SHA=$(sha256sum "$APK" | awk '{print $1}')
echo "device : $D_SHA"
echo "source : $S_SHA"
if [ "$D_SHA" = "$S_SHA" ]; then
  echo "[OK] 两值一致"
else
  echo "[WARN] 两值不一致"
fi

echo "== 完成 =="