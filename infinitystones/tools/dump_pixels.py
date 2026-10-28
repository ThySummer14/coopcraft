"""把生成的贴图导出成 ASCII 点阵，方便在没有图片的情况下核对像素。

符号：
    .  透明        #  外描边        0 1 2 3  金色由深到亮
    *  高光        S  宝石座阴影    1-6     宝石（按镶嵌顺序）
"""
import sys

sys.path.insert(0, r"D:/HUANCUN/2026-10-28-13-30-55/infinitystones/tools")
import gen_textures as g  # noqa: E402

SYMBOL = {
    g.OUTLINE: "#",
    g.GOLD_DEEP: "0",
    g.GOLD_DARK: "1",
    g.GOLD_MID: "2",
    g.GOLD_LIGHT: "3",
    g.GOLD_SPEC: "*",
    g.SOCKET: "S",
}
for idx, (_name, color) in enumerate(g.STONES, start=1):
    SYMBOL[g.shade(color, 1.45)] = str(idx)
    SYMBOL[g.shade(color, 1.05)] = str(idx)


def dump(rows, title):
    print("   " + "".join(str(i % 10) for i in range(g.SIZE)))
    for y, row in enumerate(rows):
        line = ""
        for px in row:
            if px[3] == 0:
                line += "."
                continue
            line += SYMBOL.get(px[:3], "?")
        print("%2d %s" % (y, line))
    print("   " + title)
    print()


if __name__ == "__main__":
    for count in (0, 3, 6):
        dump(g.build_gauntlet(count), "gem_count=%d" % count)
