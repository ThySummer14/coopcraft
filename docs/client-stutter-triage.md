# CoopCraft 双人联机画面卡顿排查（2026-11-04）

关联：[Issue #23](https://github.com/ThySummer14/coopcraft/issues/23)

## 结论与证据等级

这是**可执行的测试计划，不是已完成的真实客户端性能实测**。

已知玩家反馈：

- 至多两个人，同省，均为中国移动线路。客户端通过手机流量热点或 USB 网络共享上网。
- Tailscale 已经显示过直连，但两种连接方式都存在明显的画面掉帧感。
- 用户观察内存及其他系统资源有余量；尚无 F3 帧时间、单核心 CPU、显卡驱动、GC 或 spark 采样数据。

所以将「购买 VPS / 解决 DERP 中继」降为低优先级。直连不等于完全无丢包，但 VPS 无法直接修复本地帧间隔尖峰。必须区分：画面 FPS/帧时间卡顿、服务器 TPS 卡顿、网络 RTT/抖动。

### 静态代码审阅发现（候选热点，不代表确定根因）

1. **ItemInHandRendererMixin.java**：持剑、斧、镐、锄时接管第一人称手/工具的原版渲染。
2. **WeaponRenderer.java / WeaponMesh.java**：每帧自定义武器渲染读取模型 sprite；网格本身已经缓存。是否昂贵仍待真实采样。
3. **VillagerWorkGoal.java / Civilization.java**：村民聚落归属、建筑目标和 tick 逻辑存在可测量的潜在热点，但服务端 AI 不一定会导致客户端 FPS 降低。
4. **modpack/README.md**：实际实例记载 81 个 JAR、212 个模组条目（212 并非独立 JAR 数量）。光影、动态光源、地形/实体渲染等都应作为 A/B 候选。

## 本 PR 增加的测试工具

延用现有仅供开发使用的 tools/probe：

- 在 WorldRenderEvents.END 之间测量**帧到帧的墙钟时间间隔**，保留最近 4096 帧；提供 p50/p95/p99、最大帧间隔、平均 FPS、1% low FPS。
- 累计记录自重置以来超过 50ms 和 100ms 的长帧次数。
- 提供仅监听 localhost 的开发探针接口 GET /perf/frame 和 POST /perf/reset。
- tools/probe/collect_perf.py 周期读取客户端 /status 和 /perf/frame、可选服务端 /status，输出脱敏 JSON 至 build/perf/。
- tools/probe/tests/test_collect_perf.py 是离线单元测试，不需要启动 Minecraft。

**测量限制：**帧到帧的间隔包含 VSync、帧率上限、GPU 等待、GC、切换后台等影响，不是单个渲染方法的 CPU 时间。服务端轮询 tick 差分是粗略 TPS 估计，不是 MSPT；localhost 的 HTTP 响应时间也不是玩家到服务器的网络 RTT。

## 开发环境中可以复现的测试步骤

本测试由能访问开发机的维护者/Agent 执行，不需要用户手动配合。**GitHub Actions 无图形环境，不能替代真实 OpenGL 客户端。**

准备和启动本地探针（探针绝对不能放进正式整合包或生产服务器）：

    tools/probe/prepare_dev_run.sh
    ./gradlew runProbeServer
    # 另开终端
    ./gradlew runProbeClient

让 dev 客户端进入**正在渲染的游戏世界**，而不是停留在主菜单，然后运行：

    python3 tools/probe/collect_perf.py --duration 90 --interval 1 \
      --with-server --scenario idle-empty-hand \
      --output build/perf/idle-empty-hand.json

每次运行前脚本会重置客户端采样计数。建议同一场景重复三轮，每轮至少 90 秒，在相同分辨率、渲染距离、帧率限制、世界/区块状态、驱动和硬件下对比。

| 场景 | 对照变量 | 判断方向 |
| --- | --- | --- |
| A：空手、原地静止 | 基准、关闭光影 | 客户端固定卡顿 |
| B：手持剑/镐/斧静止 | 只改手持物品 | CoopCraft 自定义视图模型 |
| C：走过已加载区块 | 与 A 比 | 渲染、实体、chunk mesh |
| D：探索全新地形 | 与 C 比 | 世界生成和区块传输 |
| E：村民/聚落集中处 | 与空旷处比 | 实体渲染 vs 服务端村民 AI |
| F：相同存档单人/本地专服 | 只改运行模式 | 客户端与服务端分离 |
| G：完整包关闭/开启光影 | 其他设置保持不变 | 光影、GPU、驱动 |

如需隔离模组，只能先复制 PCL 测试实例，然后按功能分组逐步禁用；内容模组存在依赖关系，不要在正式存档中随意删除。

## 如何判断测试结果

- **B 比 A 明显差**：检查 ItemInHandRendererMixin、WeaponRenderer、模型顶点数量及每帧 sprite 获取。先对照，不要直接改玩法。
- **D 比 C 明显差**：优先分析区块生成、磁盘 I/O 和相关服务器火焰图。
- **E 差且 MSPT 有峰值**：重点调查 VillagerWorkGoal、Civilization.tick 的寻路、扫描和重复查询。
- **所有场景 p99 较差而 TPS 稳定**：客户端渲染/驱动/模组冲突/GC 更可疑。
- **FPS/帧时间平稳但方块与实体回弹**：再调查 Tailscale、移动网络的实际 RTT、丢包和 jitter；才考虑 VPS。
- **available=false、windowFrames=0**：需要真实客户端进入世界，确保探针运行且有 WorldRenderEvents.END 回调。

关键指标是 p99Ms、onePercentLowFps、totalOver50ms、totalOver100ms，不要单独用平均 FPS 或系统总体内存占用判断。结合服务端 spark 的 TPS/MSPT、日志中的 Can't keep up、卡顿时 GC 信息，才能定位具体瓶颈。

## 验证与状态（务必区分）

| 项目 | 状态 |
| --- | --- |
| 网络、自定义武器渲染和村民 tick 静态审阅 | 已审阅，未定性 |
| dev-only 帧间隔探针 / 自动采样器 / 离线测试 | 本分支已提交，CI 编译结果待验证 |
| 真实游戏帧时间、CPU/GPU、GC 与服务器 MSPT | **没有采样数据，未执行** |
| 手机流量的实时 RTT/丢包 | **未执行**；已知 Tailscale 曾直连 |
| 正式服务器、正式存档、客户端整合包修改 | **没有** |

构建产物和采样输出仅保存在 Git 忽略的 build/ 目录。切勿上传真实 IP、玩家姓名、令牌、私人日志或世界存档；必要时仅将脱敏摘要贴回 Issue #23。

## 下一步

1. CI 检查探针能否在现有 1.21.1 Fabric 环境编译，以及 Python 摘要测试是否通过。
2. 有真实开发客户端运行环境时，按 A-G 场景执行采样，将脱敏数据添加到 Issue #23。
3. 只有定位出热点之后，才新开代码修复 PR；不在无数据情况下修改村民 AI tick、整合包版本、网络栈或 JVM 参数。
