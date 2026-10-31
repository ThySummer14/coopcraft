#!/usr/bin/env python3
"""用 <workdir>/pack.json 打包客户端 .mrpack。

用法：python3 tools/pack/make_mrpack.py [--workdir packbuild] [--version 0.1.0]
"""
import argparse
import json
import shutil
import zipfile
from pathlib import Path

import catalog

REPO = Path(__file__).resolve().parents[2]


def env(side):
    return {"required": "required", "optional": "optional"}.get(side, "unsupported")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workdir", default=str(REPO / "packbuild"))
    ap.add_argument("--version", default="0.1.0")
    ap.add_argument("--jar", default=str(REPO / "build/libs/coopcraft-0.1.0.jar"))
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    pack = json.loads((Path(args.workdir) / "pack.json").read_text())
    files = []
    for slug, m in pack["mods"].items():
        if slug in catalog.EXCLUDE_CLIENT:
            continue
        files.append({
            "path": f"mods/{m['filename']}",
            "hashes": {"sha1": m["sha1"], "sha512": m["sha512"]},
            "env": {"client": env(m["client_side"]), "server": env(m["server_side"])},
            "downloads": [m["url"]],
            "fileSize": m["size"],
        })

    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": args.version,
        "name": f"CoopCraft {catalog.GAME} 客户端整合包",
        "summary": "CoopCraft 联机生存 + 性能优化 + 基础 QoL（Fabric 1.21.1）",
        "files": sorted(files, key=lambda f: f["path"]),
        "dependencies": {"minecraft": catalog.GAME, "fabric-loader": catalog.FABRIC_LOADER},
    }

    out = Path(args.out or REPO / "modpack/dist" / f"CoopCraft-{catalog.GAME}-Client-{args.version}.mrpack")
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(".tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("modrinth.index.json", json.dumps(index, ensure_ascii=False, indent=2))
        z.write(args.jar, f"overrides/mods/{Path(args.jar).name}")
    shutil.move(tmp, out)

    total = sum(f["fileSize"] for f in files)
    print(f"写出 {out}：{len(files)} 个模组，{total / 1048576:.1f} MiB 下载量，"
          f"包体 {out.stat().st_size / 1024:.0f} KiB")


if __name__ == "__main__":
    main()
