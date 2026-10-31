# 优化模组原理与 GD656 养老包解剖

本文回答两个问题：那些"优化模组"到底改了什么，以及 GD656 养老整合包为什么这么搭。结论在文末，想直接看建议可以跳到第 7 节。

## 1. 先看瓶颈在哪

Minecraft 的一帧和一 tick 是两条独立的流水线：

| 流水线 | 主要工作 | 出问题时的表现 |
| --- | --- | --- |
| 渲染（每帧，客户端） | 区块网格构建 → 上传显存 → 视锥剔除 → 提交 draw call → 方块实体/GUI 绘制 | FPS 低、帧时间抖动、走动时卡顿 |
| 逻辑（每 tick，服务端或单人世界） | 实体 AI、方块/流体 tick、区块加载、实体追踪、网络发包 | MSPT 高、TPS 掉、交互延迟 |
| 内存 | 方块状态/模型数据、区块数据、纹理 | GC 卡顿、启动慢、长时间玩越来越卡 |

原版的低效点很集中：**每个 16³ 的 section 单独构建网格、单独提交 draw call；所有构建都在主线程；看不见的面照样算；方块实体每帧重新渲染；tick 逻辑里大量线性查找和冗余计算。** 优化模组基本上就是对这几件事做替换。

## 2. 客户端渲染类

### 2.1 Sodium / Embeddium —— 重写区块渲染管线

这是收益最大的一类。Sodium 不"修改"原版渲染，而是整个替换掉 `WorldRenderer` 的区块渲染部分，思路是：

- **region 化**：把 section 按 8×4×8 聚成 region，用区域级可见性图（occlusion graph）做剔除，而不是逐 section 判断视锥，判断次数降一到两个数量级；
- **紧凑顶点格式**：每个顶点的位置/颜色/UV/光照打包压缩，显存带宽和内存占用都下降；
- **异步网格构建**：脏 section 交给自己的线程池重建，主线程只做提交，消除"走过去卡一下"；
- **按图层/材质排序合并**，减少渲染状态切换和 draw call 数量。

典型收益 2–5 倍 FPS，帧时间更稳。`Embeddium` 是 Sodium 的 Forge/NeoForge 移植版，`Chloride`、`Rubidium Extra` 是它的附加优化与设置项（粒子、云雾、实体距离等）。

### 2.2 Iris / Oculus —— 光影

不是性能模组，是把 OptiFine 光影的管线接到 Sodium 上。开光影后帧率主要由光影 shader 决定，Sodium 的剔除仍生效。

### 2.3 剔除类：少画看不见的东西

| 模组 | 做法 | 收益场景 |
| --- | --- | --- |
| EntityCulling | 从相机到实体做遮挡射线（异步），被完全挡住就整只不渲染 | 刷怪塔、村庄、地下洞穴 |
| MoreCulling | 按邻居方块状态剔除面：箱子、树叶、玻璃板、告示牌、信标光束等原版仍会画的面 | 大型建筑、树林 |
| cullleaves | 树叶之间相邻的面剔除（fancy 树叶尤其明显） | 森林、树场 |

这三个都只影响客户端渲染，不改游戏逻辑，风险低。

### 2.4 Enhanced Block Entities（EBE）

箱子、告示牌、床、潜影盒这些"方块实体"在原版里是每帧重新走一遍渲染路径的。EBE 把它们烘焙成静态方块模型，只在状态变化时更新，顺带修掉一些视觉 glitch。对箱子仓库、告示牌密集区收益明显。

### 2.5 Continuity

连接纹理（玻璃、石砖）原本靠 OptiFine 的 CTM 逐方块拆 quad，CPU 开销大；Continuity 用纹理图集里的多张 sprite 实现，几乎零额外开销。

### 2.6 ImmediatelyFast

GUI/HUD 的绘制走的是 "immediate mode"：每段文字、每个物品图标都是一次独立提交。ImmediatelyFast 把它们攒进缓冲区批量提交，聊天框刷屏、打开带大量物品的容器时收益明显。

### 2.7 Dynamic FPS

窗口失焦/最小化时把帧率压到个位数，后台挂机不再吃满 GPU。纯客户端，无副作用。

### 2.8 视距类

- **Distant Horizons**：用低精度 LOD 网格渲染远处地形，把"看得见"的距离拉到几百格，代价是内存和兼容性（与光影、部分模组冲突较多）；
- **Starlight**：重写光照引擎（异步光照更新），1.18–1.19 时代收益很大；1.20 之后原版改善，收益变小且容易和别的模组打架。

## 3. 服务端逻辑类

### 3.1 Lithium / Radium —— tick 逻辑重写

Lithium 用等价但更快的实现替换掉大量原版逻辑：实体 AI 目标选择、寻路、碰撞检测、区块加载与实体追踪、方块/流体 tick、红石、数学与集合操作。它的原则是**行为等价**（有专门的测试对比原版结果），所以对玩法没有影响，只是更快。典型 MSPT 下降 20%–50%，实体越多收益越大。`Radium` 是它的 Forge 移植。

### 3.2 Krypton / KryptonReforged —— 网络栈

替换 netty 里的压缩实现（libdeflate 等更快的 deflate）、减少每包的临时分配、合并小包。多人服玩家越多、区块传输越频繁，收益越大。

### 3.3 c2me —— 区块生成并行化

把区块的生成、加载、序列化拆到多个线程（噪声计算、结构放置、光照）。能显著减少"跑图时的区块卡顿"，但它打破了原版对线程安全的隐含假设，**标注为 alpha**，和部分模组会崩。

### 3.4 Noisium —— 世界生成加速

缓存并优化噪声采样与密度函数插值，加快新区块生成。属于"生成更快"，不是"运行时更流畅"。

### 3.5 ServerCore（Forge 侧）

一整套服务端优化的合集：动态视距、实体激活范围、区块 tick 间隔、掉落物合并等。**部分选项会改变原版行为**，需要按需配置。

### 3.6 其他

- **Clumps**：把挨在一起的经验球合并成一个实体（视觉上仍是多个），减少实体数；
- **ThreadTweak**：调线程优先级，让 tick 线程少被 GC/IO 抢占，属于"稳住帧时间"而不是"提高平均性能"；
- **Chunky**：预生成区块的工具，把跑图卡顿提前到开服前消化掉；
- **spark**：采样剖析器，用来找真正的瓶颈，不直接提升性能。

## 4. 内存与启动

| 模组 | 机制 |
| --- | --- |
| FerriteCore | 对方块状态的 multipart 模型、属性表做去重和紧凑存储，堆占用能降几十到上百 MB，渲染结果不变 |
| ModernFix | 并行化资源/模型加载、跳过不必要的注册表处理（dynamic resources），缩短启动时间、降低内存峰值 |
| Saturn | 对象池与分配优化，减少 GC 压力 |
| MemoryLeakFix / AllTheLeaks | 修具体的泄漏点（原版和常见模组），长时间挂机才有意义 |
| SmoothBoot (Reloaded) / Fastload (Reforged) | 启动阶段的线程与加载优化，只影响开游戏那几十秒 |

## 5. 兼容性与风险

- **mixin 冲突**：ModernFix、Lithium、c2me、ServerCore 都会改同一批类，组合使用偶发崩溃，出问题先按"最近加的那个"回退；
- **c2me 是 alpha**，与结构/生成类模组冲突概率最高；
- **纯客户端模组装服务端无收益**（Sodium、EBE、EntityCulling 之类），服务端只留逻辑与网络类；
- **光影与剔除类模组**在个别 shader 下会闪面，需要单独测试；
- **版本跟进**：优化模组通常滞后于游戏版本几天到几周，升级 MC 时要一起等。

## 6. GD656 养老包解剖

`yanglao--GD656极致养老整合包_Alpha0.0.5_PCL.json`：**Minecraft 1.20.1 + Forge 47.4.0**，313 个文件 = 290 个模组 + 21 个资源包 + 2 个光影包。按文件名粗分：

| 类别 | 约数 | 代表 |
| --- | --- | --- |
| 前置库/API | 51 | Architectury、GeckoLib、Bookshelf、PuzzlesLib、Kotlin for Forge、Connector |
| 性能/修复 | 27 | Embeddium、Oculus、Radium、KryptonReforged、FerriteCore、ModernFix、Noisium、EntityCulling、ServerCore、Distant Horizons |
| 世界生成/结构 | 34 | Terralith、Tectonic、Biomes O' Plenty、Regions Unexplored、Better End/Nether、YUNG's 全家桶、CTOV、Towns & Towers |
| 维度/冒险 | 19 | 天境（Aether + Redux + Deep Aether + Aether Villages）、暮色森林、Deeper and Darker、The Graveyard、Mowzie's Mobs |
| 建造/装饰 | 14 | Supplementaries、Amendments、Handcrafted、Another Furniture、Waystones |
| 食物/农业 | 14+ | Farmer's Delight 及其十余个扩展、Aquaculture |
| 科技/背包 | 12 | Create 6.0.5、Sophisticated Backpacks、Immersive Aircraft、EMI |
| 界面/QoL | 35 | Jade、Xaero 三件套、AppleSkin、Controlling、ShulkerBoxTooltip、EasyAnvils/EasyMagic |
| 视觉/氛围 | 48 | Distant Horizons、Particular、Falling Leaves、Presence Footsteps、Ambient Sounds、3D Skin Layers |
| 联机/皮肤 | 7 | e4mc、mcwifipnp、No Chat Reports、CustomSkinLoader、ViaForge |

**技术路线值得注意**：它用 **Sinytra Connector 1.0.0-beta.46 + Fabric API 0.92.2** 在 Forge 上直接跑 Fabric 模组（Distant Horizons、Dynamic Surroundings 等的 Fabric 构建），这是 1.20.1 Forge 生态里很常见的"缝合"方案。

**它的取舍**：内容模组占了三分之二，性能层只是"够用"（没有 c2me，没有激进的服务端改动），目标是**长期存档不崩、内容多、随时能玩**——"养老"就是这个意思：不追求帧数极限，追求版本稳定和存档安全。1.20.1 + Forge 47.4.0 也是目前整合包作者公认最稳的组合之一。

## 7. 对 CoopCraft 的建议

我们的情况不同：**1.21.1 + Fabric，5 人以内联机**。人少、实体少、区块少，所以瓶颈几乎总在**客户端渲染**，服务端 tick 基本不会是问题。

### 客户端（`modpack/dist` 里已配好 48 个）

Sodium、Iris、FerriteCore、ModernFix、ImmediatelyFast、EntityCulling、MoreCulling、EBE、Continuity、cullleaves、Dynamic FPS、Particle Core、BadOptimizations 等，覆盖了第 2、4 节的所有机制。跑不动再考虑 Distant Horizons（内存换视距，且和光影冲突）。

### 服务端（可选，建议 4–6 个）

| 模组 | 理由 |
| --- | --- |
| Lithium | 等价重写 tick，零行为风险，最该装 |
| Krypton | 网络压缩更快，区块传输省 CPU |
| FerriteCore | 降内存，长时间开服更稳 |
| ModernFix | 缩短重启时间（我们改代码要频繁重启） |
| Clumps | 经验球合并，刷怪场景省实体 |
| spark | 只在排查时临时装，用来定位真正的卡顿源 |

**不建议装**：c2me（alpha，5 人服收益小、崩服成本高）、ServerCore（会改行为，和我们的玩法冲突）、Distant Horizons（服务端不需要）。

### 怎么验证有没有用

1. 单人进游戏按 F3，看右侧 `ms/t` 和帧率；
2. 服务端点 `spark profiler start` → 玩 5 分钟 → `spark profiler stop`，看热点；
3. 大世界用 `chunky` 预生成，避免玩家跑图时服务端卡；
4. 改造前后各跑一次 `./gradlew runGameTest`，确认逻辑没被优化模组影响（虽然优化模组不参与 game test）。

### 不要做的事

- 不要把 1.20.1 的 jar 直接往 1.21.1 上塞（渲染管线每年都在改，必崩）；
- 不要同时装 Sodium 与 OptiFine 系模组；
- 不要为了"帧数好看"叠一堆小优化模组（GPUBooster、Flerovium 这类实验性项目收益不稳、崩溃难查）；
- 不要在没有 back up 的情况下往正在跑的服务端加模组——先停服、备份 `world/`、再加。
