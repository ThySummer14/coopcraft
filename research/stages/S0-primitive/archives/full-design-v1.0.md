> **历史版本 / 已被后续设计修订。** 本文保留原始研究过程与理论依据，不作为当前工程的直接开发指令。项目已确定为 **Fabric + Minecraft 1.21.1**，并有大量已实现的社会模拟功能；文中任何 NeoForge 方案、工程起点、进度或架构建议均已过期。开发前以仓库当前源码、[文明系统主线设计](../../../../docs/civilization-design.md)、[研究体系 v1.1](../research-framework-v1.1.md) 为准。

---

# 《文明演化》Minecraft Mod：原始社会完整设计与 Agent 交接规格

> **版本**：v1.0（2026-10-29）  
> **性质**：历史与人类学研究整理 + 游戏设计 + 可开发系统规格 + 任务拆分  
> **当前阶段**：原始社会文明阶段；**不开发**奴隶社会、封建社会、现代经济系统  
> **继承文档**：`primitive_society_economy_design_v0.1.md`。本文件整合前七轮讨论并补充 Minecraft 接入方案；如有冲突，以本文件为下一步讨论基准，**不得覆盖 Agent 已完成代码中的有效设计，必须先对照现状**。  
> **状态声明**：这是一份设计交接稿，并不代表任何功能已经实现。文中所有时间尺度、系数、人口数量和实验阈值都是**待校准的游戏假设**，不是历史定律。  
> **给开发 Agent 的原则**：先审计当前代码和既有设计，再做增量实现。每个功能都应有可验证的行为、可追溯的记录、可回归的自动化测试。不要自行改成固定“时代升级树”。

---

## 0. 一页概要：我们到底在做什么

项目目标不是“史前装饰包”“村民职业强化包”或“原始时代科技树”，而是一个**基于历史唯物主义视角、吸收现代考古学与人类学、多智能体自下而上演化的 Minecraft 社会模拟 Mod**。

- 玩家在 Minecraft 世界中生活、劳动、制作、探索、与村民交往，具有现实影响，但不是全知全能的文明管理员。
- 村民具有生存需求、劳动能力、有限知识、不同社会关系及个人经历，能自主生产、消费、分享、学习、迁徙和协商。
- 生产资料与生活用品、实际保管与所有权、权利主张与社会承认要区分。
- 资源、技术和生产力的发展可能改变财产和社会关系，但**不自动生成私有制、阶级、首领、国家或下一个时代**。
- 一个原始社会内部就允许出现流动采集群体、半定居采集群体、早期栽培聚落、复杂无国家共同体，以及混合形态。
- 平等不是自动平均分配。不平等也不是只看某人箱子里有多少东西。要模拟维护平等的行动，以及权利集中和依附关系形成的条件。
- 模拟需要**可观测、可解释、可复现、可回滚**，具有后台社会核心与前台 Minecraft 表现的分离架构。

### 必须满足的九条不可违背规则

1. **物资不能凭空生成**：原始资源来自世界或明确配置的初始存量，转换、搬运、消耗和损坏都记账。
2. **时间不能重复使用**：居民不能同时捕鱼、睡觉、照料儿童和建造房屋。
3. **信息不能全知共享**：居民只能根据自己获得的消息、观察和知识行动。
4. **权利不能只由字段赋予**：财产控制必须具有主张、承认、执行、争议及持续性。
5. **制度不能直接替代行为**：规则影响行为概率与后果，但没有绝对控制所有居民的魔法权力。
6. **技术不能全球瞬时解锁**：知识先属于具体居民，再通过教学、观察、交往传播，并可能失传。
7. **社会形态不能自动升级**：阶段标签只做观察性描述，不触发固定历史剧本。
8. **远处与近处不能双重结算**：后台聚落与加载实体只存在一份权威状态。
9. **玩家不能被迫读报表才能理解世界**：重要机制必须通过行动、场景、对话和少量调查界面可见。

---

## 1. 历史研究边界：什么是理论，什么是证据，什么是游戏假设

### 1.1 理论框架

参考马克思1859年《政治经济学批判》序言关于生产力、生产关系、经济基础与上层建筑的分析。我们把生产、分配和组织关系放在社会模拟的核心位置；文化、权威和习惯既受实际生产条件影响，也反作用于生产。参见：[Marx, 1859](https://www.marxists.org/archive/marx/works/1859/critique-pol-economy/preface.htm)。

马克思1881年关于俄国农村公社的草稿讨论过共同所有与私人因素存在不同可能演化方向，反对把特定发展道路泛化为必然路径。**这不是史前狩猎群体的直接史料**，而是对非单一路径建模的理论启示。参见：[Zasulich 草稿](https://www.marxists.org/archive/marx/works/1881/zasulich/draft-1.htm)。

### 1.2 经验研究转译成规则的原则

| 研究材料 | 可转译的机制 | 不能直接推出 |
|---|---|---|
| Woodburn, 1982，采集狩猎即时/延迟回报经济 | 劳动回报时滞、可携带工具、固定设施、长期资产投入 | 延迟回报必然产生阶级 |
| Jaeggi & Gurven, 2013，食物分享 | 亲属、互惠、请求、合作等分享动机并存 | 分享=自动平均分配 |
| Stibbard-Hawkes & von Rueden, 2025，平等的维持 | 分享、退出、风险共担、限制权力、共同决策需要行为机制 | 人人财富和威望完全一样 |
| Thomson 等, 2025，四种非洲采集狩猎社会 | 社会规范维护方式随群体差异而变化 | 存在普适的原始部落议会制度 |
| Bowles & Fochesato, 2024，长期不平等 | 区分暂时财富差异与跨代持续控制 | 有剩余=私有制=阶级 |
| Kuijt & Finlayson, 2009，Dhra' 储粮建筑 | 储藏、建筑、管理制度可能先于完全驯化农业 | 公共地点粮仓必然是公有制 |
| Lew-Levy 等, 2024，采集狩猎知识传承 | 非亲属、同辈、父母、群体多条学习路径 | 技术只能由父母传给孩子 |
| Ostrom, 1990，公共资源治理 | 监督、协商、执行、退出和争议解决有成本 | 现代共同资源研究就是史前直接记录 |
| Epstein & Axtell, 1996，Sugarscape | 微观行为与环境规则可生成宏观结构 | 一个模拟结果足以证明真实历史 |

> **研究方法**：引用现代民族志时要说明其研究对象是当代群体；考古学证据只是某时某地的实物线索；游戏中的心理权重、模拟年数和阈值需要单独标为“设计假设”。任何模拟结果都不能直接当成对真实史前历史的证明。

---

## 2. 文明阶段与玩法边界

### 2.1 允许的社会组织状态，不是升级关卡

A. **流动性采集狩猎群体**：短期营地、便携生产资料、季节迁移、合作与分享。  
B. **半定居或定居采集群体**：鱼类和植物等资源支持相对稳定的居住、储藏或固定设施。  
C. **早期植物管理与栽培聚落**：播种、照料和野生采集并存，有长期回报劳动。  
D. **复杂无国家共同体**：出现多营地网络、较复杂设施、专业化劳动、组织和权力差异，但可能长期没有国家。

它们可以相互转化、同时存在。**农业≠国家，储粮≠私有制，首领≠国王，家庭财物≠生产资料私有制。**

### 2.2 第一版不开发的内容

不实现完整奴隶制、封建领主制、资本主义工资利润体系、现代学校、中央政府、货币市场和共产主义阶段。对于资源排他、持续依附、职位继承，可以实现**观察和数据结构**，但不以“奴隶社会诞生”作为本阶段通关事件。

### 2.3 玩法定位

- **生存玩家**：亲自采集、打制工具、捕鱼、建造、交易和帮助他人。
- **社会成员**：通过长期合作、学艺、共享、争议、居住与迁徙，改变群体关系。
- **观察者**：用历史记录、居民档案、营地调查和实验模式分析社会。
- **研究模式**：可自由设置参数与快进观察，用于可复现实验；不等于普通生存模式有“决定所有居民命运”的按钮。

### 2.4 玩家看得见的反馈

附近居民真实地移动、采集、使用设施、搬运、交流，居住区随着生产需要而生成和改变；重要历史事件以简短、自然的居民发言、营地公告或编年史记录呈现。严禁把所有玩家体验退化成菜单中的隐藏数字。

---

## 3. 模拟世界的空间层级

**基础层级：个人 Person → 居住单位 Household → 营地 Camp → 跨营地网络 Network → 文化联系 CulturalCommunity**。

不要固定认为“文化共同体=一个村庄”，也不要认为“亲族=当前同住者”。

| 层 | 示例数据 | 作用 |
|---|---|---|
| `Person` | 身份、需求、知识、关系、行动日程 | 个体自主行为 |
| `Household` | 同住成员、实际照料者、保管设施 | 共同生活和再生产 |
| `Camp` | 地点、公共设施、规则、食物、成员网络 | 日常社会生产和协调 |
| `Network` | 访问、交换、婚育关系、迁徙信息 | 技术传播和社会流动 |
| `CulturalCommunity` | 共享的语言变体、知识和习俗分布 | 文化相似性统计，不作为硬身份锁 |

家庭之间可以共享照料；一个营地可以分裂、合并或重新被使用；个人可以保留旧营地关系。

---

## 4. 生态与季节：物质基础应怎样体现为 Minecraft 世界

### 4.1 资源斑块 `ResourcePatch`

不要把整片森林的生态完全等价于原版树叶掉落。设定抽象的资源斑块，绑定自然方块与生态区。字段：`id, dimension, center, region, resourceType, capacity, stock, regrowth, seasonalPattern, accessibility, extractionCost, defensibility, risks, knownBy`。

初期类别：野果/块茎、鱼、猎物、木材、石料、纤维、燃料、水。第一运行切片可缩成**果实、鱼、木材、石料**等少数类型。独立斑块可能枯竭、恢复、随季节变化。

示例公式，仅作模型初值：

```text
newStock = clamp(oldStock + naturalGrowth(oldStock, capacity, season)
                 - actualHarvest + migrationIn - migrationOut, 0, capacity)
```

鱼群、动物和植物允许不同演化函数。不可把游戏方块每次视觉更新当作生态库存的独立生产事件。初版要明确玩家采集对抽象资源的影响策略：**由 Mod 管理的资源节点**或已实现的世界事件监听，一致性优先。

### 4.2 稀缺与可排他控制

记录资源数量、位置、可替代性、是否容易守住入口、是否依赖固定设施。`defensibility` 表示阻止他人使用资源的成本和可行性，不等于资源已归某人所有。

### 4.3 季节与时间

原版通常每游戏日 24,000 tick，约20分钟（20 TPS）。必须设置**统一的模拟日历**，避免玩家附近居民按一种时间、后台群体按另一种时间成年。

- 普通生存推荐先用参数化的 `daysPerYear = 16~32` 作为实验区间（每季4~8游戏日）；这不是历史年龄比例。
- 独立实验模式可按完整模拟日快进。不能在玩家正常游戏中突然只加快儿童成长、却不加快粮食生产或历史时间。
- 时间压缩下的生理、年龄、生产周期必须统一设计；正式数值要经过可玩性测试。
- 服务端调度频率应与模拟日历解耦，**季节变化≠逐tick重算全世界**。

---

## 5. 人口、家庭、心理与照料

### 5.1 人口生命周期

`birth → infancy → childhood → adolescence → adulthood → oldAge → death`，年龄边界必须配置化，不假装统一的历史人口学参数。出生、生育、死亡和健康风险依赖年龄、营养、照料和环境。人口系统不得因人口不足而直接创造一个“无来源成年村民”，除非有明确的迁入事件。

### 5.2 照料是一种劳动

家中幼儿、病者等的照料实际占用工作时间。其他亲属和非亲属可以参与，记录照料事件及对关系的影响。不能把儿童和老人仅视为“无收益消耗者”；也不能为所有老人强制指定教师职业。

### 5.3 个体属性必须与环境分开

`health, stamina, hunger, temperatureStress, age, skill, knowledge, preference, riskTolerance, socialNeeds, habits, recentExperiences`。性格描述的是**倾向**，不是定死的行为；家庭教育影响学习机会，不能把道德、阶级、智力和职业直接写成遗传结论。基因/表型系统可作为以后扩展，但先不做复杂基因决定性格或社会地位的模型。

### 5.4 社会关系网络

任何两位居民可拥有多维、**非对称**关系：`kinship, trust, affection, cooperation, reciprocity, influence, conflict, care, familiarity`。 A 信任 B，不代表 B 一定信任 A。把 `事实` 与 `居民认为发生的事实` 分开；谣言和知识有传播路径、接触距离、来源和可信度。

### 5.5 记忆管理

短期记忆记录最近劳动、帮助、失约和冲突；重大事件进入长期记忆；个人日记摘要可以定期聚合，不应无限储存每次走路的详细轨迹。世界事件账本与居民主观记忆**独立**。

---

## 6. 劳动、生产与社会再生产

### 6.1 基本活动

采集、狩猎、捕鱼、烹饪与加工、处理兽皮、纤维加工、石器制作、工具修补、建筑与维护、运输、照料、学习、社交、休息、侦察、迁营。职业是长期活动结果，**不是工作站绑定的身份**。

### 6.2 生产任务 `ProductionTask`

字段建议：`taskId, type, proposerId, participants, location, resourceSource, assetIds, requiredKnowledge, inputs, duration, tools, productRecipe, plannedYield, actualYield, risk, phase, timeReservations`。

生产前检查可到达资源、技能、工具、权限与时间；生产中分配任务与移动；结束时原子结算：扣减资源、工具损耗、记录参与者、生成产品批次。失败也要扣除合理的时间和材料。狩猎与捕鱼所得不是固定的 `baseRate × 级别`，有环境和随机差异。

### 6.3 时间预算

每个模拟日：`休息 + 生存劳动 + 照料 + 制造维修 + 运输 + 教学 + 社交 + 等待 = 总可用时间`。多居民协作必须锁定各自时间，避免一个 NPC 被两个项目同时雇用。

### 6.4 产品流通 `ResourceBatch`

`harvest → fieldConsume / carry → houseStore / campStore / gift / process → consume / spoil / exchange`。

采用统一**物资批次 ID**、来源、数量、位置和当前保管单位。追踪野外消费。加工过程按配方记录投入、输出和损耗；“1鱼变1熟鱼”是单位映射，不代表营养/质量与守恒单位恒等。

### 6.5 守恒硬约束

```text
期初各类实际库存 + 采获 + 外来输入 + 配方转换入账
= 期末库存 + 消费 + 转换出账 + 损耗 + 外部输出
```

对不同种食物按明确的物质单位、营养参数和加工配方审计。**物资转移**不改变总量。实际世界 ItemStack 与后台库存不能分别作为两套真相；所有生成物品、拾取、存入箱子、丢弃、销毁必须经统一事务处理。

### 6.6 分享与物资分配

不是全体自动平均。允许家庭优先、需求帮助、亲缘、互惠、共同储藏、馈赠、拒绝分享等并存。举例：A 因缺粮请求 B → B 根据已知需求、自身风险、信任、规范与库存决定 → 产生允许/拒绝/提出替代/协商 → 记录物资与社会关系变化。

共享倾向作为非确定性排序：

```text
score = needObserved + kinship + trust + reciprocity + socialNorm
        - ownFutureShortageRisk - deliveryCost
```

不采用通用历史固定系数；所有权重可配置，行为必须受信息与库存约束。

---

## 7. 吃、穿、住、行如何在 Minecraft 里实现

### 7.1 食物

食品具有 `rawMaterial, preparation, nutrition, spoilage, weight, volume, provenance`。初期建立火塘、研磨/切割设施、简易存储。食物加工消耗劳动与燃料，改善部分食材的食用/保存性质，不应所有烹饪都无成本增加营养。火塘还可成为社交和教学地点。

### 7.2 衣物

`hide/fur/plantFiber → scrape/soften/twist/cut → clothing/rope/net`。保暖、防护、耐久、制作与修补时间、材料来源各有用途。饰物与服饰纹样可承担文化与赠礼意义，不直接产生万能“魅力”buff。复杂制革化学与力学细节可抽象。

### 7.3 住所与建筑生命周期

房屋/火塘/工具加工点/储藏设施/渔具/晾晒区等记录：`materials, structuralState, shelterRating, maintenanceCost, capacity, usageHistory, accessRights, buildTasks`。建筑经由需求触发、选址、建材采集、运输、多人施工、维护、废弃和重用，不是“点一个时代按钮自动生成建筑”。

### 7.4 交通与距离

居民有体力、携行载重、运输容器、路径成本、危险与目的地。区别 **整体迁营** 与 **后勤远征**。初版以徒步搬运为主；简单舟船后续扩展。远处资源越丰富也不能免费获得。

### 7.5 聚落从行为中生长

初期功能空间：住所、公共火塘、加工区、储藏、废料区、取水点、捕鱼点、常走路径。空间布局来自居民真实使用历史与地形，不必一次性自动生成完整村庄；建筑管理权与建筑位置分离。聚落具有 `temporary / seasonal / settled / expanding / shrinking / abandoned / reoccupied` 等**观察性状态**。

### 7.6 建设触发实例

```text
营地鱼干较多 -> 储藏需求上升
-> 适当位置提议建设储藏设施
-> 有居民支持并承诺劳动/材料
-> 资源实际采集、运输、施工
-> 设施建成，设置使用与管理规则
-> 长期维护 / 改建 / 争议 / 废弃
```

没有参与者、材料和可施工位置，建设不能凭空完成。

---

## 8. 学习、文化、语言、艺术与知识演化

### 8.1 知识是个人持有而非全局科技值

分为：**即时环境信息 / 操作技能 / 工艺知识 / 社会规范 / 文化和象征知识**。

每个 `KnowledgeEntry` 记录 `awareness, understanding, proficiency, confidence, teacherOrSource, practiceCount, lastUsed, localVariant, factualReliability`。

三条不同事件：**发现、掌握、普及**。技术传承依赖具体接触、指导、试作、材料和实践；掌握者死亡后若没有继承者，技术可能失传。

### 8.2 创新来源

劳动试错、已知技能组合、其他营地借鉴后改造。创新存在投入、失败、材料损耗，不使用“拥有创新天赋，自动每N天解锁技术”的科技事件。

### 8.3 文化传播

亲属、非亲属、同伴、旅行者和群体活动都能成为知识源。远处居民不自动知道新工具。职业技能由实际持续劳动形成，可以有人非常熟练但不愿教学，也可以有人擅长传授。儿童通过观察、实践和游戏学习。

### 8.4 语言交流是有语义的事件

`TellLocation, RequestFood, OfferHelp, TeachTechnique, ProposeNorm, DisputeClaim, Promise, RetellHistory`。对话文本属于表现层，真正改变世界的是经过检查的交流意图与行动。不要求每个村民调用大模型。

### 8.5 两套历史

- `HistoricalLedger`：服务器记录真实的生产、亲属、迁移、争端与制度变化。
- `CulturalMemory`：居民理解、传说和记忆中的历史，可能遗忘、夸张和相互矛盾。

共同仪式/纪念/故事/装饰要消耗时间或物资，并通过接触、社会网络和参与改变实际关系。艺术不必全都提供产量加成。

---

## 9. 财产、分配、权威和无国家治理

### 9.1 产权是权利束，不能只有 `ownerId`

最少包括 `access, use, manage, benefit, exclude, transfer, inherit, enforce`，权利主体可分别是 `Person / Household / Camp / SharedGroup`。

每项权利应有四层：`claim`（谁主张）→ `recognition`（谁承认）→ `enforcement`（怎样执行）→ `persistence`（能否延续）。

例：工匠拥有石斧的优先使用权，不等于拥有整条河。一个居民负责看管公共粮仓，不等于他有私人收益权。

### 9.2 规范的产生

规范状态 `observedPractice → sharedExpectation → recognizedNorm → repeatedEnforcement/revision/decay`。多数人经常做某行为，不等于所有人认为它是义务。规范可通过请求、提醒、调解、声誉、拒绝合作、移居等行为维护，必须有执行成本。

### 9.3 集体决策不是强制投票

`IssueRaised → InformationSpread → PreferencesForm → Coordination → Action → Feedback`。可能采取个人直接行动、临时协调、协商共识或集体会议，不是固定每7天议事一次。对组织劳动、共享、资源争议、迁营可以使用不同决策方式。

### 9.4 威望与权力分离

保存 `technicalReputation, trust, persuasion, coordinationAbility, actualAssetControl, coerciveCapacity`。临时狩猎组织者可以有威望却不能直接继承酋长职位。首领称谓不能自动获得征税权或仓库提款权限。

### 9.5 不平等与依附诊断

并行统计：物质财富、技能资本、关系性财富、关键资源排他程度、家庭资源获取能力、劳动对特定个人的依赖、权利跨代延续、公共监督、实际退出选择。

区分：**暂时差异 → 可累积差异 → 跨代固化 → 可执行的生产资料控制 → 持续支配他人劳动**。没有一步是必然发生的；“对病人提供照料”也不能被自动标记为剥削。

### 9.6 同时存在的制衡机制

分享请求、公共资源监督、管理轮换、协商修订、技术公开、迁徙与其他生计机会。模拟必须既允许资源集中，也允许其被阻止或逆转。**绝对不使用“剩余量超过X则进入奴隶制”**。

---

## 10. 村民 AI 怎样真正工作：决策、执行与记忆闭环

### 10.1 四层 AI，避免全局暴力搜索

1. **需要层**：饥饿、疲劳、健康、照料义务、安全、社交。
2. **候选行动层**：根据居民真实已知的地点、资源、关系和技能生成可行动作。
3. **规划与资源预留层**：给行动分配时间、工具、资源、队友，解决抢占冲突。
4. **执行与反馈层**：附近真实动作；远处抽象结算；结束后更新产品、记忆、技能和事件日志。

候选动作示例：`Gather, Fish, Craft, Transport, Eat, Share, RequestFood, Care, Teach, Visit, Build, Repair, Scout, Migrate, Negotiate, Rest`。

### 10.2 优先级公式仅作为调试工具

```text
utility(action, person) = needReduction + expectedProduct + socialValue + personalGoal
                          - risk - timeCost - resourceCost - normConflict
```

**不能**每回合总选择理论最优动作：居民信息有限，判断会失误，遇到不可达地点、劳动力冲突或天气变化会重新计划。可加入少量探索与惯性来避免无限重复切换动作。

### 10.3 行动状态机

`IDLE → PLAN → RESERVE → TRAVEL → WORK → DELIVER → REPORT → COMPLETE`，失败分支 `INTERRUPTED / CANCELLED / FAILED`。必须提供取消和补偿：取消时未消耗的材料释放，已消耗的材料和劳动依然记账。

### 10.4 一次具体行动的可追溯示例

```text
01 早晨  Person A 发现家庭存粮不足
02 A 记得河岸有鱼，知道渔具位于公共工具棚
03 A 获得渔具临时使用许可，预留2小时
04 A 前往河岸，产生实际捕捞任务
05 资源斑块 -8鱼，产生 batch-fish-043 +8鱼
06 A 在野外吃掉1鱼，背回7鱼，渔具耐久下降
07 邻居 B 请求食物；A 同意给 B 2鱼
08 批次分拆：家庭库存 +5，B 家庭 +2，野外消费1
09 双方互助和信任发生可解释变化
10 历史日志记录，B 下次面临选择时可能帮助 A
```

此时营地里其他居民不能凭空得知这次分享，除非旁观、听到当事人的讲述或者从其他渠道获知。

---

## 11. Minecraft 与社会引擎的分层架构

### 11.1 建议仓库结构（如已有项目则以兼容重构为主）

```text
civilization-mod/
├─ simulation-core/              # 纯 Java，无 net.minecraft 依赖
│  ├─ domain/                    # Person, Household, Camp, Asset, ResourcePatch, Norm...
│  ├─ economy/                   # Ledger, Batch, Production, Exchange, Sharing
│  ├─ ecology/                   # Regeneration, Season, ResourceAccess
│  ├─ society/                   # Relationships, Care, Norms, Conflict, Claims
│  ├─ culture/                   # Knowledge, Learning, Innovation, Memory
│  ├─ decision/                  # Actions, Planning, Scheduling, Utility
│  ├─ settlement/                # SiteSelection, BuildingProjects, Migration
│  ├─ events/                    # Event types, causal links, projections
│  └─ tests/                     # Reproducible, headless simulations
├─ minecraft-adapter/           # NeoForge 接入，如已有同等目录可沿用
│  ├─ entity/                    # NPC 与实体适配、同步
│  ├─ world/                     # 方块、资源区域、聚落和世界映射
│  ├─ storage/                   # 存档、数据迁移、版本化
│  ├─ networking/                # 数据包、权限验证
│  ├─ ui/                        # 日志、居民档案、营地观察
│  └─ events/                    # 游戏 tick 和世界事件
├─ data/                        # JSON 可配置配方/规则/实验场景
├─ research/                    # 文献研究、模型假设与限制
├─ experiments/                 # 固定 seed、参数和运行结果
└─ docs/                        # 本规格、实现状态与交接记录
```

不是强制要求立刻按上述目录大规模拆分。**先检查现有仓库，再尽量小范围逐步分层**。

### 11.2 版本建议

若还没有确定开发目标，可优先评估 **Minecraft Java 1.21.1 + NeoForge + Java 21**，但这是**建议基线，不是要求推翻正在进行的工程**。官方 [NeoForge 1.21.1 Getting Started](https://docs.neoforged.net/docs/1.21.1/gettingstarted/) 指明 Java 21。具体 API 以最终锁定版本的官方文档为准。

### 11.3 数据持久化和客户端同步

| 数据 | 首选存储方向 | 注意点 |
|---|---|---|
| 跨区块世界人口、营地、制度、汇总账本 | `SavedData` / 统一世界存档 | 修改后标记 dirty，带 schemaVersion |
| 附着在实体上的状态 | Entity Data Attachments 或自定义实体序列化 | 必须通过稳定 personId 与社会核心关联 |
| 特殊设施、箱子、火塘 | Block Entity 数据 | 原生库存与模拟库存需要一个权威来源 |
| 工具品质、物品属性 | ItemStack Data Components | 不要每件物品都附加不必要的大对象 |
| 自定义配方和规则 | 数据驱动 JSON、配方、数据映射 | 避免硬编码，注意版本加载规则 |
| UI 数据 | 自定义网络 Payload / 增量同步 | 客户端只能显示，不能自行改写权威社会状态 |

参考：[Block Entities](https://docs.neoforged.net/docs/1.21.1/blockentities/)、[Data Attachments](https://docs.neoforged.net/docs/1.21.1/datastorage/attachments/)、[Payloads](https://docs.neoforged.net/docs/1.21.1/networking/payload/)、[Data Maps](https://docs.neoforged.net/docs/1.21.1/resources/server/datamaps/)。`SavedData` 所在 API 版本应在实现时确认，不能直接照搬不同 NeoForge 版本代码。

### 11.4 近景/远景/背景三级模拟

| 级别 | 条件 | 运行行为 |
|---|---|---|
| L0 近景 | 玩家可见且有实体 | 实体移动、寻路、动画、可见工作、局部交互 |
| L1 中景 | 附近营地但不可见 | 群体任务的批量结算、关键人物和物资状态保持 |
| L2 背景 | 更远且未加载的营地 | 定期按社会日结算人口、生态、经济、规则和事件 |

远景不强行加载区块，不执行大规模实时实体 AI。无论级别变化，`PersonId, BatchId, AssetId, CampId` 稳定；L2 升到 L0 只**物化已有状态**，不能额外生成工资、鱼或工具；L0 降到 L2 也不能丢失尚未结算的施工与运输任务。

### 11.5 服务器权威与多玩家

决定物资、社会事件和居民行为的计算在服务端；客户端发送明确交互意图并经服务端权限/距离/资源验证。多人玩家可以有不同关系与行为影响，不允许双方同时从同一批次扣除超额资源。社会模拟线程原则上不直接异步修改 Minecraft 世界对象，按服务端线程/安全任务队列执行世界变更。

### 11.6 Tick 调度与性能策略

- 逐 tick：仅实体动画、附近执行器必要更新，**不**逐 tick 更新所有居民社会网络。
- 周期性：附近居民规划、任务执行分段。
- 每游戏日：饮食、库存损耗、劳动账本、生态与居民需求。
- 每季：迁营/储粮/资源策略评估。
- 更长期：代际、继承、知识持有结构与制度变化分析。

以上是逻辑频率，不是所有逻辑必须集中在同一个 tick 执行。需要错峰、预算和快照，避免同一帧结算几百个营地。

---

## 12. 建议的基础数据结构和接口

### 12.1 核心数据对象

| 类 | 必要字段 | 不得误用 |
|---|---|---|
| `Person` | personId, needs, health, skills, knowledgeIds, relationships, memories, campId | 不能用一个 personality 决定一生 |
| `Household` | householdId, residents, careNetwork, assets, stores | 家庭不必等于血缘小家庭 |
| `Camp` | campId, bounds, facilities, members, norms, publicStore, status | 营地不等于国家或文化共同体 |
| `ResourcePatch` | patchId, type, location, stock, capacity, regrowth, defensibility | 可控性不等于私有权 |
| `Asset` | assetId, type, durability, ownerClaims, rightsBundle, controller | 持有人不一定有收益/继承权 |
| `ResourceBatch` | batchId, kind, amount, location, rights, originEvent | 不可在实体与后台同时存在 |
| `ProductionTask` | taskId, actors, inputs, site, reservedTime, phase, outputs | 不可重复结算或双重劳动 |
| `KnowledgeEntry` | id, competency, confidence, source, practicedAt, variant | 不可因文明等级全体获得 |
| `Norm` | normId, context, expectedBehavior, knownBy, supporters, enforcement | 不可等同于服务端不可违背的命令 |
| `Claim` | claimant, asset, rightsRequested, basis, recognizedBy, contestedBy | 声明不等于拥有 |
| `SimulationEvent` | id, tick, type, actors, causes, deltas | 不要无限记录无意义寻路事件 |

### 12.2 系统核心接口（语言无关伪代码，非现成 NeoForge API）

```text
SimulationCore.step(simTime, worldFacts, seededRng) -> StateDelta + DomainEvents
ResourceService.reserve(patchId, amount, taskId) -> Reservation
ProductionService.commit(taskId, reservations) -> Batches + LedgerEvents
RightsService.check(actorId, action, assetId, context) -> Allowed/Contested/Denied
SocialDecisionService.propose(issue, actorId) -> ProposalId
KnowledgeService.learn(learner, teacher, techniqueId, practiceTime) -> LearningEvent
SettlementService.materialize(snapshot, loadedRegion) -> VisualTasks
```

### 12.3 一次模拟日的事务顺序（建议）

```text
1. 恢复/确认统一状态与当前模拟时间
2. 更新居民可观察的局部环境，按条件传播信息
3. 评估需求、照料义务和正在执行的任务
4. 生成可行动作，预留资源、时间和设备
5. L0执行可见实体行动；L1/L2执行受约束的抽象任务
6. 原子结算生产、资源扣减、工具损耗和运输
7. 处理请求、分享、公共领取、赠礼和交换
8. 加工、消费、腐败，更新健康和存活状态
9. 传播技术、更新关系、记忆、规范争议与决策
10. 处理离居、迁移、建筑进度、重大生命事件
11. 执行生态日更新，写入摘要/异常/存档快照
12. 做非负库存、时间预算、权利约束和事件校验
```

实际可以拆成更多增量 tick 执行；核心是**先后次序固定、随机种子固定、结算幂等**。

### 12.4 数据驱动配置示意（设计 Schema，不保证可直接在 Minecraft datapack 加载）

```json
{
  "id": "civilization:river_fishing",
  "category": "production",
  "requiredKnowledge": ["civilization:basic_fishing"],
  "requiredAssets": ["civilization:simple_fishing_tool"],
  "resourcePatchTag": "civilization:fishable_water",
  "workHours": 3,
  "yieldModel": "bounded_stochastic",
  "outputs": [{"item": "civilization:raw_fish", "unit": "portion"}],
  "possibleDecisionsAfterProduction": ["consume", "share", "store", "transport"]
}
```

这只是例子；真正的产量由资源、劳时、技能、工具、天气和失败可能性动态计算，不由 `outputs` 字段固定给予。

---

## 13. 六个整体场景：交给 Agent 的端到端验收

### 场景 S1「第一次真正的分享」

村民 A 捕鱼后带回7份，B 缺粮并知晓 A 有库存，请求2份。A 有可行的接受/拒绝/提出条件选项，实际影响库存和双方关系。**断言**：8份总收获若野外吃1份，营地最多只出现7份；不能自动全营地加好感。

### 场景 S2「共同储藏的争议」

几户共同建储藏设施，指定临时保管人；连续缺粮后出现领取请求和不同意见。成员可能提议轮换保管或提高家庭储备比例。**断言**：规则改变依赖知情、支持和执行，而不是随机直接解锁“私有制”。

### 场景 S3「技术发现与失传」

工匠 A 改进一种工具。B 经过实际指导和练习学会，未接触的 C 不会获得知识。如果 A 去世，B 若会独立制作则保留技术，否则可能失传。**断言**：没有全局解锁。

### 场景 S4「定居的代价」

河岸有持续渔业资源，居民投资火塘、晾晒区和住所；连续环境风险导致资源变化。居民可以继续维护、季节迁徙或部分分裂。**断言**：建筑不只是升级图标，离开后物资和设施状态可保留并重用。

### 场景 S5「资源控制并不必然产生阶级」

A/B 两营地拥有相似产品剩余，但一个资源分散，另一个集中在固定捕鱼口。改变监督、资源替代路径与迁移成本。**断言**：能观察资源控制和依赖程度差异；不能只按粮食剩余量触发新社会。

### 场景 S6「儿童与非亲属学习」

一名儿童主要由家庭成员照料，同时从非亲属工匠学习；成长后有实际技能；儿童需要照料的时间影响成年人劳动安排。**断言**：照料与教学有成本，学习不由血统直接继承。

---

## 14. 自动化测试和实验框架

### 14.1 单元测试（必须有）

- 资源与产品总量守恒，包括加工、腐败与实体卸载/加载。
- 同一 batch 不重复发放，重复提交同一个 task 不会再生产一遍。
- 人物同一时间没有两份相互冲突的任务预留。
- 数据存档/重载后 personId、campId、资产权限与任务阶段不丢失。
- NPC 对未知地点不具有完整正确的信息。
- 生产资料的持有人不自动拥有所有八项权利。
- 规范被违反后，处罚与社会反应取决于知情、条件和实际执行能力。
- 生物学亲缘、居住单位、照料网络可独立表示。

### 14.2 社会实验（每组重复多 seed）

1. 丰饶 vs 匮乏资源；
2. 分散 vs 集中/可排他资源；
3. 家庭库存 vs 共同储藏；
4. 分享规范强 vs 弱，监督强 vs 弱；
5. 信息交流频繁 vs 孤立，教学有效 vs 不佳；
6. 迁徙成本高 vs 低；
7. 不同初始家庭结构和照料网络；
8. 建设设施前后运输与劳动成本。

**记录参数和随机种子，禁止只展示一次戏剧性成功运行。**

### 14.3 指标

`FoodAccessRate, HungerDays, ProductPerLaborHour, CareHours, SharingReach, TrustNetworkDensity, KnowledgeRetention, ResourceAccessAutonomy, AssetControlConcentration, DependencePersistence, NormCompliance, MigrationCount, BuildingMaintenanceBurden`。

结果报告必须包含失败的实验、不可解释的异常和模型的假设限制。不能在计算上因为某组指标被人为正向加权，就宣称某种制度已经被“证明更优”。

### 14.4 性能与兼容验收

- 先纯 Java headless 验证 30、100、500 人等规模的复杂度与运行时间，再确定 Minecraft 实体化规模。
- 在目标硬件环境记录 tick 耗时、服务器 TPS、网络同步量、内存占用、区块切换耗时。
- L0/L1/L2 切换进行重复存取测试。
- 后台计算可以批量处理，但不能让社会模拟阻塞主线程、不能因角色看不见就冻结其经济生活。
- UI 同步只传必要差量，玩家近处观察时才展示细节。

---

## 15. 分阶段交付顺序与开发优先级

> **只为开发排序，不代表已经实施。Agent 应先查现有代码、现有任务和 v0.1 的实现情况，汇报差异后按最小改动继续。**

### P0：第一个纵向贯通的可玩闭环（最优先）

1. 建立独立领域模型与物资账本，固定 seed，可存档和测试。
2. 一个小营地，居民有饥饿、体力、已知资源、行动计划。
3. 基础采集/捕鱼/工具消耗/野外消费/搬运/家庭库存。
4. 一次真实分享请求、拒绝或接受，更新记忆/信任。
5. Minecraft 实体能看见行动，调试面板能查到来源和原因。
6. 进行至少一次卸载重载，证明不会双重结算。

**P0 验收**：无人为剧情脚本时，居民至少能完成「需求 → 找资源 → 劳动 → 带回 → 消费或分享 → 反馈」循环。

### P1：让营地真正成为社会

增加跨家庭互助、长期关系、照料和时间预算、公共储藏、规则争议与修改、两营地访问和信息传播、简单季节与存粮风险。

**P1 验收**：不同关系/知识/规则可以导致同一物质情境下的不同社会行动，并能解释为什么。

### P2：让社会有历史

知识技能传播/失传、儿童成长与社会学习、家庭和迁移、设施维护与废弃、群体历史、文化记忆、建筑空间演化。

**P2 验收**：在不脚本指定的情况下形成不同知识传统、营地形态和社会网络。

### P3：原始时代内部的制度分化

产权权利束、长期设施管理、继承主张、排他与退出、劳动依赖、公共监督及其失效或恢复。

**P3 验收**：可以观察无国家社会中不同财产和权力结构长期存在或变化，但不自动宣告进入后续社会阶段。

### P4：游戏完成度与拓展

原始材料多样性、服饰、建造表现、自然动画、文化活动、音效、日常对话、历史阅读体验、性能优化、可配置科研沙盒。此阶段不意味着前面完全不做美术；P0 必须已有基本可视反馈。

### 交给 Agent 的每个 PR/提交应有

`目标 → 改动文件 → 新增状态字段 → 更新的规则 → 测试 → 性能影响 → 仍未实现/已知风险 → 下一步`。

若可推送仓库，在用户此前授权和当前仓库协作策略允许下小步提交，保留可回滚节点；这里**没有获得任何当前仓库地址或检查实际仓库状态的结果**，不得谎称已部署或已提交。

---

## 16. 禁止误实现清单

| 错误设计 | 为什么不行 | 替代方式 |
|---|---|---|
| 建了仓库自动切换私有制 | 建筑不能定义所有权 | 实际权限、主张、承认和执行 |
| 首领头衔自动征税 | 无国家社会并非一律有征税机构 | 临时协调、实际资源控制与社会成本 |
| 全体村民共享科技树 | 抹除了教育、交往和知识流失 | 个人知识、真实教学、传播网络 |
| 每人一组永久固定职业 | 忽略流动劳动与家庭照料 | 基于需要与技能的任务参与 |
| 全球命令“平均分粮” | 抹除家庭分享和不同动机 | 居民请求、规范和实际库存转移 |
| “贪婪基因”决定剥削 | 把历史关系误写成生物本质 | 资源控制、社会约束与可选行为 |
| 所有部落每周强制投票 | 错误统一不同协调方式 | 场景相关的自发行动和协商 |
| 事件按日期固定触发 | 伪历史涌现 | 条件、行动与因果链驱动 |
| 原始村民都是平等纯善 | 不符合多样的人类学材料 | 动机多样与主动维持平等 |
| 远景暂停或重复生产 | 导致时间与物资不一致 | 统一权威状态、分层结算 |
| 为所有社交调用 LLM | 成本高、不可复现、可能篡改世界 | 结构化行为事件，语言为表现层 |
| 以贫富差距数值判断阶级 | 财产差异不等于劳动支配 | 权利、实际控制、长期依赖、继承 |

---

## 17. 一次 30 分钟玩家体验的目标（场景脚本≠模拟历史脚本）

**0–5分钟：** 玩家来到河谷，见到住处、火塘、采集或捕鱼居民；通过观察发现不同家庭在不同活动。  
**5–10分钟：** 玩家帮助寻找石料或捕鱼，劳动消耗时间/工具，产品可以保留、交换或分享。  
**10–15分钟：** 某户家庭请求粮食，玩家可介入但不能无视其他人的库存和意愿。  
**15–20分钟：** 一名工匠传授技能；玩家看见学习需要时间与实际试作。  
**20–25分钟：** 营地因资源、储藏或使用问题产生不同意见，居民自行响应。  
**25–30分钟：** 玩家从居民档案和编年史看到实际劳动、关系变化与营地决策的后果。

这只规定**体验应可被触及的内容**，并不规定第19分钟必须发生某场争吵。若现实参数在半小时内不会自然形成大事，可通过**测试场景的初始条件**提高观察概率，而不在生存世界强制触发剧情。

---

## 18. 研究与决策记录制度

每个新增机制需要一个小型 ADR（Architecture/Design Decision Record）或研究卡：

```text
研究/机制 ID：R-xxx
问题：我们想解释什么？
理论来源：马克思主义文本/现代学术出处
证据强度：考古直接证据/民族志比较/理论假说/纯游戏化假设
可观察行为：玩家或测试可以看到什么？
数据对象：哪些字段会改变？
实验：怎样区分模型预测与随机波动？
已知局限：哪些情况不适用？
状态：讨论中/接受/试验中/已实现/弃用
```

原则：研究可以继续补充，但不应频繁推翻已经验证可运行的底层协议；修改社会假设优先通过可配置规则、实验对照和版本迁移进行。

---

## 19. 参考研究与官方开发文档

### 历史唯物主义与理论

1. Marx (1859), [*Preface to A Contribution to the Critique of Political Economy*](https://www.marxists.org/archive/marx/works/1859/critique-pol-economy/preface.htm).
2. Marx (1881), [Draft letter to Vera Zasulich](https://www.marxists.org/archive/marx/works/1881/zasulich/draft-1.htm).
3. Epstein & Axtell (1996), [*Growing Artificial Societies*, MIT Press](https://mitpress.mit.edu/9780262050531/growing-artificial-societies/).

### 平等、分享、生产关系与不平等

4. Woodburn (1982), [*Egalitarian Societies*](https://inequality.stanford.edu/sites/default/files/media/_media/pdf/Classic_Media/Woodburn_1982_History%20of%20Inequality.pdf).
5. Jaeggi & Gurven (2013), [*Natural Cooperators: Food Sharing in Humans and Other Primates*](https://gurven.anth.ucsb.edu/sites/secure.lsit.ucsb.edu.anth.d7_gurven/files/sitefiles/papers/jaeggigurven2013EA.pdf).
6. Thomson et al. (2025), [*Fiercely Egalitarian*](https://journals.sagepub.com/doi/10.1177/10693971251338210).
7. Stibbard-Hawkes & von Rueden (2025 online), [*Egalitarianism is not equality*](https://www.cambridge.org/core/journals/behavioral-and-brain-sciences/article/egalitarianism-is-not-equality-moving-from-outcome-to-process-in-the-study-of-human-political-organisation/8B8E13583AF33C64A1FB1B05AEB2846B).
8. Bowles & Fochesato (2024), [*The Origins of Enduring Economic Inequality*](https://www.aeaweb.org/articles?id=10.1257/jel.20241718).
9. Ostrom Workshop, [Design Principles](https://www.ostrom-workshop.indiana.edu/courses-teaching/teaching-tools/ostrom-design/index.html).

### 教育、文化和物质生活

10. Lew-Levy et al. (2024), [*Cultural transmission among hunter-gatherers*](https://pmc.ncbi.nlm.nih.gov/articles/PMC11621818/).
11. Kuijt & Finlayson (2009), [*Evidence for food storage and predomestication granaries 11,000 years ago in the Jordan Valley*](https://pmc.ncbi.nlm.nih.gov/articles/PMC2700141/).

### Minecraft/NeoForge 技术（以锁定版本为准）

12. NeoForge 1.21.1, [Getting Started / Java 21](https://docs.neoforged.net/docs/1.21.1/gettingstarted/).
13. NeoForge 1.21.1, [Block Entities](https://docs.neoforged.net/docs/1.21.1/blockentities/).
14. NeoForge 1.21.1, [Data Attachments](https://docs.neoforged.net/docs/1.21.1/datastorage/attachments/).
15. NeoForge 1.21.1, [Network Payload](https://docs.neoforged.net/docs/1.21.1/networking/payload/).
16. NeoForge 1.21.1, [Data Maps](https://docs.neoforged.net/docs/1.21.1/resources/server/datamaps/).
17. NeoForge 1.21.1, [Recipes](https://docs.neoforged.net/docs/1.21.1/resources/server/recipes/).
18. [Minecraft tick/time](https://minecraft.wiki/w/Tick).

> 本文并不是每篇文章的全文替代；如果 Agent 在实现中遇到材料之间矛盾，优先重新核对原文，并更新研究卡。外部参考代码和美术资源必须单独检查开源许可，不默认可直接复制到 Mod。

---

## 20. 最短交付命令与第一步

**建议 Agent 的第一步不是同时实现20个子系统，而是：**

1. 读取本文件与 `primitive_society_economy_design_v0.1.md`，再检查当前仓库、版本、目录、已有模块和测试。
2. 输出一个对照表：`已实现 / 部分实现 / 未实现 / 与规范冲突 / 存在 bug`。
3. 选出最小的 P0 闭环，优先补齐统一物资账本、居民行动和真实食物分享。
4. 跑纯核心测试和 Minecraft 集成测试，记录随机 seed、版本和可复现步骤。
5. 小步提交变更，报告实现证据、未覆盖的点；对社会理论存在不确定性的部分留下参数与研究问题，不以写死事件解决。

**最终评判标准：**一群早期居民在玩家不干预时，能够依靠资源、劳动、知识、互助、居住和实际社会规则独立延续；玩家可以追溯真实生产与制度变化的因果链，并看见不同可能的历史，而不是一条被设计师锁死的升级树。