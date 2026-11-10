# S0｜八人营地三重短缺：从需求发现到可见劳动协调（实验规格 v0.1）

> 日期：2026-11-04；研究深度：S0/L2；维度：D03、D05、D06、D08、D12。  
> **源码审查基线**：main `17296af707a08a334a94c4cb75b59472ec2dca5b`。  
> 性质：基于已实现的 Fabric 1.21.1 行为系统提出的**下一步可玩实验**，不是已经完成的实际功能。  
> 前置研究：[共同体需求与劳动协调](needs-and-labor-coordination-v0.1.md)、[家庭照料 #28](https://github.com/ThySummer14/coopcraft/issues/28)、[公共工程 #32](https://github.com/ThySummer14/coopcraft/issues/32)。

## A. 问题与具体可玩的结论

当缺粮、照料、建造同时出现时，**没有一个统一的上帝AI为全村分配任务**，但所有居民面对同一组真实物资、劳动和空间约束。居民独立作出有社会理由的决策，实际任务执行受到时段预留、现有承诺、路径和库存保护。玩家提供食物、参加施工或寻求协助，能改变营地未来结果；无人参与时生活仍继续。

首版目标不是复杂的社会制度法典，而是让玩家看见并理解一句话：“今天储藏棚停工，是因为材料搬运者正在帮助一户家庭寻找食物。”这句话必须能关联到实际行动与事件，而不是剧情旁白。

## B. 先核对的仓库事实

- **交互已经存在**：`src/main/java/com/thysummer/coopcraft/civilization/VillagerInteraction.java` 的非潜行右键可描述居民工作，潜行右键持真实食物可尝试给予。接受后物资实际上进入**模拟营地所有者或公共仓库**，不直接进该村民个人包裹。这种“公共贡献但对某位居民表达帮助”的语义需要明确提示或区分，不可假装已经实现直送。
- **工作角色已经存在**：`LaborAllocator.java` 按已加载成年人和人格选出觅食、采石、建筑等角色；当前不足3名成人通用劳动者。建议保留为专长/偏好，短期任务可因需求和承诺变化。
- **真实动作与施工存在**：`VillagerWorkGoal.java` 有采集/觅食/制作/建造/进食的执行逻辑；`ConstructionSystem.java` 实际逐块放置，材料从真实公仓扣除。不能复制第二套建造系统。
- **独立实验时间预算已存在**：`simulation/Person.java` 维护 `careHours`、`learningHours`、`socialHours`；`PrimitiveSocietySimulation.java` 从生产努力预算中扣除上述占用。但真实实体 `VillagerWorkGoal` 尚未使用统一、互斥的照料/采集/施工时段预留。
- **公仓规则已经存在**：`simulation/NormSystem.java` 处理不同居民对公仓口粮的请求、规则、执行和社交时间成本。不能直接用“总存粮足够”代替“每个家庭都确实能领取”。
- **测试设备**：`AGENTS.md` 规定构建使用JDK 25，目标字节码Java 21；`./gradlew build runGameTest`；已有开发探针 `tools/probe` 和 YAML 卡片 `tools/cards/settlement_roster.yml`。注意 dev-only 探针不进入发布jar。此文档没有实际运行上述命令。
- **阶段风险**：`SettlementStages.java` 采用不可下降的 `ordinal()` 进度，`ConstructionPlanner.java` 仅在原始阶段且缺火塘时计划火塘；与多路径文明和失修后重建存在风险。须单独代码评审，不能由本实验新建阶段树。

## C. 场景参数（**测试假设**，不属于人类学测量）

- 共8位登记居民，其中6位可工作的成年人、2位需照料儿童。
- 为第一版解释和复现，将每个成年人的每日**可灵活调度**劳动定义为2个时段，共12个时段；进食、基础休息另计。
- 测试日意向需求如下：食物获取5时段、照料2、工具维护1、住所施工3、材料运输2，总计13时段，缺口1时段。
- 上述13个时段只是刻意产生取舍的测试案例；**不得把“2时段=某实际劳动小时”写死为历史规律**。在真实 Mod 中必须把时段预留映射到行动成功/失败和实际 tick 时间，防止“建造动画未完成却后台结算工作”。
- 第一步只让 **食物、施工和材料供应** 成为可执行候选；照料在真实 Fabric 行为接入 #28 之前标识为**分析/诊断来源**，不伪造“孩子已经被照料”。

## D. 任务依赖图，不只靠绝对优先级

```text
可接近的资源点 + 工具/技能 + 人的可用时段
                      ↓
                 获取食物
                      ↓
                  食物转移
                      ↓
       本人/家庭/公共领取规则与实际进食

儿童需要照料 → 照料者位置/愿意/时段 → 他人可否离开营地参加采集

木石资源 + 运输/公仓 + 可施工现场 + 建筑者时段
                      ↓
                 实际建造方块
                      ↓
            可用床位/设施用途/维护请求
```

**任务候选两轮过滤**：先剔除不可能执行的事（材料不足、资源未知、时间重叠、无加载路径、权限缺失）；再由实际居民根据需求、社会关系与既有承诺选择和协商。不要全村共享同一个 `priority=100` 食物信号就让全体居民同时抢同一浆果灌木。

## E. 三条让玩家亲眼看到的历史分支

### 分支 A：居民自行暂缓施工

- 发现当前公仓口粮不足，并非只是“低于固定库存整数”；需结合真实消费需求、居民实际领取权利和可获得食物。
- 原已准备施工的居民判断当前更适合觅食或维护采集工具，主动**暂停但不取消**工程；物资留在唯一权威库存，已完成方块不消失。
- 状态面板显示：“储藏棚暂缓：木匠塔恩去采集；已完成 8/20 方块”，后续若储备恢复自动尝试继续。
- 没有全员投票，没有强制所有村民改职业；玩家也可完全旁观。

### 分支 B：玩家带来真实食物

- 玩家持有真实食物进入营地。通过既有 `VillagerInteraction` 或公共仓交付，明确标记交付性质：`PUBLIC_CONTRIBUTION`（公共储备）/ `DIRECT_AID`（针对个人的帮助，目前未实现，需独立评审）。
- 只有经服务端实际交付并可正常分配的食物，才可以更新需求估计。
- 当粮食紧张程度缓和，部分居民可能愿意履行原先的施工承诺；不能把玩家赠予的物资复制为两处库存。
- 村民记录对玩家的实际社会经历，不自动将食物折算为首领忠诚。

### 分支 C：玩家不交食物，转而提供劳动

- 玩家帮助采集、运送或直接施工；减少居民需要承担的某个真实任务。
- 若玩家施工绕过居民正常生产过程，要明确Minecraft实际方块完成时间和库存消耗，并在事件中记录“玩家施工”，不凭空增加 NPC 劳动产出。
- 一些居民仍可能觉得工程优先性太低而选择觅食。玩家不是统一总指挥。

**重要边界**：如果采集点已枯竭，投入更多劳力不一定有食物；如果材料确实短缺，空有建筑者也无法施工。这些失败也是符合因果的结果。

## F. 五个跨时代可复用对象

```java
// 伪结构：先映射到现有数据，再决定新Java类型
record ObservedNeed(UUID id, UUID settlementId, NeedKind kind,
                    UUID observerId, long observedDay, int urgency,
                    List<Long> causeEventIds) {}

record TaskOption(UUID id, UUID needId, UUID actorId, WorkKind work,
                  int plannedSlots, List<RequiredItem> inputs,
                  BlockPos site, String constraint) {}

record WorkCommitment(UUID id, UUID actorId, UUID taskId,
                      long simulationDay, int slotIndex,
                      CommitmentState state) {}

record ActionOutcome(UUID commitmentId, boolean completed,
                     int actualWorkTicks, List<Long> transferEvents,
                     String failureReason) {}

record DecisionExplanation(UUID actorId, UUID taskId,
                           List<String> consideredReasons,
                           List<Long> evidenceEvents) {}
```

- **ObservedNeed**：公共视图只显示已经被传达或实际观察到的需求；每个人不获得全知。
- **TaskOption**：符合技能、材料、位置、规则和家庭责任。
- **WorkCommitment**：锁定一个可执行时段，不得与照料/学习/另一项劳动双占。
- **ActionOutcome**：实际任务的结果才是生产账本的提交依据；中断至少记录耗费时间，不把未完成产出结算给居民。
- **DecisionExplanation**：向玩家和 dev 探针解释为何选择/延后工作，可复现关键因果。

当一个时段里有兼容的行动，例如近距离烹饪兼顾照看，须显式标记兼容集合及成本系数；首版默认一时段只进行一个占用，避免“免费照料”。

## G. 应当怎样接入 Fabric 已有代码（最小改动顺序）

1. **读取事实**：`SettlementNeeds` + `SettlementSimulationService`/公仓的当前事实，新增只读 `NeedSnapshot`（不要复制库存）。`ConstructionPlanner` 从当前缺床/火塘判断建设候选。
2. **轻量规划**：`SettlementTaskPlanner`（建议名字）按聚落/居民分别产生候选任务，优先判定硬约束，再由各居民行为策略排序。该服务不直接扣物资，也无自动强制指挥权。
3. **时段/动作桥接**：`ResidentWorkReservations`（概念）服务端保存、使用不可重复的 `commitmentId`；`VillagerWorkGoal` 查询本人有效承诺，继续复用现有 `GATHER/FORAGE/CRAFT/BUILD/EAT`。
4. **施工暂停**：`ConstructionSystem` 保留 `ConstructionJob` 的 `nextIndex`、实际方块与库存进度。紧急觅食导致的是任务不调度，不等于 `cancelConstruction`。
5. **结果反馈**：记录任务选择、行动成功/失败、材料消耗、关系影响和新观察到的需求；已有 `EventLedger` 可扩展关联，不要再建第二份“影子仓库”。
6. **玩家可见**：`VillagerInteraction`（既有）显示个人原因；聚落名册新增最多三条营地公共问题并标注“未知/已知/阻塞”，可通过现有 `SettlementOverviewPayload` 扩展，新增网络字段必须服务端验证、检查包大小并保持中英双语。
7. **开发测试**：纯函数规划器 JUnit → Fabric GameTest 守恒和任务冲突 → dev-only `tools/probe` 的实际客户端界面与 YAML 卡片 → 两人联机玩家参与。不要以纯模拟单测代替实机。

**不要求第一版直接实现“玩家指派某NPC去照料”，因为那会重复绕过社会同意。** 先展示实际照料约束并确保已有生存/工作链条不矛盾。

## H. 实验与验收（先做可重复的小场景）

### 场景一：食物充足/短缺

- 相同NPC/工具/地图/种子，只改变真实可领食物量。
- 观察建造继续概率、完成方块数、觅食时间、真实进食次数与失败原因。
- 不要求缺粮下100%停止所有建造；要求每一个行为可追溯、不会导致无理由全村同步跳转。

### 场景二：有/没有儿童照料占用

- 在真实照料动作上线前只跑抽象模拟；上线后控制其他条件，验证实际照料时段影响可用建筑/采集工时。
- 不根据性别强制分配照料或外出劳动。

### 场景三：玩家贡献 vs 不参与

- 持有真实物品、交付失败/成功、饱食与公仓领用资格变化。
- 对同一村民记录两次相同交付必须实际扣除两份物资；拒绝时物资不扣、库存满时退回。
- 需要重复游玩种子，避免把一次偶然行为误认成制度必然规律。

### 边界场景

- [ ] 6名成年人各2时段，系统最多锁定12个互斥时段，不能承诺13个。
- [ ] 角色分配随已加载人口变化不会让居民凭空中断同一真实施工动作。
- [ ] 工人正在搬木头时缺粮，只在合适的中断点安全暂缓，工具/材料不丢失。
- [ ] 施工已消耗8块石头并放置8个方块时暂停，不会删除方块或全额退款。
- [ ] 玩家交付食物无法绕过 `CARE_FIRST_COMMON_STORE` 分配规则。
- [ ] 无资源、无路径、居民未加载/死亡/迁移时不能结算“看不见的劳作”。
- [ ] 重载两次不多扣材料、不再执行已完成劳动承诺。
- [ ] 客户端伪造任务请求/拒绝他人的请求无效，只有服务端能改权利或资源。
- [ ] 当没有玩家时，仍能够在已加载营地形成至少一条完整的自助生产路径。
- [ ] 历史/当前状态分离的后续议题：`SettlementStage` 为 AGRICULTURAL 后火塘被破坏，验证现有 `ConstructionPlanner` 行为并单独修正；不作为本切片的主要实现。

## I. 知识依据和范围

- Elinor Ostrom (2005), *Understanding Institutional Diversity*：以行动情境、信息、权限、成本收益分析不同制度，不意味着所有早期共同体都有统一议事会议。https://digitalcommons.usu.edu/unf_research/54/
- Smith et al. (2018), *Hunter-Gatherers Maintain Assortativity in Cooperation despite High Levels of Residential Change and Mixing*：2010–2016年56个Hadza营地383人，合作行为与当前营地社会情境关联，不能硬编码稳定“自私/合作职业”。https://pubmed.ncbi.nlm.nih.gov/30245106/
- 2022年跨12个采集/混合生计群体的690名儿童时间分配研究：儿童劳动、照料、游戏比例存在显著差异，不应统一童工和照料角色。https://www.nature.com/articles/s41598-022-12217-1
- Dyble et al. (2016) 两个采集群体食物分享网络含家庭、跨家庭分享簇和营地等层级；为“单个家庭 vs 公仓”之外的合作提供经验线索，但**不能直接复原所有史前社会**。https://pubmed.ncbi.nlm.nih.gov/27451900/

## J. 达到L2的首个冻结条件

- 玩家在5–15分钟内能看懂：营地哪几项需求冲突、村民为什么暂缓某项劳动。
- 一项现实物资变化或一次实际劳动援助能改变至少一条后续NPC行动路径。
- 无玩家时居民仍自主生活；物资、实际工作和历史事件可追溯。
- JUnit + Fabric GameTest + dev-only YAML 客户端场景跑通；不突破服务端单一权威事实。
- 成立后冻结这一版接口，后续制度深研进入新实验，不无限加属性。

## K. 本轮重要新观察

`VillagerInteraction.offerFood` 当前接受后进入公共库存而非某居民个人包裹；这与“个人帮助”玩家期待存在语义落差。优先调整交互提示/两种赠予类型，不建议没有权限设计就新增隐蔽的居民私有食物箱。

`AGENTS.md` 表明**构建JDK25、产物Java21**，旧文档所写“Java21构建”不足以代表最新环境。开发者请使用该文件给出的具体命令、中文输出与PR协作约定。
