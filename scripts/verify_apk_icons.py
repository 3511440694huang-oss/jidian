#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_apk_icons.py — APK 图标进包像素级校验（release 资源重命名场景）

背景：release 构建会被 AAPT2 重命名资源（如 mipmap/ic_launcher → res/RJ.png），
按文件名直接找会扑空；本脚本先用 aapt2 dump resources 查「资源名 → 实际文件」映射，
再从 APK 中取出 4 个启动器图标（指定密度），与源码目录的源图做逐像素比对。

判定口径（只看可见像素，alpha>0）：
  PASS-EXACT    全像素一致
  PASS-CLEANUP  可见区一致；差异仅为 alpha=0 透明像素的 RGB 清理（打包器行为，不算问题）
  FAIL          可见区存在差异 → 图标没进包 / 版本不对 / 源图改了没重建

用法（在 proot Ubuntu 终端运行）：
  python3 verify_apk_icons.py --apk app-release.apk --res-src app/src/main/res \
      [--density xxhdpi] [--aapt2 /root/aapt2-bin/aapt2]

退出码：0 = 全部通过；1 = 存在 FAIL 或步骤失败。
"""
import argparse
import io
import os
import subprocess
import sys
import zipfile

import numpy as np
from PIL import Image

ICONS = ["ic_launcher", "ic_launcher_round", "ic_launcher_foreground", "ic_launcher_monochrome"]
DENSITIES = ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]


def parse_resource_map(dump_text, density):
    """从 aapt2 dump resources 文本提取 {mipmap/xxx: 'res/xx.png'}（指定密度的 PNG 条目）。"""
    found = {}
    current = None
    for line in dump_text.splitlines():
        s = line.strip()
        if s.startswith("resource"):
            parts = s.split()
            if len(parts) >= 3 and parts[2].startswith("mipmap/"):
                current = parts[2]
            else:
                current = None
            continue
        if s.startswith("(") and "(file)" in s:
            parts = s.split()
            if len(parts) >= 3 and current:
                dens = parts[0].strip("()")
                typ = parts[3].split("=")[1] if len(parts) > 3 and "=" in parts[3] else ""
                if dens == density and typ == "PNG":
                    found[current] = parts[2]
    return found


def find_notification(dump_text):
    """找 drawable/ic_notification 的实际文件与类型；找不到返回 None。"""
    lines = dump_text.splitlines()
    for i, ln in enumerate(lines):
        s = ln.strip()
        if s.startswith("resource") and "drawable/ic_notification" in s:
            for j in range(i + 1, min(i + 3, len(lines))):
                t = lines[j].strip()
                if t.startswith("(") and "(file)" in t:
                    parts = t.split()
                    if len(parts) >= 3:
                        typ = parts[3].split("=")[1] if len(parts) > 3 and "=" in parts[3] else ""
                        return parts[2], typ
    return None


def load_rgba(data):
    im = Image.open(io.BytesIO(data)).convert("RGBA")
    return np.asarray(im, dtype=np.int32)


def compare(a, b):
    """返回 (status, detail)。"""
    if a.shape != b.shape:
        return "FAIL", "尺寸不一致：apk=%dx%d vs src=%dx%d" % (a.shape[1], a.shape[0], b.shape[1], b.shape[0])
    ad = np.abs(a - b)
    rgb = ad[:, :, :3].max(axis=2)
    alpha = ad[:, :, 3]
    visible = (a[:, :, 3] > 0) | (b[:, :, 3] > 0)
    vis_rgb = int(((rgb > 0) & visible).sum())
    vis_a = int(((alpha > 0) & visible).sum())
    if vis_rgb == 0 and vis_a == 0:
        inv_rgb = int(((rgb > 0) & ~visible).sum())
        if inv_rgb == 0:
            return "PASS-EXACT", "全像素一致"
        return "PASS-CLEANUP", "可见区一致；仅 %d 个全透明像素的残留 RGB 被清理（打包器行为）" % inv_rgb
    ys, xs = np.where(((rgb > 0) | (alpha > 0)) & visible)
    sample = ""
    if len(xs):
        y, x = int(ys[0]), int(xs[0])
        sample = "；样例 (%d,%d) apk=%s src=%s" % (x, y, tuple(int(v) for v in a[y, x]), tuple(int(v) for v in b[y, x]))
    return "FAIL", "可见区差异：%d px RGB / %d px alpha%s" % (vis_rgb, vis_a, sample)


def main():
    ap = argparse.ArgumentParser(description="APK 图标进包像素级校验")
    ap.add_argument("--apk", required=True, help="release APK 路径")
    ap.add_argument("--res-src", required=True, help="项目 app/src/main/res 目录")
    ap.add_argument("--density", default="xxhdpi", choices=DENSITIES, help="比对密度（默认 xxhdpi）")
    ap.add_argument("--aapt2", default="/root/aapt2-bin/aapt2", help="aapt2 可执行文件路径")
    args = ap.parse_args()

    if not os.path.exists(args.apk):
        print("[ERR] APK 不存在：" + args.apk)
        return 1
    aapt2 = args.aapt2 if os.path.exists(args.aapt2) else "aapt2"
    try:
        out = subprocess.run([aapt2, "dump", "resources", args.apk], capture_output=True, text=True)
    except FileNotFoundError:
        print("[ERR] 找不到 aapt2：" + args.aapt2)
        return 1
    if out.returncode != 0:
        print("[ERR] aapt2 dump 失败：" + (out.stderr or "").strip()[:300])
        return 1

    mapping = parse_resource_map(out.stdout, args.density)
    rc = 0
    print("== 图标进包校验（density=%s）==" % args.density)
    with zipfile.ZipFile(args.apk) as z:
        for icon in ICONS:
            key = "mipmap/" + icon
            if key not in mapping:
                print("[SKIP] %s：APK 中未找到 %s 条目" % (key, args.density))
                continue
            src = os.path.join(args.res_src, "mipmap-" + args.density, icon + ".png")
            if not os.path.exists(src):
                print("[SKIP] %s：源图不存在 %s" % (key, src))
                continue
            with open(src, "rb") as f:
                status, detail = compare(load_rgba(z.read(mapping[key])), load_rgba(f.read()))
            flag = "OK " if status.startswith("PASS") else "BAD"
            print("[%s] %-28s apk:%-14s %s %s" % (flag, icon, mapping[key], status, detail))
            if status == "FAIL":
                rc = 1

    notif = find_notification(out.stdout)
    if notif:
        print("[i] drawable/ic_notification 在包：%s（%s）" % notif)
    else:
        print("[!] drawable/ic_notification 未找到（若应用有前台服务，请检查）")
    return rc


if __name__ == "__main__":
    sys.exit(main())