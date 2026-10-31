#!/usr/bin/env python3
"""解析 1.21.1 + Fabric 的可用模组版本，产出 <workdir>/pack.json。

用法：python3 tools/pack/resolve.py [--workdir packbuild]
"""
import argparse
import json
import os
import subprocess
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import catalog

API = "https://api.modrinth.com/v2"
UA = "coopcraft-pack-builder/0.1"
REPO = Path(__file__).resolve().parents[2]


def curl_json(url, path):
    path = Path(path)
    if path.exists() and path.stat().st_size > 2:
        return json.loads(path.read_text())
    path.parent.mkdir(parents=True, exist_ok=True)
    last = ""
    for _ in range(3):
        p = subprocess.run(["curl", "-sS", "--fail", "--max-time", "40",
                            "-H", f"User-Agent: {UA}", url, "-o", str(path)],
                           capture_output=True, text=True)
        if p.returncode == 0:
            return json.loads(path.read_text())
        last = p.stderr.strip()
    raise RuntimeError(f"{url}: {last}")


def pick(vlist):
    if not vlist:
        return None
    rel = [v for v in vlist if v["version_type"] == "release"]
    pool = rel or vlist
    pool = sorted(pool, key=lambda v: v["date_published"], reverse=True)
    return pool[0]


def primary(ver):
    for f in ver["files"]:
        if f.get("primary"):
            return f
    return ver["files"][0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workdir", default=str(REPO / "packbuild"))
    args = ap.parse_args()
    work = Path(args.workdir)
    api = work / "api"
    q = f"game_versions=%5B%22{catalog.GAME}%22%5D&loaders=%5B%22{catalog.LOADER}%22%5D"

    with ThreadPoolExecutor(8) as ex:
        list(ex.map(lambda s: curl_json(f"{API}/project/{s}", api / f"proj-{s}.json"), catalog.SEEDS))
        list(ex.map(lambda s: curl_json(f"{API}/project/{s}/version?{q}", api / f"vers-{s}.json"),
                    catalog.SEEDS))

    mods, missing, seen = {}, [], set()
    queue = [(s, False) for s in catalog.SEEDS]
    while queue:
        slug, is_dep = queue.pop(0)
        if slug in seen:
            continue
        seen.add(slug)
        ver = pick(json.loads((api / f"vers-{slug}.json").read_text()))
        if not ver:
            missing.append(slug)
            continue
        meta = json.loads((api / f"proj-{slug}.json").read_text())
        f = primary(ver)
        mods[slug] = {
            "title": meta["title"], "version": ver["version_number"], "version_id": ver["id"],
            "date": ver["date_published"][:10], "type": ver["version_type"],
            "client_side": meta.get("client_side"), "server_side": meta.get("server_side"),
            "license": meta.get("license", {}).get("id"), "url": f["url"],
            "filename": f["filename"], "size": f["size"],
            "sha1": f["hashes"]["sha1"], "sha512": f["hashes"]["sha512"], "dep": is_dep,
        }
        for d in ver.get("dependencies", []):
            if d.get("dependency_type") != "required" or not d.get("project_id"):
                continue
            try:
                dp = curl_json(f"{API}/project/{d['project_id']}", api / f"byid-{d['project_id']}.json")
            except Exception as e:  # noqa: BLE001
                print(f"  ! 依赖查询失败 {slug} -> {d['project_id']}: {e}")
                continue
            queue.append((dp["slug"], True))

    out = {"game": catalog.GAME, "loader": catalog.LOADER, "mods": mods, "missing": missing}
    work.mkdir(parents=True, exist_ok=True)
    (work / "pack.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
    total = sum(m["size"] for m in mods.values())
    print(f"解析完成：{len(mods)} 个模组，{total / 1048576:.1f} MiB")
    if missing:
        print("缺失（1.21.1 无对应版本）：", ", ".join(missing))


if __name__ == "__main__":
    main()
