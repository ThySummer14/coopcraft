from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch

OUT = Path(__file__).parent / "images" / "coopcraft-core-loop.png"
OUT.parent.mkdir(parents=True, exist_ok=True)

plt.rcParams["font.family"] = ["Arial Unicode MS", "PingFang SC", "sans-serif"]
fig, ax = plt.subplots(figsize=(14, 8), dpi=160)
fig.patch.set_facecolor("#F7F4EC")
ax.set_facecolor("#F7F4EC")
ax.set_xlim(0, 14)
ax.set_ylim(0, 8)
ax.axis("off")

nodes = [
    (1.0, 4.7, "共同立项", "朋友决定下一座建筑、\n道路或基地设施", "#D9A441"),
    (5.0, 6.0, "分头准备", "采集、种植、制造，\n自愿认领材料", "#758E4F"),
    (9.2, 4.7, "结伴远征", "探索原版结构，寻找\n资源、遗物与新地点", "#4F7F91"),
    (9.2, 1.7, "带回与展示", "物资进入工程箱，\n纪念物留在共同据点", "#8A638A"),
    (5.0, 0.6, "建设与自动化", "玩家亲手建造，机械\n减少重复劳动", "#A8623B"),
    (1.0, 1.7, "据点成长", "竣工庆祝与新设施，\n开启下一轮共同目标", "#526C5B"),
]

for x, y, title, body, color in nodes:
    box = FancyBboxPatch(
        (x, y), 3.4, 1.45,
        boxstyle="round,pad=0.12,rounding_size=0.15",
        linewidth=1.8, edgecolor=color, facecolor="#FFFDF8",
    )
    ax.add_patch(box)
    ax.text(x + 0.22, y + 1.02, title, fontsize=18, fontweight="bold", color=color, va="center")
    ax.text(x + 0.22, y + 0.47, body, fontsize=12.5, color="#343832", va="center", linespacing=1.45)

centers = [(x + 1.7, y + 0.725) for x, y, *_ in nodes]
for start, end, color in zip(centers, centers[1:] + centers[:1], [n[4] for n in nodes]):
    arrow = FancyArrowPatch(
        start, end, arrowstyle="-|>", mutation_scale=19,
        linewidth=2.0, color=color, connectionstyle="arc3,rad=-0.12",
        shrinkA=69, shrinkB=69,
    )
    ax.add_patch(arrow)

ax.text(7, 7.65, "CoopCraft 长期合作玩法闭环", ha="center", va="center", fontsize=25, fontweight="bold", color="#2E382F")
ax.text(7, 3.65, "核心不是通关，而是让每次游玩\n都在共同世界留下可见成果", ha="center", va="center", fontsize=17, color="#465148", linespacing=1.5)
ax.text(7, 0.1, "自由建造 · 轻量自动化 · 原版探索 · 2–5 人长期存档", ha="center", fontsize=12.5, color="#72786F")

fig.savefig(OUT, bbox_inches="tight", facecolor=fig.get_facecolor())
print(OUT)
