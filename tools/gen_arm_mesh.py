"""Generate the first-person right arm mesh.

Coordinate system (units = 1/16 block, origin = the point where the handle sits
in the fist):

    +Y  along the handle, towards the blade
    +X  towards the fingers (outward from the palm)
    +Z  towards the thumb / camera side

Every box is emitted with flat vertex colours, so the arm needs no texture.
"""

import json
import os

OUT = os.path.join(
    "src", "main", "resources", "assets", "coopcraft", "weapon", "arm.json"
)

SKIN = (0.80, 0.57, 0.43)
SKIN_DARK = (0.69, 0.47, 0.34)
GLOVE = (0.30, 0.22, 0.18)
SLEEVE = (0.15, 0.19, 0.26)
SLEEVE_DARK = (0.10, 0.13, 0.19)

# 玩家模型空间里 +Y 是"向下"。这里按直觉建模（+Y 指向肘部之外），
# 导出时统一翻转，正好让肘部朝向屏幕下方，小臂自然从右下角出画。
FLIP_Y = True

# CCW winding seen from outside; the renderer derives flat normals from these.
FACES = {
    "+x": [(1, 0, 1), (1, 0, 0), (1, 1, 0), (1, 1, 1)],
    "-x": [(0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)],
    "+y": [(0, 1, 1), (1, 1, 1), (1, 1, 0), (0, 1, 0)],
    "-y": [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)],
    "+z": [(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)],
    "-z": [(1, 0, 0), (0, 0, 0), (0, 1, 0), (1, 1, 0)],
}

positions = []
uvs = []
colors = []
indices = []


def box(x0, y0, z0, x1, y1, z1, color, skip=()):
    """Append one axis-aligned box."""
    for face, corners in FACES.items():
        if face in skip:
            continue
        base = len(positions) // 3
        for cx, cy, cz in corners:
            positions.extend([
                x1 if cx else x0,
                y1 if cy else y0,
                z1 if cz else z0,
            ])
            uvs.extend([0.5, 0.5])
            colors.extend(color)
        indices.extend([base, base + 1, base + 2, base, base + 2, base + 3])


# ---------------------------------------------------------------- forearm
# Tapers slightly towards the elbow so it does not read as a plain beam.
box(-2.9, -9.6, -2.2, 2.3, -6.6, 2.2, SLEEVE)
box(-3.1, -6.6, -2.4, 2.5, -5.9, 2.4, SLEEVE_DARK)   # cuff
box(-2.7, -5.9, -2.3, 2.2, -4.2, 2.3, SKIN)          # bare forearm
box(-2.9, -4.2, -2.5, 2.4, -2.4, 2.5, SKIN)          # wrist

# ---------------------------------------------------------------- palm
# Palm sits on the -X side of the handle, the fingers curl around the +X side.
box(-3.0, -2.4, -2.7, 1.2, 2.4, 2.7, GLOVE)
box(-3.0, 1.4, -2.7, -1.4, 2.4, 2.7, SKIN_DARK)      # heel of the palm peeks out

# ---------------------------------------------------------------- fingers
# Four curled fingers, each with a gap so the fist reads as separate digits.
# 手指往里缩 0.4 像素，指缝里露出深色手套——不然在 30 像素宽的手上完全看不出分指。
for i in range(4):
    y0 = -2.45 + i * 1.36
    y1 = y0 + 1.08
    box(1.2, y0, -2.3, 3.3, y1, 2.3, SKIN)
    box(3.3, y0 + 0.12, -2.0, 3.9, y1 - 0.12, 2.0, SKIN_DARK)  # fingertips

# ---------------------------------------------------------------- thumb
# Lies across the fingers, pointing up along the handle.
box(-1.4, 2.4, -2.9, 2.2, 4.6, -1.3, SKIN)
box(2.2, 2.7, -2.7, 3.5, 4.3, -1.5, SKIN_DARK)

mesh = {
    "positions": [round(v, 4) for v in positions],
    "uvs": [round(v, 4) for v in uvs],
    "indices": indices,
    "colors": [round(v, 4) for v in colors],
}

if FLIP_Y:
    mesh["positions"][1::3] = [-v for v in mesh["positions"][1::3]]
    # 翻转坐标会反手性，三角形绕序跟着反过来，法线才仍然朝外。
    idx = mesh["indices"]
    for i in range(0, len(idx), 3):
        idx[i + 1], idx[i + 2] = idx[i + 2], idx[i + 1]

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(mesh, f, separators=(",", ":"))

print("%s: %d verts, %d tris" % (OUT, len(positions) // 3, len(indices) // 3))
