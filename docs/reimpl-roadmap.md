# 把养老包的能力搬进 CoopCraft

养老包（GD656 极致养老整合包，Forge 1.20.1，313 个文件）和我们不是一个技术栈：

- 养老包是 **Forge 47.4.0 + Sinytra Connector**，靠搬运大量现成模组堆出内容；
- CoopCraft 是 **Fabric 1.21.1** 的独立模组，只有 74 个 Java 文件，玩法靠自己的代码。

所以"搬"只有两种含义：**装同类 Fabric 模组**（内容包，几 MB 到几百 MB），或者**自己重写系统**（几十到几百行 Java）。
本文只讨论后者——不复制任何第三方模组的代码或素材，只按玩法重写；Xaero、Create 这类 All-Rights-Reserved 或体量巨大的项目不在此列。

## 养老包里 290 个模组的去向

| 类别 | 数量级 | 处理方式 |
| --- | --- | --- |
| 内容包：Create、天境、暮色、BOP、Terralith、Tectonic、YUNG's 全套、Graveyard、Deeper Darker、Lost Cities、各类 Delight | ~150 | **不重写**（体量≈一个游戏），要就装对应 Fabric 1.21.1 版本 |
| 性能/修复：Embeddium、Radium、Starlight、ModernFix、FerriteCore、Krypton、Noisium、Saturn、Chloride 等 | ~25 | 已由整合包和专用服的 Fabric 版覆盖，不重写 |
| 界面/QoL：Xaero、Jade、REI、AppleSkin、物品栏 HUD、聊天头像、缩放、键位冲突 | ~35 | 整合包大多已有，缺的补装即可，不重写 |
| 系统/规则：传送、遗体、睡袋、守卫、背包、连锁挖矿、双开门、地名… | ~20 | **值得重写**，见下表 |
| 前置库/API、光影、资源包 | 其余 | 不需要 |

## 可重写清单（按"对多人开荒的价值 ÷ 工作量"排序）

| # | 玩法（养老包里的来源） | 在我们代码里的落点 | 工作量 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | 进入群系显示地名（TravelersTitles） | 纯客户端 HUD，读原版群系数据 | S，1 个文件 | ✅ 已完成（`BiomeTitles.java`） |
| 2 | 共享路径点（XaeroPlus 的共享标记） | 复用 `CoordsSharing` / `DeathPointPayload` 的网络套路，加一份存档数据 | S，3 个文件 | ✅ 已完成（`waypoint/` + `WaypointClient`） |
| 3 | 传送石/驿站网络（Waystones） | 新方块 + `SettlementSavedData` 的兄弟类 + C2S/S2C 包 + 客户端列表 | M，6–8 个文件 | 待做 |
| 4 | 遗体/墓碑（Corpse） | 死亡事件 → 放墓碑方块（BlockEntity 存物品）→ 右键取回 | M，4–6 个文件 | 待做 |
| 5 | 守卫村民（Guard Villagers） | 复用 `VillagerWorkGoal` / `MemberNeeds`，给聚落加"守卫"职业 | M，3–5 个文件 | 待做 |
| 6 | 连锁挖矿（FTB Ultimine 精简版） | 服务端校验工具+饥饿+耐久，最多 N 个同种方块 | M，2–3 个文件 | 待做 |
| 7 | 睡袋/帐篷（Comforts） | 新方块复用床的逻辑，不设置重生点 | S–M，2–3 个文件 | 待做 |
| 8 | 双开门、点击穿透、简易铁砧/附魔台（PuzzlesLib 之类） | mixin 或交互事件 | XS，各 1 个文件 | 待做 |
| 9 | 背包（Sophisticated Backpacks） | 物品 + 容器 GUI + 序列化 | L，10+ 个文件 | 暂缓 |

优先级理由：2 和 3 属于同一条"地图与移动"轴线，且能把现有网络层（`CoopNetworking`）、存档层（`SettlementSavedData`）和客户端界面（`SettlementScreen`）都串一遍，适合当骨架；4、5 直接服务"聚落开荒"主题；6、7、8 是顺手补的手感。

## 约定

- 每个功能单独一个 PR，带上说明和验证方式；客户端功能至少手动进游戏验证一次。
- 不改动 `gradle.properties` 的版本基线（1.21.1 / Fabric 0.20.1 / Java 21）。
- 新功能的网络包统一走 `com.thysummer.coopcraft.net`，命名 `<Feature>Payload`。
- 需要存档的数据统一放 `server` 侧 `SavedData`，不要在客户端存权威数据。
