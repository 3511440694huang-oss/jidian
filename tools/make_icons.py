#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""疾点 · 图标资源生成（Seedream 生成图 → 3 色扁平化 → 全密度 mipmap + 自适应图标）

输入：/sdcard/WORK/generated-images/seedream-*-jidian-icon.png（1024 方图）
产出：
  项目根 icon-master.png / icon-foreground-432.png / icon-preview.png
  res/mipmap-{mdpi..xxxhdpi}/ic_launcher.png / ic_launcher_round.png
                                / ic_launcher_foreground.png / ic_launcher_monochrome.png
"""
import os, sys, glob
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "app/src/main/res")

PALETTE = [
    ("#14171A", (0x14, 0x17, 0x1A)),  # graphite bg / ink
    ("#EDEAE3", (0xED, 0xEA, 0xE3)),  # ivory dial
    ("#C6372A", (0xC6, 0x37, 0x2A)),  # signal red hand
]
BG = PALETTE[0][1]
BG_HEX = PALETTE[0][0]
PREVIEW_BG = (0xF7, 0xF5, 0xF0)

MASTER = 1024
DIAL_RATIO_LEGACY = 0.72   # 方形图标里表盘占宽
SAFE_DIAL_RATIO = 0.58     # 自适应前景：表盘占 108dp 画布比例
SQUARE_DP = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]
FG_DP = [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]


def quantize(img):
    arr = np.asarray(img.convert("RGB"), dtype=np.int32)
    cols = np.array([p[1] for p in PALETTE], dtype=np.int32)
    dist = ((arr[:, :, None, :] - cols[None, None, :, :]) ** 2).sum(axis=-1)
    labels = dist.argmin(axis=-1).astype(np.uint8)
    labels = np.asarray(Image.fromarray(labels, "L").filter(ImageFilter.ModeFilter(3)))
    rgb = cols[labels].astype(np.uint8)
    return Image.fromarray(rgb, "RGB"), labels


def find_dial(labels):
    ivory = labels == 1
    ys, xs = np.nonzero(ivory)
    if xs.size == 0:
        raise RuntimeError("找不到象牙色表盘")
    cx = (xs.min() + xs.max()) / 2.0
    cy = (ys.min() + ys.max()) / 2.0
    r = max(xs.max() - xs.min(), ys.max() - ys.min()) / 2.0
    return cx, cy, r


def make_master(src_path):
    src = Image.open(src_path).convert("RGB")
    if src.width != src.height:
        s = min(src.size)
        src = src.crop(((src.width - s) // 2, (src.height - s) // 2,
                        (src.width + s) // 2, (src.height + s) // 2))
    flat, labels = quantize(src)
    cx, cy, r = find_dial(labels)
    # 裁成以表盘中心为中心的方图，表盘占 DIAL_RATIO_LEGACY
    side = min(src.width, src.height, int(round(2.0 * r / DIAL_RATIO_LEGACY)))
    left = int(round(cx - side / 2.0)); top = int(round(cy - side / 2.0))
    left = max(0, min(src.width - side, left)); top = max(0, min(src.height - side, top))
    crop = flat.crop((left, top, left + side, top + side)).resize((MASTER, MASTER), Image.LANCZOS)
    # 背景纯色化（裁切区外的旧背景可能带噪）
    arr = np.asarray(crop).copy()
    # 与背景距离很小的像素（抗锯齿过渡）交给重采样，不再硬处理
    return crop, labels, (cx - left) * MASTER / side, (cy - top) * MASTER / side, r * MASTER / side


def background_mask(labels_master, bg_threshold=60):
    """从画布四边 flood fill，抠掉与背景连通的区域，返回保留掩码。"""
    h, w = labels_master.shape
    lab = Image.fromarray(labels_master.copy(), "L")
    if lab.getpixel((0, 0)) == 0:
        ImageDraw.floodfill(lab, (0, 0), 255)
    for pt in ((w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        if lab.getpixel(pt) == 0:
            ImageDraw.floodfill(lab, pt, 255)
    return np.asarray(lab) != 255


def build(src_path):
    master_img, labels, dcx, dcy, dr = make_master(src_path)
    master_img.save(os.path.join(ROOT, "icon-master.png"), optimize=True)

    # 前景抠图（只抠外圈背景，表盘内部的石墨色刻度/指针保留）
    m_labels = np.asarray(master_img.convert("RGB"), dtype=np.int32)
    cols = np.array([p[1] for p in PALETTE], dtype=np.int32)
    dist = ((m_labels[:, :, None, :] - cols[None, None, :, :]) ** 2).sum(axis=-1)
    m_lab = dist.argmin(axis=-1).astype(np.uint8)
    keep = background_mask(m_lab)
    rgb = np.asarray(master_img, dtype=np.uint8)
    alpha = np.where(keep, 255, 0).astype(np.uint8)
    subject = Image.fromarray(np.dstack([rgb, alpha]), "RGBA")
    ys, xs = np.nonzero(alpha)
    bbox = (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1)
    subject = subject.crop(bbox)

    def render_fg(canvas_px, subject_img, ratio):
        canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
        target = int(round(canvas_px * ratio))
        w, h = subject_img.size
        scale = target / float(max(w, h))
        nw = max(1, int(round(w * scale))); nh = max(1, int(round(h * scale)))
        s = subject_img.resize((nw, nh), Image.LANCZOS)
        canvas.paste(s, ((canvas_px - nw) // 2, (canvas_px - nh) // 2), s.split()[3])
        return canvas

    # 单色（主题图标）：外圈表环 + 红色秒针 + 中心轴帽
    hh, ww = alpha.shape
    yy, xx = np.mgrid[0:hh, 0:ww]
    rr = np.sqrt((xx - dcx) ** 2 + (yy - dcy) ** 2)
    mono = np.zeros_like(alpha)
    ring = (rr > dr * 0.80) & (rr < dr * 0.99) & keep
    red = (m_lab == 2) & keep
    cap = (rr < dr * 0.10) & keep
    mono[(ring | red | cap)] = 255
    mono_img = Image.fromarray(np.dstack([rgb, mono]), "RGBA")
    ys2, xs2 = np.nonzero(mono)
    if xs2.size:
        mono_img = mono_img.crop((int(xs2.min()), int(ys2.min()),
                                  int(xs2.max()) + 1, int(ys2.max()) + 1))
    else:
        mono_img = subject

    # ---- 各密度资源
    for density, px in SQUARE_DP:
        d = os.path.join(RES, "mipmap-" + density)
        os.makedirs(d, exist_ok=True)
        master_img.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"), optimize=True)
        rnd = master_img.resize((px, px), Image.LANCZOS).convert("RGBA")
        mask = Image.new("L", (px * 4, px * 4), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, px * 4 - 1, px * 4 - 1), fill=255)
        rnd.putalpha(mask.resize((px, px), Image.LANCZOS))
        rnd.save(os.path.join(d, "ic_launcher_round.png"), optimize=True)

    for density, px in FG_DP:
        d = os.path.join(RES, "mipmap-" + density)
        os.makedirs(d, exist_ok=True)
        render_fg(px, subject, SAFE_DIAL_RATIO).save(os.path.join(d, "ic_launcher_foreground.png"), optimize=True)
        render_fg(px, mono_img, SAFE_DIAL_RATIO * 1.04).save(os.path.join(d, "ic_launcher_monochrome.png"), optimize=True)

    # ---- 根目录预览资产
    fg432 = render_fg(432, subject, SAFE_DIAL_RATIO)
    fg432.save(os.path.join(ROOT, "icon-foreground-432.png"), optimize=True)

    def circle_crop(img):
        out = img.convert("RGBA")
        m = Image.new("L", (out.width * 4, out.height * 4), 0)
        ImageDraw.Draw(m).ellipse((0, 0, out.width * 4 - 1, out.height * 4 - 1), fill=255)
        out.putalpha(m.resize(out.size, Image.LANCZOS))
        return out

    tile = 320
    canvas = Image.new("RGB", (tile * 3 + 80, tile + 40), PREVIEW_BG)
    sq = master_img.resize((tile, tile), Image.LANCZOS)
    canvas.paste(sq, (20, 20))
    rnd = circle_crop(master_img.resize((tile, tile), Image.LANCZOS))
    canvas.paste(rnd.convert("RGB"), (tile + 40, 20), rnd.split()[3])
    # 自适应图标真实观感：108dp 画布缩放成 72dp 视口（1.5x 放大），圆形遮罩
    adaptive_canvas = Image.new("RGBA", (tile, tile), BG + (255,))
    fg_big = render_fg(tile, subject, SAFE_DIAL_RATIO)
    adaptive_canvas.alpha_composite(fg_big)
    mask = Image.new("L", (tile * 4, tile * 4), 0)
    vm = int(tile * 4 * 72.0 / 108.0)
    off = (tile * 4 - vm) // 2
    ImageDraw.Draw(mask).ellipse((off, off, off + vm - 1, off + vm - 1), fill=255)
    adaptive_canvas.putalpha(mask.resize((tile, tile), Image.LANCZOS))
    canvas.paste(adaptive_canvas.convert("RGB"), (tile * 2 + 60, 20), adaptive_canvas.split()[3])
    canvas.save(os.path.join(ROOT, "icon-preview.png"), optimize=True)

    print("master:", master_img.size, "dial center:", (round(dcx, 1), round(dcy, 1)), "r:", round(dr, 1))
    print("wrote", len(SQUARE_DP) + len(FG_DP), "density sets + previews")


if __name__ == "__main__":
    srcs = sys.argv[1:] or sorted(glob.glob("/sdcard/WORK/generated-images/seedream-*-jidian-icon.png"))
    if not srcs:
        raise SystemExit("没有找到源图")
    build(srcs[-1])
