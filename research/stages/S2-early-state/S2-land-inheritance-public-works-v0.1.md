# RES-S2-D03-D04-D05-D07-D08-001：土地、继承、阶层固化与公共工程（v0.1）

> 日期：2026-11-05 · 状态：**研究→可开发设计草案，非已实现** · 深度：L2 规格草案 / 尚未通过软件验收。
>
> 关联：S0 公共工程 [Issue #32](https://github.com/ThySummer14/coopcraft/issues/32)、S1 土地/种植 [Issue #35](https://github.com/ThySummer14/coopcraft/issues/35)、研究总纲 [Issue #25](https://github.com/ThySummer14/coopcraft/issues/25)。
>
> 软件约束：Fabric 1.21.1、Java 21 目标，Gradle JDK25；保持现有 `SettlementSavedData`、`MinecraftStockpileAdapter`、独立 `civilization.simulation`、真实采集制作、家庭与双亲映射、社会规范和逐块施工。所有工程项须在编码时重新核对最新源码与测试。

## 0. 本轮新增内容与不可越过的边界

此前S2讨论已覆盖国家形成多路径、财政与政治身份概念。本轮**不重复讲解王国/社会形态概论**，只给出5项可编码增量：土地权利包、继承事务、关系推导的阶层、公共工程+义务劳动、财政与政治反馈。技术时代与制度状态分离；职位、所有权、劳动身份不是同一枚举。共同体不因人多或粮多而必然走向国家，国家不必具有奴隶制。

**[证据]** 2025 PNAS 全球考古住宅资料认为持续财富差异与土地约束、聚落层级和代际传递相关；2026《Science Advances》中原地区研究提示关系网络优势与权力控制在国家出现前已能形成差异；2017 Rost 综述发现灌溉并非总由国家中央管理。
**[解释]** 资源可被排他占有、税基可被记录、职位与权利可继承时，原本短暂优势可能持续，但强制程度取决于地方组织与监督。
**[游戏假设]** 以下状态、阈值与权重为可调的测试参数，绝非特定古代文明的实测数值。

## 1. 与工程现状的准确衔接

1. `docs/civilization-design.md` 已记录 **S0 独立模拟的家庭、批次来源、定日循环、议事与分配规范、双营地迁居、家庭亲属和真实容器适配**；不得再次建“虚拟国家粮仓”或第二个库存真值。
2. 当前社会主线原始共同制度，已有协调首领，但其**没有私人生产资料特权**。S2政治职位应以新制度实例附加，不篡改现存首领行为或把自私人格直接等价为腐败。
3. **S1真实农业未在主线实现**。现有S1研究 Issue #35 规定先验证马铃薯可吃可播，再扩展小麦/种子/面包，农田与种植劳动用真实方块。S2土地/税制依赖它，先纯Java模拟、后实机交接。
4. 当前 `SettlementSavedData` 版本应以开发时源文件为准；设计文档指出为v17。不要在研究阶段擅自指定v18、更改存档；正式实现时逐字段迁移、旧档默认空权利争议且不推断未记录的亲属。
5. 公共工程优先复用 `ConstructionPlanner` / `ConstructionSystem` 的逐方块真实扣料、`LaborAllocator`/村民工作目标，保持一个聚落同时施工数量受限，不在每Tick全聚落扫描。

## 2. 权利模型：不要发明一个“地皮所有者”字段

### 2.1 对象与权利

```text
ResourceSite(id, siteType, boundsOrPos, settlementId, source)
  siteType = FARM_PLOT | GRANARY | WORKSHOP | CANAL_SECTION | COMMON_GROUND

RightsBundle(siteId, version, validFrom, claims[])
RightClaim(claimId, holderActorId, rightType, scope, priority, startDay, endDay,
           sourceEventId, transferable, inheritable)
  rightType = ACCESS | USE | MANAGE | ALLOCATE | RECEIVE_YIELD |
              EXCLUDE | TRANSFER | INHERIT

Actor = Person | Household | Assembly | Institution | Polity
```

- 权利包**不复制方块/物资**。真实地块由位置、范围、存档UUID标识，不在世界中制造额外土地 ItemStack。
- 现场行为经服务端 `RightDecision(actor, action, site, day)` 判定；不同权利可归不同主体。具有 `MANAGE` 权限不意味着具备 `TRANSFER` 或 `INHERIT`。
- 地块收益权用于决定实际产出在入包/入批次之后应交给谁；不把“预计产量”写成可领取库存。
- 公共地块可由集体持有不可继承权，具体人担任可撤换管理员；家族可以持有可继承的**使用或收益权**，而土地的最高处分权仍归共同体。
- 玩家箱子/玩家领地与Minecraft权限、领地保护集成必须优先于本系统。默认仅NPC登记设施生效，未明确授权时不得强征玩家财产或任意修改玩家建筑。
- 权利冲突以 **服务端明确规则** 解决，不能随机覆盖：先检查玩家保护与区块条件，再审查已登记的有效权利，最后返回 DENY / ALLOW / DISPUTED。争议保持原权利生效或冻结受争议的非日常转让，具体可配置，不允许“双方都成功转卖”。

### 2.2 土地产出和分配

```text
actualHarvest = only from confirmed block harvest / simulation-produced sourced batch
seedReserve = actually reserved from available inventory (not a new phantom stack)
eligibleYield = actualHarvest - actualHarvestConsumedForSeeding
deliveries = applyYieldRights(eligibleYield, activeRights, validPolicy)
transaction: sourceBatch -> recipientBatch split, linked by sourceEventId
```

- 不要把“留种储备”从产量数学减去之后又从库存扣一次：它是同一批库存的**保留锁/用途限制**，播种时再真实消费。
- 多人/多村民对同一株同时收割，必须只有一次真实收获确认；其余返回无产出，不能凭计划补产量。
- 田间丰收不等于“庄园拥有者可以从任何Minecraft箱子取物”；征纳需要通过合法交接与可用库存来执行。

## 3. 继承是事务，不是NPC死亡时无条件复制

### 3.1 事务契约

```text
InheritanceCase(caseId, deceasedPersonId, triggerEventId, openedAtDay,
                lawId, estateClaimIds, eligibleHeirs, conflicts, status)
status = OPEN | CONTESTED | RESOLVED | ESCHEATED | CANCELLED

SuccessionRule(ruleId, target = PROPERTY_RIGHT | OFFICE, selectionPolicy,
               eligibility, publicConsentRequired, expiry)
```

- 只针对死者**生前个人有效且 inheritable=true** 的权利请求继承；公共仓库集体权利、职位的法人资产不进入遗产池。
- `OFFICE` 职位继承与 `PROPERTY_RIGHT` 分开：可世袭政治职位不代表公共仓库归个人继承。
- 候选继承人只能来自**已经记录的双亲/子女**或显式新增的亲属证据；旧档不得凭同姓/距离推断血缘。未实现婚姻时不造配偶遗产法。
- 本轮默认规则只是游戏化：无有效继承人→原权利回到授予机构、进入待裁决公共产权，**不删除土地与物资**；多继承人→按照已启用习惯规则协商/拆分权利份额，禁止每人各得100%相同地块收益。
- 死亡/迁居/重载等事件均以 `triggerEventId` 去重；权利转移采用单一事务（先冻结争议项，再改变权利持有者，生成事件），禁止部分写入产生双继承。
- 未成年继承人可持有收益权，管理权由合法监护/机构代行，照料与收益按已有家庭规则结算，不凭空制造成年人。

### 3.2 第一版默认继承测试

A类公共设施：继承请求拒绝，职位按现有替任规则另选；B类可继承使用权：有效独生子女继承；C类共享收益权：两名子女按50%份额分配；D类无已知子女：回归授予机构；E类复合家庭/争议：权利暂缓转移并产生待裁决记录。以上均可配置，**不是对古代各地区真实继承法的断言**。

## 4. 不要用阶级标签替代社会关系

```text
For household h, period t:
controlledProductiveAssets = sum(active MANAGE/EXCLUDE/RECEIVE_YIELD rights)
obligatedLaborProvided = sum(confirmed labor hours owed to other actors)
laborReceivedFromOthers = sum(confirmed hours owed to h or institution controlled by h)
appropriatedSurplus = sum(actual batches received from others' production by rights)
outsideOptions = reachable alternatives known to the household, with real costs

ClassRelationSnapshot = derived(
  assetControl, laborObligations, surplusFlows, exitConstraints, dependencyLinks)
```

- 职业标签 FARMER、CRAFTSMAN、GUARD 不映射成“农民阶级、工匠阶级、统治阶级”。
- **分层与不平等是分布诊断**：展示家庭资源余额、收益权集中度、债权分布与劳动负担分布，而不是先给一个“剥削等级”。
- 观察三个时点：初期、继承后、再经过若干周期。若优势没有跨代延续，不要声称发生“阶级固化”。
- 可计算 `assetControlConcentration`、`laborBurdenP90P10`、`intergenerationalRightRetention`；人口过小或收入全零时指标返回N/A而不是除零。
- 对债役、奴役留出法律身份与劳动退出限制接口；本次只做公共义务与拒绝/征发反馈，不把奴役设计为廉价生产增益。

## 5. 公共工程与徭役：统一调度、真实消耗

### 5.1 工程生命周期

```text
PROPOSED -> DELIBERATING -> FUNDED -> LABOR_COMMITTED -> BUILDING
         -> INSPECTED -> OPERATING -> MAINTENANCE_REQUIRED -> (REPAIRED | ABANDONED)
```

每一步有责任人、资源来源、期限、失败原因与因果事件。建设可因缺材料、未加载、路径受阻、无人愿意施工、权限争议而等待，不能删除进度或从空气中创造工程。

### 5.2 劳动义务契约

```text
LaborObligation(id, householdId, issuingInstitutionId, projectId, ruleId,
                dueHours, fulfilledHours, remainingHours, dueDay,
                consentMode, enforcementLevel, sourceEventId, status)
consentMode = VOLUNTARY | COLLECTIVE_DUTY | IMPOSED_CORVEE
status = ASSESSED | ACCEPTED | REFUSED | PARTIAL | FULFILLED | DISPUTED | EXPIRED
```

- 一名居民每日 **production + care + travel + deliberation + publicWork <= availableLaborHours**。所有工作通过同一个现有日劳动预算分配，不给公共工程创造第二条时间轴。
- `dueHours` 是计划；只有确认的实际建造/运输/维护活动写入 `fulfilledHours`，失败寻路不算已工作。
- **同意模式与执行能力分开**：集体制定普遍义务不等同于军队强制；拒绝时可出现协商、替代出资、宽免、欠工或有成本的处罚。强征制度仍需可调执法成本、守卫时间和政治后果。
- 不允许所有村民在同一日无代价同时完成家庭耕种、照护、远程贸易与满额徭役。
- 完工后真实新增仓储容量/生产效率的效果必须由可观察的Minecraft方块和已登记接口提供；工程耗材、维护材料也遵守单一权威。

### 5.3 第一竖切：公共粮仓扩建

原因：现有S0公仓和逐块建设系统可复用，不必等待复杂水流算法或S1小麦产出。需增加一座小型仓储扩建蓝图及**至少一个可交互的真实容器**，不能只增加外观。
最小过程：
1. 公仓利用率连续高于测试门槛（建议80%，连续2个结算日）→ 居民提出扩建；条件数值仅为可调游戏假设。
2. 家庭看到建设材料清单/工时需求，各自作出自愿承诺或接受制度义务；一名NPC自治主持，玩家可贡献但不是必要角色。
3. 项目预算由公共库存实际保留/出库支持，施工逐块从真实来源扣除；单聚落一个施工项目限制继续有效。
4. 完工时实测真实容器容量增加；日后维护可触发材料消耗，失败会影响容量/可用性而不是凭空扣健康。
5. 名册事件页展示“提出原因、谁支持、谁交料、谁出工、何处卡住、真实建成后新增容量、剩余欠工”。

高级水渠版本等 S1 农田有稳定耕作、区块水源与维护机制后再开发。2017 Rost 综述禁止“水渠修好自动诞生专制国家”的伪历史捷径。

## 6. 税款与公共财政：应缴、实缴、欠缴、用途不同账

```text
FiscalAssessment(assessmentId, householdId, institutionId, periodId,
                 policyId, taxBaseEventIds, assessedQuantity, exemption,
                 dueQuantity, collectedQuantity, arrears, status)
TreasuryTransfer(transferId, fromInventory, toInventory, sourcedBatchIds,
                 quantity, receiptEventId, causeAssessmentId)
```

- 首版仅 `VOLUNTARY_CONTRIBUTION`、`HARVEST_SHARE`、`FIXED_TRIBUTE`、`LABOR_DUTY`，不造货币或无限库存。
- 实物税基仅计实际确认收获与该家庭拥有的收益份额；先 S1，再接 Minecraft。模拟核心可先用带来源的产出批次进行固定种子实验。
- `taxDue = max(0, assessed - exemption)`; `taxPaid = confirmedTreasuryTransfer`; `arrears = taxDue - taxPaid - explicitlyRemitted`。欠缴不能生成库存，也不可因重载再次征收。
- 口粮、留种、应急储备的保护应是制度可配置规则。模拟一条“强制征取基本储备”政策，必须记录实际物品流失、家庭生存风险、执行成本和抗拒，不允许只扣虚拟忠诚度。
- 财政支出必须实际结算：施工材料、官员工作时间、守卫配给、灾害救济、维护耗材。职位与守卫不应免费。
- **数值例**：家庭总产20单位、日常及留种需15、应急3、合法可征2。固定贡赋4→正常缴2欠2；若制度允许强征，另2需要真正取走原本受保护的库存并引发风险，不得再次创造4税收单位。
- 财政账户必须有唯一权威批次； `Ledger` 是交易证据，不是可随意读写的第二套存货。

## 7. 政治反馈：把服从与支持分开

```text
Office(id, institutionId, powers, termPolicy, removableBy, successionPolicy)
OfficeHolder(officeId, personId, startDay, endDay, sourceEventId)
HouseholdOpinion(householdId, polityId, compliance, support, grievances[], knowledgeIds)
InstitutionSnapshot(polityId, administrationCoverage, fiscalFulfillment,
                    proceduralContinuity, enforcementCapacity, rightsConcentration,
                    supportDistribution)
```

- 现有S0协调首领不是自动国王；`Office`只在实际授予新权力后改变行为。一个职位可以得到管理权限，却不继承公共财产。
- `compliance` 为实际纳贡/服役记录，`support` 为知情家庭从财政公平、受益、救济、违约等形成的态度。畏惧下服从不等于认同。
- 公共工程收益须按 **实际受益家庭** 记录。某家劳动多却得不到使用权时会提高争议概率；这只是可调游戏行为逻辑，不能冒充普遍社会定律。
- “国家形成”不是一次事件按钮：重复出现的职位、地域管辖、财政与执行能力足以维持多个周期，才标注`EARLY_STATE_LIKE`。**不强制两个以上村庄**，单城邦亦可能形成国家；分类阈值版本化、可解释，不按人数或物资自动升级。
- 权力机构可以失效。财政不足→官员失去报酬/守卫缩减→征纳执行减弱；居民可诉求、迁出、拒服、改选、分权或重新结盟。制度变化必须修改权利/劳动/财政规则，而不只是换领袖头像。

## 8. 五条可复现社会路径（非剧本）

| 路径 | 输入差异 | 允许出现的结果 |
|---|---|---|
| A 协商公共工程 | 公共权利、定期审计、可撤换职位 | 工程按期、规则延续、权力未必私有化 |
| B 仓库控制集中 | 分配权集中、监督弱、部分家庭长期依赖配给 | 资源依赖与政治影响力增长，可能产生反监督 |
| C 世代权利累计 | 家庭可继承收益权，土地有限 | 收益权集中可能跨代保持，也可能因分家下降 |
| D 徭役与财政危机 | 较重劳动义务、收成下降/外部冲击 | 家庭产量下降、欠工、税欠、谈判或强征 |
| E 联合/分权 | 多聚落真实信息与交易渠道 | 联盟、地方自主、征贡与退出，不必形成统一王国 |

实验输出记录来源事件序列、家庭剩余分布、收益权持有人、各方实际劳动、守卫/行政成本和政策变化，不能只比较文明标签。

## 9. 分层施工计划（小PR，避免跨阶段大重构）

**P0a 纯模拟权利与产出分配**：
- 仅新建`ResourceSite/RightClaim`领域模型、权利冲突规则、生产事件到真实批次分配；复用现有S0事件溯源。
- JUnit：无资产复制、收获一次入账、公共管理者不能转卖、权利修改有事件；同种子1000日守恒。

**P0b 劳动预算+公共工程闭环**：
- 复用S0共同工程 Issue #32、ConstructionPlanner/ConstructionSystem。先纯核心承诺/履约，后Fabric仓储扩建+真实容器。
- GameTest：材料只扣一次，暂停/缺料可恢复，工时与照护/采集互斥；玩家不参与也可完成。

**P1a 继承事件与权利快照**：
- 挂接当前真双亲子女映射与死亡/迁出事件；事务幂等、公共资产不继承、多人继承无重复收益。不得用旧档推测婚姻或未记录亲属。

**P1b 财政与制度对照**：
- 在S1收获事件稳定后做实物税；在核心可先测试有来源的抽象收获。完成自愿/定贡/按收成/劳役四种制度分支。
- 要求税款应缴、实缴、欠缴和减免独立可追溯，财政收支守恒。

**P2 政治组织与国家形态识别**：
- 职位、监督、民众支持与服从、改革；对接双聚落网络。
- 暂不做货币、奴隶市场、完整军事AI、帝国行政、正式国家法律体系或全球离线模拟。

## 10. 玩家10–20分钟代表场景

| 时间/场景 | NPC自治 | 玩家操作 | 可观察的成本与变化 |
|---|---|---|---|
| 0–3分钟 公仓拥挤 | 管理者生成扩建建议并播报所需材料 | 查看钟名册/仓库事件 | 仓库利用率与真实格数 |
| 3–6分钟 讨论与募工 | 家庭按储备、可用工时、知情情况响应 | 参与议事或提供材料 | 实际承诺、反对理由、物资锁定 |
| 6–14分钟 建造 | 工人前往工地，采集/照料与建造抢同一工时 | 可协助采集，或旁观 | 逐块消耗、未到位工时、已用物料 |
| 14–20分钟 竣工/失败 | 管理者验收或产生欠工/争议 | 查看账目并选择复议 | 实际可用容量与家庭剩余、制度记录 |

开发测试可在安全测试世界预置材料和接近容量门槛的公仓压缩流程，正式游戏无需强制20分钟时钟或脚本成功。

## 11. 最少测试矩阵（3对照 + 2边界 + 生产保障）

1. **EXP-S2-001**：相同家庭、资源、天气、种子；A 自愿劳役 vs B 定额徭役→对比净公仓容量、家庭生产时数、工程完工率、拒绝与支持。
2. **EXP-S2-002**：A 公共产权+轮替管理 vs B 可继承收益权→3代后收益权分布；不是观察一种结果必然正确，而是观察是否由对应制度变更引发。
3. **EXP-S2-003**：A 公共审计 vs B 无审计→分配争议、管理员权限滥用及罢免行为对比，所有资源必须守恒。
4. **EXP-S2-004 边界**：死者没有有效继承人、两个继承人争同权、职位空缺、玩家占用地块；不自动没收公共资产、无双重收益。
5. **EXP-S2-005 边界**：材料不足/没有路径/建筑处于未加载区块/同日重复回调/连续两次NBT重载；材料与权利不能复制、漏扣或失效。
6. **长时随机种子**：三组固定种子×至少1000核心日，核对批次来源、应缴/实收/减免、不重复出工、继承事务唯一。
7. **GameTest与联机**：真实容器、箱子满、局部导入导出、多人重复操作、`mobGriefing`、玩家财产保护、存档重载；构建与GameTest均绿才标“已实现”。
8. **性能**：不加载未加载区块；日/政治周期错峰结算，范围有限，队列有上限；服务端唯一写入权，客户端只读快照。

## 12. S2→S3 的严格衔接条件

向 S3 的“领主制/地租依附”分支**不是自动升级**。至少有可观测的组合：
- 一方对生产性土地具有稳定排他/收益权，另一方在该土地上实际耕作；
- 耕作者向权利主体经常性交付实际产品、工时或后来有条件的货币支付；
- 土地与生产依附关系可跨多个生产周期延续，且退出具有实际成本；
- 领主权力和国家公共税收分开结算，绝不能把所有税款直接叫“地租”；
- 继承、契约、改革或迁出能够改变这些关系。

S2不含以上全部条件时也可成立；S3制度不需要S2某个精确“时代数值”解锁。

## 13. 风险清单和文献

- [证据边界] 不把中原墓葬关系性财富指标直接映射成财富Gini；不把中后期古巴比伦继承法和阿契美尼德徭役规则当作所有早期国家通法。
- [游戏边界] 游戏里的每周期、承诺比例和税率只是调参，不是历史实测。
- [工程边界] 仓储扩建必须新增实际容器容量，地块权限不能凌驾玩家保护，旧存档迁移不能推断虚构继承人。
- [范围边界] 争议过深不阻塞S1/S0；先让一个完整的真实资源/工时/权利闭环可玩，再扩大至早期国家。

资料：
1. Mayshar, Moav, Pascali (2022), *The Origin of the State: Land Productivity or Appropriability?* https://doi.org/10.1086/718372
2. Bowles & Fochesato (2024), *The Origins of Enduring Economic Inequality*. https://doi.org/10.1257/jel.20241718
3. Kohler et al. (2025), *Economic inequality is fueled by population scale, land-limited production, and settlement hierarchies across the archaeological record*. https://doi.org/10.1073/pnas.2400691122
4. Li, Guo, Chen (2026), *How inequality became durable before states*. https://pmc.ncbi.nlm.nih.gov/articles/PMC13666007/
5. Yoffee (1995), *Political Economy in Early Mesopotamian States*. https://doi.org/10.1146/annurev.an.24.100195.001433
6. Rost (2017), *Water management in Mesopotamia from the sixth till the first millennium B.C.* https://doi.org/10.1002/wat2.1230
7. Schrakamp (2018), *Irrigation in 3rd Millennium Southern Mesopotamia*. https://doi.org/10.17171/3-53-7
8. Yokoyama (1995), *The Administration of Corvée Work and Corvée Workers in the First Dynasty of Babylon*. https://doi.org/10.5356/jorient.38.2_136
9. Charpin (2010), *The Transfer of Property Deeds and the Constitution of Family Archives*. https://doi.org/10.7208/chicago/9780226101590.003.0005
