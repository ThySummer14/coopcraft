# 死者剪刀的现场清算与再利用

## 范围

第七十八增量允许管理员从真实死亡保管记录中，显式取回具有可验证死亡营地证据的公共剪刀，并解除本次借据。工具进入管理员的主背包，管理员需要自行携回公仓。没有自动传送到仓库，也不会凭借借用者离线或名册中消失，就清算任意物品。

工具名称、附魔、消失诅咒、当前耐久、自定义数据与其他组件保持原样。只在真实工具被目标槽接受后，移除本模组的合法剪刀借据，不生成或修理工具，不替换管理员的护甲、副手或已有槽位。

## 使用步骤

1. `/coop settlement cargo` 查附近遗物记录ID及死亡位置。
2. `/coop settlement cargo shears <记录UUID>` 只读查看该记录可清算的营地UUID、营名与剪刀数量。显示结果不会领取或解除借据。
3. 到原死亡点3格内，使用OP2权限执行 `/coop settlement cargo reclaim-shears <记录UUID> <营地UUID>`。这条明确命令就是对该记录及指定营地的清算操作，不另外建立跨时段确认票。
4. 成功进入主背包的剪刀可以手动带回对应公仓。此时已经解除借据，工具成为普通物品，玩家也可自由使用；命令不会持续替玩家保留归属或承诺已归还公仓。

清算使用完整UUID，不接受选择器或名称。执行源和真实玩家都需要OP2，玩家须在线、存活、非旁观；不能用`execute positioned`、跨维执行或提升命令源权限冒充现场。玩家和死亡点都须在边界内、已加载且实体活动，不强制加载区块。

## 可清算证据

- 独立死亡保管存档Version3新增每条记录的`DeathSettlement`，仅在实际死亡捕获时取当时唯一有效成员声明的营地UUID。
- 记录必须仍持有真实工具。合法Version1剪刀借据必须同时符合死亡营地、记录死者UUID及当前维度，且是单把剪刀，无其他用途标记冲突。
- 指定营地仍须存在并可确认是当前对象。任何维度存在该死者UUID的活体，或尚有成员声明、冲突、孤儿声明、数据读取不完整，都会阻止清算。最多检查32个已有维度，不通过加载区块寻找实体。
- 当前身份和实际到场授权在交接前后重复核对。背包满时保留原工具与借据；部分接收只扣真正移交的工具。重复执行不能再领已领取的物品。

## 旧档与保守处理

旧Version1最多8格、Version2最多9格，仍可读入并按原方式恢复实物，但不补猜死亡营地。迁移到Version3时缺少`DeathSettlement`继续表示未知证据；这类记录不能使用新清算动作。未来版本、损坏证据与不支持的格式保留禁用，不清空记录。聚落根存档保持24，剪刀借据仍为1。

未知借据、错误营地/借用者/维度、借用后换营再死亡，以及没有死亡营地证据的旧记录不会被自动解除。现有`cargo recover`/`rescue`继续原样领取所有符合原规则的实物，不帮忙去借据。已通过这些旧命令取入玩家背包的标记剪刀，不在本轮清算入口范围。越界遗物也没有新远程清算特例。

## 非死亡转换仍待后续

雷击变女巫不经过`die`。已核查的1.21.1实现直接尝试放入新女巫，再丢弃原村民；返回void的入世调用并不证明新实体成功存在。通用`Mob.convertTo`还可能在入世前复制并清空旧装备，Fabric转换通知也早于最终入世确认。不能据此将所有转换、卸载或discard当作死亡，提前搬走工具或补发副本。

本轮没有修改雷击、通用转换、跨维度或直接移除行为。普通死亡/僵尸感染仍沿已实现的早期死亡保管；非死亡转换的资产风险并未因此消除。后续应针对单一路径核实新旧实体与真实物品唯一保管后再接入。

## 转换行为的只读依据

- [Fabric雷击转换事件](https://github.com/FabricMC/fabric-api/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-entity-events-v1/src/main/java/net/fabricmc/fabric/mixin/entity/event/VillagerEntityMixin.java)
- [Fabric普通转换事件](https://github.com/FabricMC/fabric-api/blob/83c07162d88a703d15c8dae50cbbc12f4e9e63c3/fabric-entity-events-v1/src/main/java/net/fabricmc/fabric/mixin/entity/event/MobEntityMixin.java)
- [1.21.1雷击实现，反编译资料](https://github.com/Soumeh/1.21.1-Deobfuscated/blob/414d16968de22d168f6b636a9e8d1f80401161d7/minecraft/src/net/minecraft/entity/passive/VillagerEntity.java#L816)
- [1.21.1普通转换实现，反编译资料](https://github.com/Soumeh/1.21.1-Deobfuscated/blob/414d16968de22d168f6b636a9e8d1f80401161d7/minecraft/src/net/minecraft/entity/mob/MobEntity.java#L1344)

以上是固定版本源码及映射审阅，不代表已运行或字节码验证转换钩子。

## 验证边界

新增实际ItemStack/容器、存档兼容、权限策略和命令接线回归，编译与JUnit以本次提交自动CI为准。未启动Minecraft客户端、服务器或GameTest。真实命令执行、玩家移动/断线、存档重载以及模组共存仍待统一游戏验收。标准同步非重入容器转移不承诺任意第三方重入、异常setter或跨文件进程崩溃原子性。
