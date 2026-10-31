# 文明演化 Mod：原始社会生产、分配与社会再生产系统

**设计规格 v0.1｜2026-10-28**
**状态：P0 开发中；独立 Java 核心与限定 Minecraft 生存适配已实现，数值参数仍需持续校准**

## 0. 项目定位

本系统不使用固定的「文明升级按钮」。在史前社会阶段，我们希望通过自然资源、劳动、产品转移、共同生活、权利规则、记忆与协商，让多样的社会组织自然产生。技术时期与社会制度是两个不同坐标。原始社会不是所有人均分和没有个人物品的同义词。

验证目标是机制内部一致性、可追溯性、多样结果和对经验材料的合理对应；**模拟结果不是对历史理论的经验性证实**。

## 1. 核心研究依据及可转译规则

| 证据 | 对设计的启示 | 不能推出的结论 |
|---|---|---|
| Woodburn (1982) 即时/延迟回报经济 | 每种劳动记录投入到回报的时间、资产维护、可转移性和社会依赖 | 延迟回报必然产生阶级 |
| Jaeggi & Gurven (2013) 食物分享元分析 | 分享动机允许亲属、互惠、他人请求、声誉共存 | 所有分享都出于单一动机 |
| Gettler 等 (2023) BaYaka 食物网络 | 食物种类、家庭关系、合作网络影响分配 | 分享必然平均 |
| Hadza 外出消费研究 (2016) | 狩猎/采集期间的消费应先计入物资账本 | 空手返回等于没有收获 |
| Stibbard-Hawkes & von Rueden (2025)；Thomson 等 (2025) | 衡量对资源、地位和自主权的接近，而不只是财富值；维护平等需有行动 | 平等意味着所有能力/财产相同 |
| Bowles & Fochesato (2024) | 比起暂时收入差距，更要检测长期财富控制、继承和持续依赖 | 积累一批粮食就标志阶级诞生 |
| Lew-Levy 等 (2026) 社会规范学习 | 分享习惯由观察、模仿、指导与同伴互动传递 | 每个村民出生自带不变的制度意识 |
| Ostrom (1990) 公共资源治理 | 使用权、监督、纠纷解决和规则修订可由群体组织形成 | 现代公共资源理论就是史前社会的直接历史描述 |
| 马克思 (1859) 生产力/生产关系 | 制度影响生产，生产条件又影响制度的延续与变动 | 各社会按同一顺序自动升级 |

## 2. 领域边界和优先级

### 第一轮可运行范围 P0

- 一张固定的河谷生态地图，2～3个营地、24～36名居民。
- 野果、块茎、鱼、猎物、木材、石料、燃料，初版仅要求三类食物与两类生产资料可用。
- 每人有体力、饥饿、技能、家庭、亲友与信任关系。
- 劳动包含采集、捕鱼、简易狩猎、工具制作、照料、搬运。
- 物资可以在野外消费、带回、分享、储藏、腐败和消耗。
- 支持个人、家庭、共同体三种访问/保管权限，但不把其当作互斥的社会形态。
- 一个简单的共享规范、一次冲突/协商/修订闭环。
- 事件日志可追溯每笔资源和每次规则变更。

### 后续 P1/P2

- 工具质量、复杂生产、储粮设施、季节、资源枯竭、迁营。
- 技艺传承、儿童学习、外营互动、季节性集会。
- 植物管理、种植、延迟回报投资、私人使用主张、代际继承。
- 持续依赖、权利集中和制度变迁的测量，不预设必须进入奴隶制。

### 当前实现切片

`src/main/java/com/thysummer/coopcraft/civilization/simulation/` 已实现首个纯 Java 切片：单个 24 人河谷营地、6 个家庭、15 名成年劳动者与 9 名儿童；资源斑块、技能、饥饿、信任、互惠和规范认知参与每日决策。食物采集、捕鱼、简易狩猎、野外消费、带回、家庭/公共储藏、跨家庭分享、公共分配、消费、腐败与再生均通过真实库存和事件账本结算。木材、石料已可通过同一生产接口采集，自动工具制作尚未实现；照料劳动已接入后述可选人口实验。

批次拆分、转移和消费具有唯一权威余额及来源链；公共库存不能被普通个人转账直接取出。`RiverValleyScenario` 支持固定种子重放，测试已覆盖 3 个种子各 1000 日、逐日物资/营养/斑块守恒、全部消费来源追溯，以及有分享和无分享的对照。当前参数仅用于模型实验，不是历史估计。

社会关系与习惯制度切片已经实现：只有当事人直接获得纠纷信息，其他居民通过每日有限的关系传播获知，信息置信度随传播下降。持续短缺和拒绝积累为不满后可产生公仓分配提案；提案经知情成年人参与、个人立场、家庭力量和自愿监督共同决定，不能按固定日期或单一支持率生效。已采纳的“缺粮者优先”规则会改变公仓请求顺序并限制每人每日领取量，协商和监督消耗下一日劳动时间。规则具有执行稳定度，可被修订或休眠，所有社会事件均可追溯到物质短缺或拒绝。

双营地社会网络与家庭迁徙切片已经实现：`RiverValleyNetworkScenario` 包含 24 人河谷营地和 12 人山脊营地。跨营地信息只沿双方已知路线与实际联系人传播，旅行消耗社会时间；目的地食物保障以有来源和时效的个人报告表示，不能读取全知地图数据。各家庭依据自身处境、报告、儿童负担、路线成本与外部关系独立决定是否迁出。迁徙保留家庭 ID、批次和来源链，改变成员归属并产生决策/完成事件；迁出者不能远程监督原营地。固定种子可产生部分迁出或不迁出的不同结果，双营地 1000 日守恒测试通过。

人口再生产与照料切片已经实现：`RiverValleyDemographyScenario` 使用可配置年龄日支持婴幼儿、儿童、青少年、成年和老年。劳动、决策与照料资格分别计算，青少年劳动时间有限。家庭成员及有信任关系的其他家庭可以提供照料，照料小时在当天生产前扣除并影响婴幼儿健康。年龄推进、健康、死亡、出生和照料均记录事件；出生受食物、人口容量与照料条件约束，不附带物资、技能或规范。死者退出生产、消费、迁徙、沟通和监督，个人批次不自动复制或继承。人口场景固定种子重放及 1000 日守恒测试通过。

个人知识与技能传播切片已经实现：`RiverValleyKnowledgeScenario` 将技术知识按居民和劳动类型记录，新生儿不自动继承技术。同营地、同家庭或具有信任关系的居民可以安排指导，婴幼儿不能学习，只有具备决策能力的成年和老年知识持有者可以执教。每人每天至多参加一次指导，师生各消耗一小时当天劳动时间；未知技术经固定种子判定习得，已知技能按师生差距和关系提升。启用知识系统后，未掌握的技术不能用于采集，死亡者退出知识网络。指导事件记录技术、习得结果和技能变化；知识场景固定种子重放及 1000 日守恒测试通过。

跨营地物资交换切片已经实现：`RiverValleyTradeScenario` 支持家庭之间的一对一双向易货。发起者只能依据已知路线、互信联系人和有来源及时效的营地报告提出请求，不能读取远方库存；回应者根据本家庭真实需求与富余批次接受或拒绝。每人及每家庭每天至多参与一次交换，成交后发起者承担路线旅行小时、回应者承担协调时间，并影响下一日生产。双方批次通过受校验的两条交换腿移动，保留原始来源并因果连接到交换请求；拒绝不移动库存。交换场景固定种子重放及 1000 日守恒测试通过。

Minecraft 唯一权威库存交接切片已经实现：`MinecraftStockpileAdapter` 仅在服务端显式调用时转换聚落公仓物品，不进行每 tick 双向同步。容器转入模拟时先扣除真实物品，再生成有 `EXTERNAL_IMPORT` 来源的批次；批次转回容器时按真实容量插入，再以 `EXTERNAL_EXPORT` 扣除抽象余额，未装下的部分留在原批次。异常回滚、满容器和持有者校验均有测试。当前一件物品对应一个抽象单位，仅支持甜浆果、马铃薯、鳕鱼、生牛肉、橡木原木和圆石；其他物品及带自定义组件的堆叠不转换。真实箱子 GameTest 已验证往返不复制、不丢失物资。

聚落级适配存档与事件观察切片已经实现：`SettlementSavedData` 版本 9 保存营地、稳定村民 UUID→Person 映射、抽象批次和完整交接事件账本。每名村民使用独立适配库存家庭，不代表真实亲属关系；当前只记录映射时的年龄带、饥饿和风险偏好，不映射成长、技能或社交状态。名册侧栏显示最近三条模拟事件。GameTest 已连续执行两次完整 NBT 保存/加载，在重载后继续部分导出，最终真实物品数量不变、抽象余额清零、来源链与守恒成立。

限定每日生存结算切片已经实现：`SettlementSimulationService` 可在至少一名居民已映射的聚落显式启用生存模式，启用时从真实饱食度重新同步核心饥饿。只对仍登记且已加载的存活村民推进每日饥饿，旧饱食 tick 在启用时停止，原村民进食 AI 改为只消费抽象公仓批次。食物申请、分配和消费均写入账本并可追溯到外部导入；真实背包和真实公仓不会被重复消费。生存开关、最后结算日、饥饿和食物余额均持久化，离线天数不追赶补算。GameTest 已验证次日饥饿同步、原接口进食、重载及守恒。

这一切片不是完整 P0：自动工具制作、完整每日社会模拟、真实生产/成长/亲属/技能/规范映射与完整可玩适配仍未实现；没有启用离线自动结算。当前交换只是一对一易货，不包含货币、价格或远程公仓直接出库。婚姻、财产继承、住房、聚落空间演化与生产关系长期分化属于后续专题实现。

## 3. 数据模型（引擎核心，不依赖 Minecraft）

### ResourcePatch（生态资源斑块）
- `id`, `position`, `biome`, `resourceType`
- `stock`, `carryingCapacity`, `naturalGrowthRate`, `seasonMultiplier`, `lastHarvestedTick`
- `accessConstraints`：可达性、危险、距离、季节
- 抽象库存表示资源潜力，不要求一一对应 Minecraft 实体。

### Person（独立居民）
- `id`, `campId`, `householdId`, `ageBand`, `health`, `energy`, `hunger`
- `skills`, `knownTechniques`, `needs`, `workPreferences`, `riskPreference`
- `relations`：亲属、照料、信任、合作、冲突
- `observedNorms`：其实际知道或相信的规则；不保证全体一致
- `memories`：近来的交易、帮助、争执、劳动成功/失败

### Household / Camp
- `Household`：成员、照料需求、家庭仓储、设施权限、短缺历史
- `Camp`：居民关系、公共仓储、物资规则、营地设施、协商会议、对外关系

### Asset（生产资料或存储设施）
- `assetId`, `type`, `location`, `durability`, `capacity`
- `useRight`, `controlRight`, `benefitRight`, `transferRight`, `inheritRight`
- **权利主体可不同**，权利持有人可为 Person / Household / Camp；如多人拥有则按份额或规则表示。

### ResourceBatch（明确来源的产品批次）
- `batchId`, `resourceType`, `quantity`, `quality`, `nutritionPerUnit`
- `location`, `holderId`, `rightsPolicyId`, `harvestTime`, `expiryTime`, `sourcePatchId`, `originEventId`
- 同类食物可以聚合显示，但后台保留批次和来源。

### Norm（实际运用中的习惯规则）
- `normId`, `scope`, `conditions`, `claimants`, `priority`, `prescribedAction`
- `supporters`, `opponents`, `knownBy`, `enforcementType`, `revisionHistory`
- 规则不是全能命令，执行依赖监督、认可、执行代价和实际资源。

### Event（唯一事实来源）
- `eventId`, `tick`, `type`, `actors`, `objectIds`, `inputs`, `outputs`, `context`, `causalLinks`
- 财产转移、生产、消费、丢失、规则修订、储备被拒等均留有事件；统计从事件重建。

## 4. 经济与生态约束

### 4.1 资源再生（原型公式）

对资源斑块 p：

`R[t+1] = clamp(R[t] + g * R[t] * (1 - R[t]/K) * season - harvested, 0, K)`

- `R` 是可采资源量，`g` 为恢复率，`K` 为承载量；这是游戏模型，不是对全部现实物种的统一生长定律。
- 例如猎物种群可以另设更适用的出生、死亡和迁移过程。

### 4.2 生产获得

`grossYield = min(accessibleResource, effortHours * baseRate * skill * tool * environment * stochasticFactor)`

- 收获与当地资源斑块同步扣减。
- 劳动消耗体力/时间，工具损耗；产物需要搬运并受携带上限制约。
- 不能绕过资源库存凭空生成村民产品。

### 4.3 物资守恒（按产品类型）

`期初库存 + 新生产 + 外部流入 = 期末库存 + 消费 + 腐败损失 + 加工损失 + 外部流出`

这里的「新生产」从资源斑块中提取，并相应减少其库存。烹饪或加工跨物品类别时，输入与输出通过配方记录，不能只比较不同类别的道具数量；营养和质量也要分别记账。

### 4.4 时间预算

`可用时间 = 生存劳动 + 照料劳动 + 工具维修 + 学习 + 社交 + 休息 + 移动`

所有人都受到时间约束，照料和知识传递不允许当作“免费BUFF”。

## 5. 社会分配：从一次收获到一个社会行为

### 5.1 食物去向状态机

`HARVESTED -> FIELD_EATEN / TRANSPORTED -> HOUSEHOLD_STORED / CAMP_STORED / GIVEN -> COOKED / EATEN / SPOILED / EXCHANGED`

- 每次流转产生日志；不存在“收获自动均摊到居民背包”的特殊操作。
- 权利检查先于流转：实际持有人不一定拥有完整收益或转让权。
- 可以出现未经许可的取用，但应作为事件、冲突和后果处理，不悄悄抹掉。

### 5.2 分享请求流程

1. A 识别自己的食物缺口，获知 B 或公共储备可能有食物；信息并非全知。
2. A 决定请求对象（关系、距离、对方储量、习俗、既往经历）。
3. B 根据自身风险、对 A 的关系、习俗压力、声誉与未来互惠期待作决定。
4. 若接受，则产生物资转移事件；若拒绝，则更新关系或触发协商，不凭空生出食物。
5. 短缺和不公平感累积到一定程度时，可产生新的规则提案。

**行动倾向示例，不是历史估计系数：**

`ShareScore = w1*needObserved + w2*kinship + w3*trust + w4*reciprocity + w5*normPressure - w6*ownFutureRisk - w7*transportCost`

该分值仅用于排序；最终行动仍由真实库存、权利、知情程度和概率决策约束。

### 5.3 初始规则模板（测试用，不锁死）

- `neighbor-requests`：基于请求的分享，主要发生在可见关系网。
- `family-priority`：家庭首先保留所需，其次向关系网络转移。
- `camp-storage`：成员自愿将一部分食物放入共同储存处，再按规则领取。

同一营地可同时发生三种行为：这些不是排他的「制度卡牌」。

### 5.4 一日账本示例

假设28人，食物统一以抽象的营养单位表示（不是现实中的实际热量）。

- 野外产出：72单位。
- 野外即时消耗：6单位。
- 带回营地：66单位。
- 营地当日消耗：50单位。
- 家庭储备：8单位。
- 公共储备：8单位。
- 核算：72 = 6 + 50 + 8 + 8。
- 若储存部分随后损耗3单位，则期末剩余13单位；这3单位单独列为腐败损失。

这些数字仅检验账本流程，不代表某种考古学估计。

## 6. 习惯、规范与规则变迁

首版规则变化只需一个完整闭环：

`短缺或纠纷 -> 发现分配冲突 -> 发起规则提议 -> 关系网络影响/协商 -> 采纳或拒绝 -> 实际执行 -> 记录违反与修订`

`normSupport` 不能单独决定规则生效：还需参与的成员、实际监督能力、拒不配合者的退出可能，以及执行者本人是否愿意承担成本。

建议每人具有不完全相同的规范认知，并通过观察、模仿和指导传播；代际更替可以改变规范稳定性。

## 7. 社会结果测量

以下指标以滑动窗口统计，不允许用一个单独的「文明等级」替代：

- `FoodAccessRate`：满足基本营养需要的人日比例。
- `HungerDays`：每个人的累计缺粮天数。
- `EffectiveLaborYield`：单位劳动小时的可食用营养产出。
- `SharingReach`：获得其他家庭食物支持的居民比例。
- `ResourceAccessAutonomy`：无需特定个人批准即可获取生存资料的人口比例。
- `AssetControlConcentration`：关键生产资料实际控制的集中程度。
- `DependencePersistence`：同一关系持续若干期的资源依赖情况。
- `NormCompliance`：规则已知居民中遵守规则的比例（注意未知规则情况）。
- `IntergenerationalAdvantage`：跨代资源控制与技能机会的关联。

**重要：**单一长期集中指标升高不等于自动「进入奴隶社会」；需要社会关系本身发生结构性变化，才能做理论判断。

## 8. 每日模拟流水线（伪代码）

```text
simulateDay(world, seededRandom):
  ecology.updateSeasonAndVisibility()
  each camp broadcasts only locally-known conditions
  each person perceives local conditions, hunger and relationships
  people form households and temporary work teams
  scheduler commits work/time reservations, preventing double booking
  for each task:
      check resource, asset rights and actual availability
      execute production with costs and stochastic outcomes
      post ledger events for extraction, tool wear, field consumption
  transport physical/virtual batches with one canonical owner
  open requests, kin support, exchanges and common-store allocations
  cook/consume, update health and hunger
  apply spoilage, death/birth/illness as enabled in current scope
  evaluate conflicts, norm knowledge, reputation and proposals
  apply ecological regeneration in defined order
  append causal event records and daily statistics
  assert conservation and nonnegative balances
```

## 9. Minecraft 接入规范

- **社会模拟核心**：独立 Java module，数据驱动，固定随机种子可重放。
- **适配层**：把玩家、村民实体、世界位置、物品和设备映射为模拟对象。
- **实体与后台人口**：附近执行实体寻路；远处仅执行抽象社会和经济计算，避免强制加载区块。
- **存档**：若选择 NeoForge，世界/全局状态可参考 SavedData，实体/区块状态可参考 Data Attachments；要对齐最终实际采用的 Minecraft/NeoForge 版本。
- **库存同步**：一批食物在真实世界物品形式与后台抽象形式之间切换时，只允许有一个权威库存记录，防止重复结算。
- **当前交接实现**：`MinecraftStockpileAdapter` 只在服务端显式调用时交接公仓物品。容器转出真实物品后才允许模拟导入，容器实际接收物品后才允许模拟导出；异常回滚，容量不足的部分不结算。禁止在此基础上增加每 tick 双向复制或未记账的库存镜像。
- **当前存档实现**：`SettlementSavedData` 版本 9 保存适配居民、批次及交接账本；版本 8 缺少生存开关时默认禁用，版本 7 及更早数据缺少适配状态时按空值读取。独立适配库存家庭不是亲属模型，真实成长、技能、亲属和规范状态不得在未完成映射前启用。
- **当前生存结算**：`SettlementSimulationService` 仅对显式启用且已加载的映射居民推进饥饿，原进食 AI 只消费抽象公仓；旧饱食 tick 不得同时运行，离线天数不得追赶补算。生产、成长、亲属、技能、规范和迁徙仍未接入。
- **可观察性**：在村民页显示最近行动的原因，在营地页显示产出与短缺，在历史页显示冲突和规则修订的因果链。当前名册侧栏只显示最近三条模拟事件。

## 10. 6个必跑对照实验

1. **无共享与自由共享**：比较随机收获波动下的缺粮天数、生产积极性与分配网络，不能假定某制度必胜。
2. **资源富饶与资源贫瘠**：检验劳动结构与迁徙行为变化。
3. **即时消费与公共储藏**：检验腐败、季节短缺、储粮维护成本的作用。
4. **可自由迁营与迁移高成本**：检验长期资源依赖和争议化解方式。
5. **有监督与无监督的共用粮仓**：检验集中管理、滥用与集体纠偏。
6. **技能可传播与技能难传播**：检验劳动效率及代际技术失传。

每个实验至少运行多个固定随机种子，记录完整参数和版本。结果只说明该参数化模型的行为，不直接证明真实历史机制。

## 11. 验收条件

- 1000个模拟日多种随机种子下，不出现负库存、重复领粮和凭空产生食品。
- 每一份消费能追到其收获/生产来源及中间转移。
- 一名居民无法在同一时间同时参加多项劳动。
- 能自然出现至少一个分享请求、一次拒绝、一次补救或协商事件；不能把具体事件发生日写死。
- 去掉全部人工剧情脚本，系统仍有稳定的生存生产循环。
- 对照实验具有方向上可解释的机制反应，而非任何参数组合都产生同一个结局。
- 性能基准须针对目标 Minecraft 版本/硬件测量，暂不凭空填写 TPS 指标。

## 12. 首批工作顺序（无时间承诺）

1. 固定独立 Java 的领域模型和事件账本；编写守恒单元测试。
2. 实现单营地的需求、劳动与资源采集。
3. 实现个人/家庭/公共库存与分享行为。
4. 实现可学习的分配规范及一次规则冲突闭环。
5. 扩展第二营地、迁营、信息有限性。
6. 再接入 Minecraft 村民和可见环境。期间每一步都保存可复现实验场景。

## 13. 文献与链接

1. Marx (1859), *A Contribution to the Critique of Political Economy*, preface: https://www.marxists.org/archive/marx/works/1859/critique-pol-economy/preface.htm
2. Woodburn (1982), *Egalitarian Societies*: https://inequality.stanford.edu/sites/default/files/media/_media/pdf/Classic_Media/Woodburn_1982_History%20of%20Inequality.pdf
3. Jaeggi & Gurven (2013), meta-analysis: https://pubmed.ncbi.nlm.nih.gov/23945693/
4. Gettler et al. (2023), BaYaka food sharing: https://onlinelibrary.wiley.com/doi/10.1002/ajpa.24688
5. Hadza field consumption (2016): https://www.sciencedirect.com/science/article/pii/S1090513816000118
6. Thomson et al. (2025), egalitarian mechanisms: https://journals.sagepub.com/doi/full/10.1177/10693971251338210
7. Stibbard-Hawkes & von Rueden (2025), egalitarianism: https://pubmed.ncbi.nlm.nih.gov/41249160/
8. Bowles & Fochesato (2024), inequality: https://www.aeaweb.org/articles?id=10.1257/jel.20241718
9. Lew-Levy et al. (2026), learning sharing norms: https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0340388
10. Ostrom Workshop, design principles: https://www.ostrom.indiana.edu/courses-teaching/teaching-tools/ostrom-design/index.html
11. NeoForge SavedData: https://docs.neoforged.net/docs/datastorage/saveddata/
12. NeoForge Data Attachments: https://docs.neoforged.net/docs/1.21.3/datastorage/attachments/

---

**下一步实现：**在限定生存结算之外逐步映射真实生产、成长、亲属、技能和规范状态。每个新增子系统必须先完成持久化和唯一权威交接，再启用每日自动结算；不得把当前生存模式直接扩展成未经约束的全功能后台模拟。
