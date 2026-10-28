#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""模组资源自检：JSON 合法性 + 模型/贴图/语言键/配方 id 一致性。

用法：
    python tools/check_resources.py
"""

import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
RES = os.path.join(ROOT, "src", "main", "resources")
MODID = "infinitystones"

Java_ITEM_RE = re.compile(r'ITEMS\.register\("([a-z0-9_]+)"')
JAVA_DIR = os.path.join(ROOT, "src", "main", "java")

errors = []
warnings = []


def rel(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


def load_json(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except Exception as exc:  # noqa: BLE001
        errors.append("%s: JSON 解析失败 -> %s" % (rel(path), exc))
        return None


def registered_items():
    items = set()
    for dirpath, _dirs, files in os.walk(JAVA_DIR):
        for name in files:
            if not name.endswith(".java"):
                continue
            with open(os.path.join(dirpath, name), "r", encoding="utf-8") as fh:
                text = fh.read()
            items.update(Java_ITEM_RE.findall(text))
            # stone(...) 辅助方法注册的宝石
            if "stone(InfinityStone." in text:
                for stone in re.findall(r"stone\(InfinityStone\.([A-Z]+)\)", text):
                    items.add(stone.lower() + "_stone")
    return items


def check_jsons():
    for dirpath, _dirs, files in os.walk(RES):
        for name in files:
            if name.endswith(".json"):
                load_json(os.path.join(dirpath, name))


def check_models(items):
    model_dir = os.path.join(RES, "assets", MODID, "models", "item")
    tex_dir = os.path.join(RES, "assets", MODID, "textures", "item")
    for item in sorted(items):
        model_path = os.path.join(model_dir, item + ".json")
        if not os.path.isfile(model_path):
            errors.append("缺少物品模型: %s" % rel(model_path))
            continue
        data = load_json(model_path) or {}
        layer = (data.get("textures") or {}).get("layer0", "")
        expected_tex = "%s:item/%s" % (MODID, item)
        if not layer.startswith(expected_tex):
            warnings.append("%s: layer0 期望 %s，实际 %s" % (rel(model_path), expected_tex, layer))
        if not os.path.isfile(os.path.join(tex_dir, item + ".png")):
            errors.append("缺少贴图: %s" % rel(os.path.join(tex_dir, item + ".png")))


def check_lang(items, stones):
    lang_dir = os.path.join(RES, "assets", MODID, "lang")
    for lang in ("zh_cn.json", "en_us.json"):
        path = os.path.join(lang_dir, lang)
        data = load_json(path)
        if data is None:
            continue
        for item in sorted(items):
            key = "item.%s.%s" % (MODID, item)
            if key not in data:
                errors.append("%s: 缺少语言键 %s" % (lang, key))
        for stone in stones:
            for key in ("stone.%s.%s" % (MODID, stone), "stone.%s.%s.desc" % (MODID, stone)):
                if key not in data:
                    errors.append("%s: 缺少语言键 %s" % (lang, key))
        if "itemGroup.%s.main" % MODID not in data:
            errors.append("%s: 缺少创造模式标签页名称" % lang)


def check_recipes(items):
    recipe_dir = os.path.join(RES, "data", MODID, "recipes")
    if not os.path.isdir(recipe_dir):
        errors.append("缺少配方目录 %s" % rel(recipe_dir))
        return
    seen = set()
    for name in sorted(os.listdir(recipe_dir)):
        if not name.endswith(".json"):
            continue
        path = os.path.join(recipe_dir, name)
        data = load_json(path)
        if not data:
            continue
        result = (data.get("result") or {}).get("item", "")
        if not result.startswith(MODID + ":"):
            continue
        item = result.split(":", 1)[1]
        seen.add(item)
        if item not in items:
            errors.append("%s: 产物 %s 不是已注册物品" % (rel(path), result))
        pattern = data.get("pattern") or []
        keys = data.get("key") or {}
        used = set("".join(pattern).replace(" ", ""))
        if used - set(keys):
            errors.append("%s: 图案用到了未定义的 key %s" % (rel(path), sorted(used - set(keys))))
        unused = set(keys) - used
        if unused:
            warnings.append("%s: key 未被使用 %s" % (rel(path), sorted(unused)))
        for row in pattern:
            if len(row) != 3:
                errors.append("%s: 每行必须是 3 个字符，实际 %r" % (rel(path), row))
        if len(pattern) != 3:
            errors.append("%s: 必须是 3 行，实际 %d 行" % (rel(path), len(pattern)))
    missing = items - seen
    if missing:
        warnings.append("以下物品没有配方: %s" % sorted(missing))


def check_mods_toml():
    toml_path = os.path.join(RES, "META-INF", "mods.toml")
    props_path = os.path.join(ROOT, "gradle.properties")
    if not os.path.isfile(toml_path):
        errors.append("缺少 META-INF/mods.toml")
        return
    props = {}
    with open(props_path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    with open(toml_path, "r", encoding="utf-8") as fh:
        toml = fh.read()
    for placeholder in set(re.findall(r"\$\{([a-zA-Z0-9_]+)\}", toml)):
        if placeholder not in props and placeholder != "project":
            errors.append("mods.toml 占位符 ${%s} 在 gradle.properties 中不存在" % placeholder)

    pack = os.path.join(RES, "pack.mcmeta")
    if os.path.isfile(pack):
        with open(pack, "r", encoding="utf-8") as fh:
            text = fh.read()
        text = re.sub(r"\$\{([a-zA-Z0-9_]+)\}", "x", text)
        try:
            json.loads(text)
        except Exception as exc:  # noqa: BLE001
            errors.append("pack.mcmeta 展开后不是合法 JSON: %s" % exc)
    else:
        errors.append("缺少 pack.mcmeta")


def main():
    items = registered_items()
    if not items:
        errors.append("没有从 Java 源码里找到任何注册的物品")

    stones = []
    enum_path = os.path.join(JAVA_DIR, "com", "moyu", MODID, "item", "InfinityStone.java")
    if os.path.isfile(enum_path):
        with open(enum_path, "r", encoding="utf-8") as fh:
            stones = [s.lower() for s in re.findall(r'^\s{4}([A-Z]+)\("', fh.read(), re.MULTILINE)]

    check_jsons()
    check_models(items)
    check_lang(items, stones)
    check_recipes(items)
    check_mods_toml()

    print("已注册物品 (%d): %s" % (len(items), ", ".join(sorted(items))))
    print("宝石枚举 (%d): %s" % (len(stones), ", ".join(stones)))
    print()

    for warning in warnings:
        print("[WARN] " + warning)
    for error in errors:
        print("[FAIL] " + error)

    if errors:
        print("\n共 %d 个问题需要修复。" % len(errors))
        return 1
    print("\n全部检查通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
