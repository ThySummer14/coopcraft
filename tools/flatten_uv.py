"""Flatten the tool-head UVs to a single colour sample.

The axe head was textured with a spread of UV samples across the item sprite,
which showed up in game as white/dark stripes. A single sample reads as a solid
metal head, which is what we want at viewmodel distance.

Usage:  python tools/flatten_uv.py axe 0.400 0.300 [hoe 0.300 0.300 ...]
Handle vertices (the wood sample 0.219,0.781) are left untouched.
"""

import json
import sys

HANDLE_UV = (0.219, 0.781)


def flatten(name, u, v):
    path = "src/main/resources/assets/coopcraft/weapon/%s.json" % name
    with open(path, encoding="utf-8") as f:
        mesh = json.load(f)

    uvs = mesh["uvs"]
    changed = 0
    for i in range(0, len(uvs), 2):
        cur = (round(uvs[i], 3), round(uvs[i + 1], 3))
        if cur == HANDLE_UV:
            continue
        uvs[i] = u
        uvs[i + 1] = v
        changed += 2

    with open(path, "w", encoding="utf-8") as f:
        json.dump(mesh, f, separators=(",", ":"))
    print("%s: %d coords -> (%.3f,%.3f)" % (name, changed, u, v))


args = sys.argv[1:]
for i in range(0, len(args), 3):
    flatten(args[i], float(args[i + 1]), float(args[i + 2]))
