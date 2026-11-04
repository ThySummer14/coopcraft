# S4 城镇与商业资本：经济模拟可实现规格 v0.2

> 状态：研究定稿候选 / 待编码；日期：2026-11-05；范围：CoopCraft Fabric 1.21.1、独立 Java 社会模拟核心。
>
> 本文是**开发协议**而不是已实现功能列表。所有阈值均为可调的初始游戏参数，不是历史人口或经济统计。S4 不是强制科技时代，也不代表必然转入资本主义。

## 0. 本轮冻结的设计结论（不可在实现时默默改写）

1. 商品经济、市场、货币、信用、行会和商业资本是可并存的制度组件，不构成唯一的历史顺序。
2. 城镇是人口集聚、专业化、市场依赖、行政/军事/交通功能的多维结果；人口阈值不能直接使村庄升级。
3. 商人资本主要控制商品流通和资金；生产资料控制、劳动力雇佣和剩余占有必须单独建模。
4. 社会制度不是 NPC 职业标签。生产资料产权、使用权、劳动管理权、收益权必须允许分离。
5. 需求要分为生理/生产需要、交易意愿和有效购买需求；穷人没有钱不等于不需要食物。
6. 成交须同时有真实货权变动和可审计的支付/债权变动；没有库存、现金或有效合同不得成交。
7. 交易、生产和运输不可重复结算同一物品；模拟批次与 Minecraft 物品采用唯一权威交接。
8. 价格、税收、货币供给和负债只依据有明确来源的事件变化，不靠按时代赠送资源。
9. 市场会繁荣也会崩溃；行会可以提高训练协调能力，也可以排斥竞争者。
10. 不设置“商人多就进入资本主义”的判定；S5 使用雇佣劳动及生产控制权的再生产关系诊断。

## 1. 已有代码基线与扩展边界（2026-11-05 main）

- src/main/java/com/thysummer/coopcraft/civilization/simulation/TradeSystem.java：现有跨营地一对一易货，信任、信息时效、联系路线、劳动时间约束已存在。新增市场不得覆盖早期易货。
- .../simulation/TradeConfig.java：目前只有 enabled、minimumMutualTrust、foodReserveDays、minimumReportConfidence。新增经济配置单独建 EconomicConfig，不能破坏旧构造函数。
- .../simulation/SimulationWorld.java：已有 ResourceBatch、transfer、exchangeOneForOne、事件记录、assertConservation。货物流转沿用/扩展这里的单一权威操作，禁止另建库存账本独立增减数量。
- .../simulation/ResourceBatch.java：批次具有来源事件、保管者、质量、保质期、数量及父批次链接。
- .../simulation/StorageOwner.java：已有 PERSON/HOUSEHOLD/CAMP；企业、商队等新增主体需先解决保存/恢复与权利语义，不能仅替换 displayName。
- .../simulation/EventLedger.java、SimulationEvent.java、EventType.java：连续事件 ID、合法因果依赖；新增交易及财务事件沿用唯一事件序列。
- src/main/java/com/thysummer/coopcraft/civilization/MinecraftStockpileAdapter.java：当前只支持八种原版资源与模拟资源之间的显式交接；新商品先扩展映射和 GameTest，再允许真实世界参与。
- .../SettlementSimulationState.java、SettlementSavedData.java：存档/重载以现有结构为基础新增版本化字段，旧存档默认市场功能关闭。
- .../simulation/PrimitiveSocietySimulation.java：独立核心已经有每日循环和可选 TradeSystem；S4 先在独立实验场景运行，后接入服务器主线程上的聚落服务，不能直接让所有村民每 tick 扫市场。

**明确不在本版实现：** 真实股票/证券、复杂银行资产负债表、跨服支付、世界范围搜索最优贸易路径、未加载区块里的实体级 AI、统一“文明值”。

## 2. 必须新增的数据模型与权利区分

| 模型 | 最小字段 | 约束 |
| --- | --- | --- |
| EconomicActor | actorId、kind(PERSON/HOUSEHOLD/COMMUNITY/WORKSHOP/MERCHANT/TREASURY)、settlementId | ID 稳定；不能依赖实体此刻是否加载 |
| PropertyRight | assetId、titleOwnerId、custodianId、useControllerId、revenueBeneficiaryId、validFromEventId | 持有、占有、使用、收益可以不同；修改有事件 |
| EconomicAccount | actorId、currencyId、balanceMinor | 整数最小货币单位，余额非负，禁止 double |
| MarketOrder | orderId、marketId、actorId、goodType、side、limitPriceMinor、quantity、remaining、createdDay、expiresDay | 买单有冻结资金/额度；卖单有冻结批次数量 |
| TradeContract | contractId、buyerId、sellerId、goodType、unitPriceMinor、quantity、settleMode、status、eventId | 原子提交、幂等键；指向真实批次 |
| MarketState | marketId、placeId、inventoryIndex、orders、priceHistory、fees、rules | 不保留第三份可任意写的库存副本 |
| Shipment | shipmentId、routeId、cargoBatchIds、ownerId、carrierId、departureDay、etaDay、status、capacity | 在途保管权明确；不可双处出现 |
| Workshop | workshopId、assetControllerId、recipeId、laborContracts、inputReservations、outputOwnerId | 材料与劳动时间真实消耗 |
| DebtContract | debtId、creditorId、debtorId、principalMinor、remainingMinor、dueDay、collateralIds、state | 债权债务对称；违约不等于删债 |
| Guild | guildId、tradeType、members、entryRule、trainingRule、priceRule、treasuryAccountId | 执行规则要有合法性/执行能力 |
| MunicipalPolicy | municipalityId、taxRateBps、accessPolicy、lawEnforcementCapacity | 治理权限必须有来源 |

**边界策略：** M0-M2 先在 EconomicActor 的财务账本中引入账户，保留现有 StorageOwner 作为实物批次权威。企业/商队实际保管批次时再以版本化迁移扩展 StorageOwner.Scope，或添加持久的权益索引并通过 SimulationWorld 统一事务管理。不要不兼容地修改旧枚举序列化含义。仓库旧数据里的 PERSON/HOUSEHOLD/CAMP 必须不变。

## 3. 开发顺序及明确的阶段门

| 阶段 | 内容 | 进入下一阶段的验收门 |
| --- | --- | --- |
| M0 | 多数量易货、拆分批次、交易预留与原子结算 | 无货物复制；双方可部分成交；重放一致 |
| M1 | 本地市场、有效需求、报价、成交价格 | 不能超买/超卖；无钱不成交；价格来自订单 |
| M2 | 货币账户、发行/注销、支付结算 | 货币账本对平；旧存档兼容 |
| M3 | 在途货物与商路、运费、货损 | 卸载/重载不能复制货物；无运力不能发货 |
| M4 | 作坊生产、产权、委托加工、雇佣关系 | 所有原料/工时/收入有来源 |
| M5 | 信贷、赊账、债务到期与违约 | 资产=负债核对；违约有利益后果 |
| M6 | 行会、市政政策、税与社会指标 | 实际改变准入/报价/培训/财政及居民行为 |
| M7 | 城镇空间可见化与 Minecraft UI | 名册有因果解释；加载附近才做实体展示 |

M0-M2 是第一批可交给编码 Agent 的最小合同。M3-M7 不应阻断其合并。

## 4. 商品需求和订单模型

每个需求有 quantityNeeded、urgency、reservationDays、budget、sourceReason。区分：

- NEED：生存和生产所需要的数量，无现金也能成立。
- INTENT：居民愿意采购的数量与最高接受价格。
- EFFECTIVE_DEMAND：冻结购买力和已有订单承诺后可支付的数量；不可把 NEED 直接作为市场订单。
- PUBLIC_CLAIM：公仓保障、配给等非市场需求；不得伪装成现金买单。

需求算法初版：

1. 对每个家庭计算未来 2 日基础食物储备目标，实际所缺为 NEED。
2. 按必要性、信息可信度和可承受价格生成 INTENT；远地市场不能读取真实远端库存。
3. EFFECTIVE_DEMAND = min(INTENT.quantity, floor((cashAvailable + verifiedCreditLimit - priorReservations) / limitPriceMinor))。
4. 卖单仅能使用生产者拥有可转让权、未被预留且未腐败的真实批次。
5. 每个主体最多每日 4 笔新订单；每个市场每商品最多 64 个活跃买卖单（参数，超额延至下日）。

默认 foodReserveDays=2 沿用旧 TradeConfig 的语义；新的市场功能默认关闭。生产需要单独来自有效订单和本地生产计划，不能仅为制造市场需求循环购入再出售。

## 5. 撮合和价格（不使用随机自动调价作为成交价）

**成交以订单为准：**

- 按商品分组，买单按最高出价/最早时间/orderId 排序；卖单按最低报价/最早时间/orderId 排序。
- 仅当 bid >= ask 时匹配；成交单位价先采用卖方 ask（可在 EconomicConfig 未来切换，但整个结算必须同口径）。
- 成交量 = min(买单余量、卖单余量、实际可付数量、预留库存、运输/仓储约束)。
- 交易前在单事务中复核权利、库存、资金、资格、税率；失败释放预留，不产生部分货物转移。
- 历史“参考价”是展示和 AI 报价参考，不代替实际成交价格。无成交时沿用上次有效价并注明 stale，不凭空生成成交价。
- 参考价采用近 7 个核心日按成交量加权中位数，日间变化显示可限在 ±15%；**限幅只用于 AI 参考价格，不得覆盖双方真实成交价**。
- 买单无足够现金/授信时不参与成交，必须有欠款合同才允许延期结算。

所有货币值为 long minor unit，1 记账单位 = 100 minor，成交乘法 Math.multiplyExact；费率为整数 basis points，10000 bps = 100%，四舍五入规则固定为向下取整、余数归于纳税/收费方按每合同结算，避免不同机器不同精度。

初始演示报价（游戏参数而非史实）：TUBERS=100 minor、WOOD=150、STONE=120、STICK=40、SMOOTH_STONE=240。未在 ResourceType 与适配器中的小麦/铁器等不可在首轮凭空出现。

## 6. 货币制度：分离记账单位、支付媒介和信用债务

历史前提：货币不能写死为“先有易货、再有硬币、最后有银行”。可存在实物支付、标准计价、信用记账、公共发行等并存组合。

第一版实现一种 LOCAL_LEDGER_CURRENCY（聚落内部可转账记账单位），其创建必须由明确发行规则授权，不是开箱生成。独立测试场景通过 CURRENCY_ISSUED 事件注入期初货币并附发行者/原因；生存世界不得在城镇成立时无条件给所有人发钱。

- MoneyTransfer 生成一借一贷两条平衡分录，SUM(deltaMinor)=0。
- 发行/注销仅 TreasuryAuthority 可执行并分别记录 CURRENCY_ISSUED/CURRENCY_RETIRED；货币存量变化只能等于累计净发行额。
- 交易款、税和商队运费均通过同一账户系统转账；商人卖得的钱不是创造出来的新货币。
- 同一合约 idempotencyKey 在断线重试、存档重载后不得重复支付。
- 应付账款不是现金：信用结算需同步创建 DebtContract；只有独立的合法货币发行事件才会改变基础货币存量。
- M2 暂不把货币做成 ItemStack，避免仅转入 GUI 就产生第二份钱。以后真要铸币，必须与真实物品进行显式、原子映射。

建议新增账本 MoneyLedger，保持当前 ResourceBatch 的物资守恒算法不被污染。总货币守恒（除有来源的发行/注销）和债权债务对称分别断言。

## 7. 运输、风险与信息不对称

现有 CampLink(routeId, firstCampId, secondCampId, travelHours) 是短途社会联系，不要直接扩大它的构造约束或改变旧路径。新增 CommerceRoute，引用已知 CampLink 或建立新的分段路线图。

默认运输候选参数：一支小商队最大 64 份货物、每游戏日最多 1 次启程、基础运费 10 minor/批 + 1 minor/单位/分段、发车准备成本 1 劳动小时。所有数值集中 EconomicConfig。

运输状态：PLANNED → LOADED → IN_TRANSIT → DELIVERED，或 CANCELLED/LOST。

- 仅 LOADED 才实际从原保管者移走批次；在途货物只有一个权威保管标识。
- 到达触发批次转移及 event causalLinks，目的地满仓则停在在途/待卸货状态，不可直接删除。
- 路线事件可以导致 delay、损坏或被劫；损失要有物资事件且进入守恒损耗项。
- 超过信息时效的外地报价可以诱发错误决策，不能直接更新为服务器真实库存。
- 远程模拟不强行加载区块；实体仅在实际加载范围内呈现，回归模拟时先去重身份与货物。

## 8. 作坊、劳动和利润的拆分

最小生产组织：
- INDEPENDENT：自有工具、自购材料、自主安排劳动、自己获得销售收益。
- FAMILY：家庭产权与家庭劳动，多成员协作。
- COOPERATIVE：共同控制、公共预算、按组织协议分配。
- PUTTING_OUT：商人控制原料/订单与产品去向；工匠保留部分工具但获加工报酬。
- WAGE_WORKSHOP：经营者控制主要生产资料，雇佣劳动者获得工资。
- LORD_WORKSHOP：与既有领主/依附关系共存，不预设是否为“资本主义”。

必须拆分三类流水：商品销售额、商业流通利润、生产经营盈余。最小会计恒等式：
  经营期末资产 - 期初资产 = 销售收入 - 采购/生产投入 - 运费 - 税费 - 工资 - 其他实际损耗 + 已记录资本变动。
这里的“利润”是模拟会计指标，**不**自动推导为马克思意义的剩余价值总量；社会关系分析另看生产控制权、劳动义务和剩余占有规则。

在核心已有生产链未完全建成时，M4 只允许 STICK 或 SMOOTH_STONE 等已有资源流中的可追溯生产做验证。制作产物必须记录真实输入批次与完成事件；不准先写“每日作坊生成 10 铁锭”。

## 9. 信贷、破产、阶层与不平等交换

信用合同默认参数：到期 7 核心日；M5 首版利率 0 bps（验证债权能力后再加可配置利率）；初期只允许认识的经济主体签订，无匿名自动无限授信。信用、信任与执行力分开记录。

账本：签发债务 -> 贷款人应收增加、借款人应付增加；若确实放款，MoneyLedger 另做现金转账；若赊购，货物交付后只挂应收/应付，不增发现金。还款减少双方债权债务并转移现金；违约保留未还余额，产生具体追索、声誉、担保或重组后果。

家庭经济状态最少保留：可消费库存、现金、可收债权、应付债务、生产资料权益、劳动时长、稳定收入来源。居民阶层不是职业，而由资产控制、劳动依附、收入来源、剩余占有关系动态诊断。同一角色可以兼任农户、商人和雇主。

城乡交换需检查价格及分配后果，而非强加“城市剥削农村”的固定公式：城乡买卖双方都有真实报价与能力约束，政策、垄断、路径控制可能导致不利交换，亦可能改善专业化供给。用成交量、实际利润、生活必需品可及性、债务负担、财富集中、迁徙等指标判读。

## 10. 城镇形成：多维诊断，不是升级按钮

每 7 日汇总、以 14 日窗口平滑：
- nonFarmLaborShare = 非农业劳动小时 / 全部生产劳动小时。
- essentialMarketDependence = 市场获得的必需品实物数量 / 实际消费的必需品数量。
- externalProvisionDependence = 外地取得的必需品实物数量 / 实际消费的必需品数量。
- completedTradeVolume = 市场实际成交的实物数量（不是挂单数）。
- routeCentrality = 有效商路中经过该地点的独立路线数量。
- administrativeServiceIndex = 已实现的治理、军事、仓储或公共服务功能数。

显示标签（可并列）：AGRICULTURAL, MARKET_TOWN, CRAFT_HUB, TRANSPORT_HUB, ADMIN_CENTER。建议的 MARKET_TOWN 初版诊断：14 日完成交易 >= 30 单位、非农劳动占比 >= 0.20、必需品市场依赖 >= 0.15，且跨两个统计窗口持续成立；三指标任意两个连续两个窗口不达标则“城镇功能衰退”。阈值为诊断和测试候选值，不是建造限制，也不能直接生成 NPC、仓库和商品。

军事驻地、行政城镇、交通节点不要求农业自给或先出现某种市场职业。城市可能在危机下收缩；不是单向技术树。

## 11. 行会、税收与公共治理

行会的实现变量：entryFeeMinor、apprenticeSlots、trainingHoursCost、qualityInspectionEnabled、exclusiveLicenseEnabled、maxMembers、guildTreasuryId、enforcementCapacity(0..100)。
- 培训必须消耗师徒劳动时间，并改变已有技能传播的机会。
- 排他性执照必须由有效市政/政治规则支持并产生执法成本；未被承认的行会不得凭空禁止任何人交易。
- 行会设最低价格或限制产量可能改变订单分布，也可能使工匠收益增加或消费者买不起。
- 不预设“行会=生产率加成”或“行会=纯负面”。

市政税收：首版只设销售税 taxRateBps 默认 200（2%），税款从实际现金结算中进入金库；没有现金付款的赊销先记税款应收，不允许账面税收凭空变成现款。可由市政决定废税、调整税率，视地方执行能力影响逃税/违规事件。后续再加入市场准入、公共道路费用、公益支出和政治代表机制。

## 12. 同一日经济结算的固定顺序

每个经济日，单一核心世界串行执行：
  1. 读取上一日已确定的事件与存档快照、结清昨日到期运输。
  2. 推进家庭食物和生产需求，读取持有者实际可用库存。
  3. 处理昨日到期合同/还款，记录无法履约和违约。
  4. 生产规划及可追溯生产（已交给真实生产系统的执行顺序必须保证不二次生产）。
  5. 创建报价并预留现金或商品份额。
  6. 先本地按确定顺序撮合，再提交货权+支付+税费事务。
  7. 出发商队，记录运费和在途批次。
  8. 进行非市场公仓保障/消费/腐败，并更新每日统计。
  9. 核验物资守恒、货币平衡、债权债务对称、订单剩余非负，持久化进度游标。

如果具体集成与 PrimitiveSocietySimulation 的消费时序冲突，要**调整单一模拟日的调度器**，不要在原 simulateDay 之外再单独扣一次饥饿/消费。

所有随机分支由固定 seed、day、eventId 或交易 ID 派生，不能依赖 JVM HashMap 非确定迭代。对同日重复调用使用 durable day/phase cursor，服务端重启后不能重复结算。

## 13. 事务、安全与持久化协议

- 事务 ID：tradeId/contractId/shipmentId + event causality；不可用仅客户端生成的无验证 ID。
- 预留：卖方批次冻结量，买方钱款冻结量，单一事务由 SimulationWorld 和 MoneyLedger 协同提交；失败全量回滚，严禁转货后客户端异常漏扣钱。
- 如首版无可靠数据库原子事务，用准备阶段快照校验 + 单线程同步提交 + WAL-like pending marker；重载时通过幂等 ID 和结果事件做恢复，不可对半完成交易装作成功。
- 存档 schemaVersion 单调递增；旧版缺失新字段则 Money/Economy enabled=false，不额外创造库存。
- 不记录玩家真实网络地址、Tailscale 信息或设备标识；仅游戏内部 UUID/actorId。
- 内部物资流与世界真实物品交接继续使用 MinecraftStockpileAdapter，库存成功插入后才外导，容量不足的剩余部分不减少。

## 14. 服务器性能与网络

- 市场和经济核算日级执行，决不对每名村民每 tick 扫描所有市场。
- 按 marketId + goodType 建索引；先只计算最近有活动的市场，订单容器按限制大小增量更新。
- 背景聚落只计算批次和账户，不开新 chunk；加载玩家附近才实体化商队/作坊。
- UI 以聚落概览摘要+按需分页显示历史，不把每个交易事件广播给全服。
- 服务端唯一决定成交，客户端只能提交意向请求并展示确认；客户端传来的价格和库存不可视为事实。
- 性能基线先测 3 聚落、60 居民、20 商品订单/日，以及 20 聚落、500 居民、1000 订单/日。正式阈值先记录 CI/server MSPT/p95，不在无硬件基线时编造“必达 2ms”。

## 15. 自动化测试矩阵（编码任务的 Done 定义）

| ID | 可复现场景 | 必须断言 |
| --- | --- | --- |
| S4-T01 | 家庭 A 的 TUBERS 批次数量 10，出卖 3 | A 少 3、B 多 3、拆分来源仍指向原事件 |
| S4-T02 | 同批次两个卖单各挂 8，原批次只有 10 | 不能同时冻结 16，至少一个拒绝/削量 |
| S4-T03 | 买单 bid=120、卖单 ask=100、数量 4 | 实际成交价 100、成交额 400 minor |
| S4-T04 | 买方只有 300 minor，商品价格 100，需求 5 | 现金成交最多 3，未成交 2 不消失 |
| S4-T05 | 货物不足/资金不足/无合法产权 | 原子拒绝，货物与账户与事件状态均不部分提交 |
| S4-T06 | 金库授权发行 10000 minor、转账 300 | 账户合计 10000；只有发行事件改变总额 |
| S4-T07 | 2% 销售税、成交额 1000 | 卖方、买方、金库三方变动合计 0，金库增 20 |
| S4-T08 | 同 tradeId 重试、保存两次、重载 | 只成交/扣款一次，事件 ID 因果合法 |
| S4-T09 | 商队装载 40、卸载时仓库仅容 20 | 目的地入 20、另 20 仍由在途保管，不复制 |
| S4-T10 | 赊销 500 minor，买方当前现金 0 | 货物仅交付一次，应付/应收各增 500，基础货币不变 |
| S4-T11 | 债务到期无钱偿还 | 债务不消失，有违约事件；没有现金负数 |
| S4-T12 | 取消/关闭 marketEnabled 的旧档世界 | 原始易货及其守恒/因果测试仍通过 |
| S4-T13 | 铁山村断路 7 日 | 在途不瞬移；实际粮食短缺与职业行为按真实库存变化 |
| S4-T14 | 城镇“达标→商路中断→衰退” | 标签由滚动指标形成和退化，不自动凭空盖建筑 |
| S4-T15 | 两个固定 seed、各 1000 日重放 | 相同初始状态得到相同账本哈希；每一日物资/货币/债务审计绿 |
| S4-T16 | 玩家卸载/重进区块时商队状态迁移 | 服务端不重复 spawn 含相同货物的实体 |
| S4-T17 | 无市场购买力但有粮食生存需要 | NEED>0, EFFECTIVE_DEMAND=0，不伪造订单；保障系统仍可分配 |
| S4-T18 | 许可证开启但市政执行能力不足 | 行会无权静默拦截所有非会员真实交易 |

实现先写纯 JUnit/独立核心测试，涉及真实箱子再写 GameTest；按仓库现行规则在有 JDK 25 构建环境时跑 ./gradlew build runGameTest。文档 PR 本身不代表这些测试已经通过。

## 16. 第一个三聚落实验（必须跑通）

- 麦河：农业为主，提供 TUBERS 与 WILD_FRUIT；食物产出由真实批次记录。
- 渡口：交易中介和 STICK 制作；商人可经营仓储与商队。
- 铁山：提供 STONE，依赖外部食物；不得凭空生产未映射的铁矿和铁锭。
- 基础测试人数 3 聚落合计 60 人，设置至少 4 个家庭/聚落和 2 位独立商人。经济日 1-30 日记录供需、价格、实际成交、资金分布、库存、延误与粮食缺口。

对照条件：baseline（无货币但可易货）、cash-market（货币支付）、blocked-route（第 8-14 日断路）、tax-shock（第 8 日将税率 2% 改为 12%）、credit-crunch（限制授信）、guild-exclusive（增加准入限制）。每个场景至少 3 个固定种子，输出 JSON/CSV 每日摘要与事件哈希，不强制所有场景都产生“预期结局”；测试的是守恒、规则生效和机制可解释。

检验指标：饥饿日、基本品可及性、非农业劳动占比、市场成交量、成交量加权价格、商人净收入、家庭财富分位、债务逾期率、商队延误率、城镇功能诊断变化、每周期 CPU 时间。所有指标需能回溯实际事件。

## 17. 交付给编码 Agent 的 P0 任务卡

**P0-A：市场账本与多数量交易（独立核心）**
- 新增 MarketOrder / MarketEngine / EconomicConfig；不修改现有 TradeConfig.disabled 默认行为。
- 在 SimulationWorld 的真实批次转移基础上实现多数量、预留、幂等交易。
- 完成 S4-T01/T02/T03/T05/T08/T12/T15；至少 3 种种子，1000 日守恒。

**P0-B：货币与预算（独立核心）**
- 新增 EconomicActor / MoneyLedger / CurrencyIssue；保留资金发行来源。
- 决策用有效购买需求，杜绝“无钱自动买到食物”。
- 完成 S4-T04/T06/T07/T08/T17；包括货币守恒与价格测试。

**P0-C：Minecraft 适配预备**
- 更新持久化方案和序列化测试，但 marketEnabled 默认 false。
- 商品只使用当前 ResourceType 的 8 种映射；暂不碰真币 ItemStack。
- 不在 GameTest 中创建无来源的真实铁矿/小麦。

**P1：物流+商人资产+作坊；P2：信用、行会、市政与空间形成。** 严格以 M0-M2 已实现的测试门为条件，避免过早扩展 UI。

## 18. 概念、证据与模拟规则的界线

- 史学依据 A：商人资本在资本主义生产方式之前已存在，不等于工业资本。参阅马克思《资本论》第三卷第 20 章：https://www.marxists.org/archive/marx/works/1894-c3/ch20.htm
- 史学依据 B：宋代商业、市场、货币化及城乡专业化并存，不能把欧洲城镇当唯一模板。参阅 Linda Walton (2023), Middle Imperial China 900–1350, Chapter 4：https://www.cambridge.org/core/books/abs/middle-imperial-china-9001350/an-economic-revolution/A42C058BA4A9CDB30736CBD6DC8AFBBA
- 史学依据 C：货币起源存在商品、国家和信用等不同解释，学界仍有争论。Mikael Fauvelle (2025)：https://link.springer.com/article/10.1007/s10816-025-09694-9
- 史学依据 D：行会的准入、价格、技术与政治效果不能定性为天然正面。Sheilagh Ogilvie (2014), Journal of Economic Perspectives：https://swlb2.aeaweb.org/articles?id=10.1257%2Fjep.28.4.169
- 模拟假设：2% 默认税率、货币 minor=1/100 单位、价格参考 7 日窗口、订单数限制、城镇功能阈值、商队载量等完全是**待校准的游戏参数**，不代表历史实际比例。
- 研究争议：不同地域的城市形成、市场发展、商品货币/信用货币先后，以及行会净效应都可能不同。项目应把参数和制度机制开放给情境实验，而不是编码成单一路径。

## 19. 本轮未实施与风险登记

- 本文仅编制研究规格；未编写 M0-M7 的 Java 实现，未新增或运行测试，未验证客户端 FPS/TPS。
- 最大技术风险：旧存档兼容、货物与资金跨账本事务的一致性、模拟实体化/反实体化复制、跨聚落信息泄漏和大量订单引发的主线程卡顿。
- 最大研究风险：用商业化程度代替社会形态判定、把所有市场交换当作自愿公平、把人物职业当阶级、把信用错当凭空生成实物。
- 合并后开发 Agent 应优先完成 P0-A；任何变更导致旧 primitive tests 守恒失败，都须阻断合并。

**验收总则：真实资源守恒、货币账本有来源、债权债务对称、因果可追踪、制度可并存、玩家可观察、服务器可承受。**


## 20. 与现有研究 Issue #36 对齐：跨真实聚落的世界级协调器（必须先解决）

**这是现有代码中的硬阻塞，不是可选优化。** 当前 SettlementSimulationState.create(settlement) 为每个真实聚落分别建立一个 SimulationWorld。独立核心的 TradeSystem 可以在**同一个抽象 SimulationWorld** 中操作多个 camp，但它不等于真实 Minecraft 世界里两个聚落 SavedData 间已经可以原子交易。已在 [S4 研究 Issue #36](https://github.com/ThySummer14/coopcraft/issues/36) 指明该边界。

因此修正第 3 节实施先后关系：

- **S4-R0（在真实游戏跨聚落交易前必须完成）**：明确世界级 TradeCoordinatorSavedData 与各聚落 SettlementSimulationState 的资产归属/外部交接合同；设计持久化状态机、故障恢复、NBT 迁移和 GameTest。
- **M0 / P0-A**：先仅在独立 Java SimulationWorld 中做多 camp、部分批次易货与守恒，不宣称能跨真实聚落。
- **M1 / M2**：仍是独立核心的价格、货币、有效需求；与真实 Minecraft 的跨聚落交易集成必须受 R0 阶段门约束。
- **真实聚落适配**：不能把两个 SettlementSimulationState.world() 直接拼为一个可随意写的全局世界；要用显式交接、唯一身份和跨世界事务日志。

建议世界级持久化交易状态（TradeCoordinatorSavedData）：
  OFFERED → RESERVED → DISPATCHED → IN_TRANSIT → DELIVERED → SETTLED。
  异常可进入 CANCELLED/COMPENSATING，但不得从 SETTLED 返回未结算并再次付款。

每笔交易保留 tradeId、源/目的 settlementId、原批次 ID 与来源事件链、权利人、货物清单、资产冻结标记、支付预留、步骤编号、创建/过期时间、当前状态、最后成功写入游标。

必须遵守：
1. 真实货物在买方确认入库前只能由卖方（尚未出库）**或**世界级 in-transit/escrow（已经出库）**其中一方**权威持有；不能同时出现在两个聚落抽象库存或实体箱子中。
2. 卖方出库使用已有 SimulationWorld 的显式 external export，目的地通过有来源的 external import 导入；世界级桥接账本按 tradeId 把双方事件关联。对全世界总量只计一次转移，不误将交易计作新资源生产。
3. 真实存档分属不同 SavedData 时，不可假设单次函数调用天然跨文件原子持久化。必须定义 durable intent/pending 状态，写前检查、可恢复事务日志，分阶段只执行一次的 side effect；恢复时根据凭证重放未完成步骤或执行补偿，不通过“再发一份货”解决异常。
4. 未加载聚落仅参与可验证的汇总/在途状态更新；不强制加载远地区块。远方报价是带时间戳的报告，不是直接全知读真实箱子。
5. 已有 SettlementStage.PRIMITIVE/AGRICULTURAL/TOWN 是当前生产与聚落进展轴，SocialOrganization 也另有有限状态；不可用 Stage.TOWN 或 enum ordinal 直接定义 S4 生产关系。
6. 一张经济订单由世界级协调器持有跨聚落状态，聚落本地库存账本保留其资源权威；所有玩家指令由服务端校验。

新增强制 GameTest：发货前、冻结后、出库后、到达前、付款后分别模拟中断及两次重载；进行仓库满、接收者迁移/死亡、重复网络请求、两个玩家同时购买同一批次；这些失败路径都必须满足：没有重复的真实商品、货币不重复支付、合约有终态、库存来源可追溯。真实游戏功能未通过这些测试前，只能作为纯模拟实验推出。

本节与 Issue #36 是同一条研究主线，避免平行新增一个矛盾的跨聚落设计。
