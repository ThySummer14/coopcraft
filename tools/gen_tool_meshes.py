"""Rebuild the axe / pickaxe / hoe meshes.

The old ones had their heads floating off the shaft and textured with a spread of
UV samples, which read as stripes and, on the hoe, as nothing at all (the samples
landed on transparent pixels and the cutout shader dropped the faces).

Layout: +Y runs up the handle, the head sits at the top and is thin along Z, so
its broad face points at the camera the same way the sword blade does. Units are
1/16 block, origin at the grip.

Textures: each face samples one pixel of the item sprite. The coordinates come
from tools/AnalyzeUV.java, which takes the centroid of the wooden and metal
pixels of the vanilla diamond tool sprites — the layout is shared by every
material, so the same numbers work for iron, gold, netherite, ...
"""

import json
import os

OUT_DIR = os.path.join("src", "main", "resources", "assets", "coopcraft", "weapon")

# 木头在四把工具贴图上是同一块区域，实测 0.219,0.781 在所有材质上都落在木纹上
WOOD_UV = (0.219, 0.781)

METAL_UV = {
    "sword": (0.573, 0.448),
    "axe": (0.663, 0.350),
    "pickaxe": (0.719, 0.344),
    "hoe": (0.688, 0.281),
}

FACES = {
    "+x": [(1, 0, 1), (1, 0, 0), (1, 1, 0), (1, 1, 1)],
    "-x": [(0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)],
    "+y": [(0, 1, 1), (1, 1, 1), (1, 1, 0), (0, 1, 0)],
    "-y": [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)],
    "+z": [(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)],
    "-z": [(1, 0, 0), (0, 0, 0), (0, 1, 0), (1, 1, 0)],
}


class Mesh:
    def __init__(self):
        self.positions = []
        self.uvs = []
        self.indices = []

    def box(self, x0, y0, z0, x1, y1, z1, uv):
        for corners in FACES.values():
            base = len(self.positions) // 3
            for cx, cy, cz in corners:
                self.positions.extend([
                    x1 if cx else x0,
                    y1 if cy else y0,
                    z1 if cz else z0,
                ])
                self.uvs.extend(uv)
            self.indices.extend([base, base + 1, base + 2, base, base + 2, base + 3])

    def save(self, name):
        path = os.path.join(OUT_DIR, name + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump({
                "positions": [round(v, 4) for v in self.positions],
                "uvs": [round(v, 4) for v in self.uvs],
                "indices": self.indices,
            }, f, separators=(",", ":"))
        print("%-9s %3d verts, %3d tris" % (name, len(self.positions) // 3, len(self.indices) // 3))


def shaft(m):
    m.box(-0.8, -4.4, -0.8, 0.8, 9.4, 0.8, WOOD_UV)
    # 握柄处稍粗一点，握感更实在
    m.box(-0.95, -1.6, -0.95, 0.95, 1.4, 0.95, WOOD_UV)


def sword():
    """实心刀身。

    旧网格的刀身是照着贴图 alpha 描出来的锯齿轮廓，一个薄片一个薄片拼起来，
    远看像鱼骨，还会漏出背景。这里改成三段式棱柱：刀根、刀身、刀尖，
    中间加一道凸起的脊线，靠面朝光的方向不同做出刃面明暗。
    """
    m = Mesh()
    metal = METAL_UV["sword"]
    m.box(-0.80, -2.9, -0.80, 0.80, -2.0, 0.80, metal)     # 剑首
    m.box(-0.60, -2.0, -0.60, 0.60, 1.6, 0.60, WOOD_UV)    # 缠绳握柄
    m.box(-2.80, 1.6, -0.50, 2.80, 2.4, 0.50, metal)       # 护手
    m.box(-1.15, 2.4, -0.40, 1.15, 9.0, 0.40, metal)       # 刀根
    m.box(-0.35, 2.4, -0.46, 0.35, 12.0, 0.46, metal)      # 中央脊线
    m.box(-0.95, 9.0, -0.36, 0.95, 12.0, 0.36, metal)      # 刀身
    m.box(-0.62, 12.0, -0.30, 0.62, 14.6, 0.30, metal)     # 前段
    m.box(-0.26, 14.6, -0.22, 0.26, 16.0, 0.22, metal)     # 剑尖
    m.save("sword")


def axe():
    m = Mesh()
    metal = METAL_UV["axe"]
    shaft(m)
    # 斧头的轮廓是"眼窄 → 颈更窄 → 刃口最高最薄"，这样才不像一把锤子。
    m.box(-0.95, 7.8, -0.95, 1.0, 10.0, 0.95, metal)    # 斧眼
    m.box(1.0, 8.2, -0.55, 2.0, 9.6, 0.55, metal)       # 斧颈（最窄）
    m.box(2.0, 7.0, -0.62, 3.2, 10.8, 0.62, metal)      # 斧身（上下张开）
    m.box(3.2, 6.6, -0.26, 3.9, 11.2, 0.26, metal)      # 刃口（最高、最薄）
    m.save("axe")


def pickaxe():
    m = Mesh()
    metal = METAL_UV["pickaxe"]
    shaft(m)
    # 镐是两根从中间往外收细的尖刺，不是两头一样粗的横杠。
    m.box(-0.95, 7.9, -0.90, 0.95, 9.9, 0.90, metal)    # 中间箍
    m.box(-3.0, 8.1, -0.55, -0.95, 9.7, 0.55, metal)    # 左臂
    m.box(-4.6, 8.3, -0.40, -3.0, 9.5, 0.40, metal)     # 左臂前段
    m.box(-5.6, 8.5, -0.26, -4.6, 9.3, 0.26, metal)     # 左尖
    m.box(0.95, 8.1, -0.55, 3.0, 9.7, 0.55, metal)      # 右臂
    m.box(3.0, 8.3, -0.40, 4.6, 9.5, 0.40, metal)       # 右臂前段
    m.box(4.6, 8.5, -0.26, 5.6, 9.3, 0.26, metal)       # 右尖
    m.save("pickaxe")


def hoe():
    m = Mesh()
    metal = METAL_UV["hoe"]
    shaft(m)
    m.box(-0.85, 8.2, -0.85, 0.9, 10.0, 0.85, metal)   # 颈
    m.box(0.9, 8.5, -1.30, 4.0, 9.1, 1.30, metal)      # 锄板（横向宽、纵向薄）
    m.box(4.0, 8.3, -1.30, 4.7, 9.3, 1.30, metal)      # 刃口
    m.save("hoe")


if __name__ == "__main__":
    sword()
    axe()
    pickaxe()
    hoe()
