#!/usr/bin/env python3
"""根据 <workdir>/pack.json 生成 modpack/PACK-MODS.md。

用法：python3 tools/pack/gen_modlist.py [--workdir packbuild]
"""
import argparse
import json
from collections import defaultdict
from pathlib import Path

import catalog

REPO = Path(__file__).resolve().parents[2]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workdir", default=str(REPO / "packbuild"))
    ap.add_argument("--out", default=str(REPO / "modpack/PACK-MODS.md"))
    ap.add_argument("--version", default="0.1.0")
    args = ap.parse_args()

    pack = json.loads((Path(args.workdir) / "pack.json").read_text())
    mods = {s: m for s, m in pack["mods"].items() if s not in catalog.EXCLUDE_CLIENT}

    by_cat, assigned = defaultdict(list), set()
    for cat, slugs in catalog.CATEGORIES.items():
        for s in slugs:
            if s in mods and s not in assigned:
                assigned.add(s)
                by_cat[cat].append((s, mods[s]))
    for s, m in sorted(mods.items()):
        if s not in assigned:
            by_cat["其他"].append((s, m))

    lines = [
        "# 客户端整合包模组清单",
        "",
        f"对应 `modpack/dist/CoopCraft-{catalog.GAME}-Client-{args.version}.mrpack`，"
        "版本与哈希以包内 `modrinth.index.json` 为准。",
        "",
        f"共 {len(mods)} 个第三方模组（不含 CoopCraft 本体，本体在 `overrides/mods/`）。",
        "",
    ]
    for cat in [*catalog.CATEGORIES, "其他"]:
        if cat not in by_cat:
            continue
        lines += [f"## {cat}", "", "| 模组 | 版本 | 许可证 | Modrinth |", "| --- | --- | --- | --- |"]
        for s, m in sorted(by_cat[cat]):
            lines.append(f"| {m['title']} | `{m['version']}` | {m['license']} | "
                         f"[{s}](https://modrinth.com/mod/{s}) |")
        lines.append("")

    lines += ["## 只装服务端（不随整合包下发）", "",
              "| 模组 | 版本 | 许可证 | 用途 |", "| --- | --- | --- | --- |"]
    for s in catalog.SERVER_ONLY:
        if s not in pack["mods"]:
            continue
        m = pack["mods"][s]
        lines.append(f"| {m['title']} | `{m['version']}` | {m['license']} | "
                     f"{catalog.SERVER_PURPOSE.get(s, '')} |")
    lines.append("")

    Path(args.out).write_text("\n".join(lines))
    print(f"写出 {args.out}：{len(mods)} 个客户端模组")


if __name__ == "__main__":
    main()
