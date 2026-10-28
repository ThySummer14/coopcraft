#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「无限宝石」模组的物品贴图（16x16 PNG，零第三方依赖）。

用法：
    python tools/gen_textures.py             # 生成正式贴图
    python tools/gen_textures.py --preview   # 额外输出放大预览图，方便肉眼调

手套不是手写 16 行字符，而是「形状层 -> 描边层 -> 宝石层 -> 明暗层」分层画，
改动 FINGERS / PALM / THUMB / CUFF / GEM_SLOTS 就能调整造型。
"""

import os
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.normpath(
    os.path.join(HERE, "..", "src", "main", "resources", "assets", "infinitystones", "textures", "item")
)
PREVIEW_DIR = os.path.join(HERE, "preview")

SIZE = 16

# 六颗宝石的基础颜色（与 Java 端 InfinityStone 枚举保持一致）
STONES = [
    ("space_stone", (59, 111, 224)),
    ("mind_stone", (243, 197, 25)),
    ("reality_stone", (214, 58, 47)),
    ("power_stone", (155, 48, 217)),
    ("time_stone", (47, 201, 111)),
    ("soul_stone", (255, 123, 26)),
]

# ---------------------------------------------------------------- 调色板
GOLD_DEEP = (124, 78, 10)
GOLD_DARK = (178, 122, 24)
GOLD_MID = (232, 176, 52)
GOLD_LIGHT = (255, 218, 112)
GOLD_SPEC = (255, 250, 208)
OUTLINE = (48, 28, 4)
SOCKET = (74, 48, 10)

GOLD_RAMP = [GOLD_DEEP, GOLD_DARK, GOLD_MID, GOLD_LIGHT]


# ---------------------------------------------------------------- PNG 输出
def write_png(path, width, height, rows):
    """rows: [[(r,g,b,a), ...], ...]"""
    raw = bytearray()
    for row in rows:
        raw.append(0)  # filter type 0
        for pixel in row:
            raw.extend(pixel)

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")

    with open(path, "wb") as fh:
        fh.write(png)


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def shade(color, factor):
    """factor > 1 提亮，< 1 压暗"""
    if factor >= 1.0:
        return mix(color, (255, 255, 255), factor - 1.0)
    return mix(color, (10, 8, 24), 1.0 - factor)


# ---------------------------------------------------------------- 宝石（菱形）
def gem_pixel(base, x, y):
    """菱形宝石：左上打光 + 镜面高光 + 暗描边"""
    cx = x - 7.5
    cy = y - 7.5
    d = abs(cx) / 6.3 + abs(cy) / 6.9
    if d > 1.0:
        return (0, 0, 0, 0)
    if d > 0.90:
        return shade(base, 0.38) + (255,)

    light = 0.62 - 0.55 * (cx / 6.3 * 0.7 + cy / 6.9 * 0.9)
    light = max(0.0, min(1.0, light))
    factor = 0.68 + 0.72 * light

    if (x - 5.6) ** 2 + (y - 4.6) ** 2 <= 3.4:
        factor = 1.62
    elif abs(cx + cy * 0.35) < 0.7 and y > 5:
        factor += 0.20

    return shade(base, factor) + (255,)


def build_gem(base):
    return [[gem_pixel(base, x, y) for x in range(SIZE)] for y in range(SIZE)]


# ---------------------------------------------------------------- 手套形状
# 手工编排 16x16 点阵：
#   .  透明        #  外描边
#   0  深金(阴影)  1  暗金        2  中金        3  亮金        *  高光
#   a-f  六颗宝石位（未镶嵌时画成暗色空座）
GAUNTLET_GRID = [
    "................",
    ".......##.......",
    "....##.33.##....",
    "....33.32.33....",
    "....32.21.32....",
    "..#1222222221#..",
    ".#11aa1bb1cc1#..",
    ".#11aa1bb1cc1#..",
    ".#11111111111#..",
    "..#1dd1ee1ff1#..",
    "..#1dd1ee1ff1#..",
    ".#3*33333333*3#.",
    ".#122222222221#.",
    ".#122222222221#.",
    ".##############.",
    "................",
]

GEM_LETTERS = "abcdef"

# 2x2 宝石的四角明暗：左上打亮、右下压暗，看起来才像颗球面宝石
GEM_CORNER_SHADE = {(0, 0): 1.50, (1, 0): 1.10, (0, 1): 1.02, (1, 1): 0.76}

PALETTE = {
    ".": None,
    "#": OUTLINE,
    "0": GOLD_DEEP,
    "1": GOLD_DARK,
    "2": GOLD_MID,
    "3": GOLD_LIGHT,
    "*": GOLD_SPEC,
}


def build_gauntlet(gem_count=6):
    """gem_count 控制画几颗宝石，其余画成空的暗色宝石座。"""
    assert len(GAUNTLET_GRID) == SIZE
    socket_positions = {}
    for y, line in enumerate(GAUNTLET_GRID):
        assert len(line) == SIZE, "第 %d 行长度不是 16：%r" % (y, line)
        for x, ch in enumerate(line):
            if ch in GEM_LETTERS:
                socket_positions[(x, y)] = GEM_LETTERS.index(ch)

    rows = []
    for y, line in enumerate(GAUNTLET_GRID):
        row = []
        for x, ch in enumerate(line):
            if ch == ".":
                row.append((0, 0, 0, 0))
                continue

            index = socket_positions.get((x, y))
            if index is not None:
                if index < gem_count:
                    base = STONES[index][1]
                    origin = _gem_origin(index)
                    offset = (x - origin[0], y - origin[1])
                    color = shade(base, GEM_CORNER_SHADE.get(offset, 1.0))
                else:
                    color = SOCKET
                row.append(tuple(color) + (255,))
                continue

            row.append(tuple(PALETTE[ch]) + (255,))
        rows.append(row)
    return rows


def _gem_origin(index):
    """返回第 index 颗宝石的左上角坐标（取点阵里最小的 (y, x)）。"""
    best = None
    for y, line in enumerate(GAUNTLET_GRID):
        x = line.find(GEM_LETTERS[index])
        if x >= 0 and (best is None or y < best[1]):
            best = (x, y)
    return best


# ---------------------------------------------------------------- 预览图
def make_sheet(images, scale=8, gap=8, bg=(24, 24, 28, 255)):
    tile_w = SIZE * scale
    total_w = len(images) * tile_w + (len(images) + 1) * gap
    total_h = tile_w + gap * 2

    rows = []
    for _ in range(gap):
        rows.append([bg] * total_w)
    for y in range(tile_w):
        line = [bg] * gap
        for image in images:
            src_row = image[y // scale]
            for px in src_row:
                if px[3] == 0:
                    px = bg
                line.extend([px] * scale)
        line.extend([bg] * gap)
        rows.append(line)
    for _ in range(gap):
        rows.append([bg] * total_w)
    return total_w, total_h, rows


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    gauntlet_images = []
    for gem_count in range(len(STONES) + 1):
        image = build_gauntlet(gem_count)
        gauntlet_images.append(image)
        name = "infinity_gauntlet" + ("" if gem_count == 0 else "_%d" % gem_count)
        path = os.path.join(OUT_DIR, name + ".png")
        write_png(path, SIZE, SIZE, image)
        print("generated", path)

    stone_images = []
    for name, color in STONES:
        image = build_gem(color)
        stone_images.append(image)
        path = os.path.join(OUT_DIR, name + ".png")
        write_png(path, SIZE, SIZE, image)
        print("generated", path)

    if "--preview" in sys.argv:
        os.makedirs(PREVIEW_DIR, exist_ok=True)
        for label, images in (("gauntlet_states", gauntlet_images), ("stones", stone_images)):
            w, h, rows = make_sheet(images, scale=8)
            path = os.path.join(PREVIEW_DIR, label + ".png")
            write_png(path, w, h, rows)
            print("preview ->", path)


if __name__ == "__main__":
    main()
