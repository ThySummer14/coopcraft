# 聚落命令与统一实测流程

命令只选择**玩家所在维度、距离玩家64格内最近的已登记聚落中心**；等距按聚落ID稳定选择。没有目标时明确报错，不创建聚落。控制台无玩家身份不能执行。`/execute` 改写位置或维度也不会替代实际玩家位置与维度。

## 命令

| 命令 | 权限 | 作用 |
| --- | --- | --- |
| `/coop settlement status` | 普通玩家 | 查看开关、真实/抽象公仓库存、施工和待建项目 |
| `/coop settlement materials` | 普通玩家 | 同上，重点用于核对项目剩余消耗与缺口 |
| `/coop settlement simulation <开关> <true或false>` | OP等级2 | 通过现有服务修改开关 |
| `/coop settlement import <资源> <数量>` | OP等级2 | 从已登记公仓真实物品显式转入抽象库存 |
| `/coop settlement export <资源> <数量>` | OP等级2 | 从抽象批次显式转回已登记公仓 |

开关固定为 `survival`、`production`、`skills`、`growth`、`norms`，均有补全。后四项开启前必须先开 `survival`。关闭 `survival` 同时关闭其余四项，但**保留抽象库存**。观察命令不会创建模拟状态或开关，也不会导入/导出物品。

数量必须为整数 **1–4096**。资源使用下列小写名字，支持Tab补全；不接受任意物品ID。

| 资源 | 对应真实物品 |
| --- | --- |
| `wild_fruit` | 甜浆果 |
| `tubers` | 马铃薯 |
| `fish` | 生鳕鱼 |
| `game` | 生牛肉 |
| `wood` | 橡木原木 |
| `stone` | 圆石 |
| `stick` | 木棍 |
| `smooth_stone` | 平滑石 |

仅普通、没有自定义组件修改的对应物品可导入。例如烤马铃薯、熟鳕鱼、其他树种原木不在映射中。`fish` 是资源名，不能写成 `cod`。

## 可复制的游戏内流程

1. 在附近32格内至少有两名村民的位置放置钟，正常右键钟，登记聚落及已加载居民。不要潜行点钟。
2. 在聚落中心48格内放箱子或木桶，潜行右键登记公仓。放入普通马铃薯64个，以及准备测试的原木、圆石等。确保仓库及相邻区块已加载，箱子没有被遮挡。
3. 站在聚落中心64格内，以OP等级2玩家依次执行：

```text
/coop settlement status
/coop settlement simulation survival true
/coop settlement simulation production true
/coop settlement simulation skills true
/coop settlement simulation norms true
/coop settlement import tubers 64
/coop settlement materials
```

**开启survival后真实公仓食物不会自动导入。未显式import就可能缺粮。** 请紧接着导入食物，并根据居民数持续观察补粮。若仓库没有足够马铃薯，返回实际交接数会小于64；不能把请求数当成实际库存。也可放入生鳕鱼并执行 `/coop settlement import fish 32`。

4. 可选：`/coop settlement simulation growth true` 开启已有的成长映射；它本身不保证立刻出生或自动繁殖。
5. 测试材料往返：公仓放入圆石后依次执行：

```text
/coop settlement import stone 16
/coop settlement materials
/coop settlement export stone 5
/coop settlement materials
```

预期导入时真实库存下降、抽象库存等量增加；导出反之，不凭空生成。导出依模拟仓库批次顺序（到期日优先，其次批次ID），不混用其他资源。满仓可能只导出一部分或0个，反馈必须与实际移动数一致。

6. 执行 `/coop settlement simulation survival false`，检查五个开关均为false，抽象库存仍在。需要时用export取回；关闭模拟不阻止显式交接。
7. 用普通玩家验证status/materials仍可读，而simulation/import/export不能使用。离开所有聚落中心64格后，再执行status，应提示无聚落而不创建新聚落。

## 材料解释与边界

- 真实库存列出公仓全部物品ID与数量；抽象库存单独列出资源数量。两者不会重复算成同一库存。
- 当前施工只统计已保存 `nextIndex` 之后仍需消耗的物品；门/床的无需消耗续半块不重复计费。
- 每个待建项目独立计算缺口，没有预留或扣除其他项目的需求。原木制木棍、工作台制作等潜在转换不提前折算成已拥有物资。
- 建造缺口只计入当前可使用物资；抽象库存需要survival和production同时开启才能用于建造。
- 未登记公仓、公仓被移除/阻挡、所在或箱子水平邻块未加载时，不扫描或强制加载区块。仍可查看抽象库存与需求，但缺口显示未知，交接被拒绝。
- 命令同步运行在服务端线程，身份、位置、聚落、公仓全部由服务端解析。没有任意坐标、库存或批次ID参数。

## 自动化检查

新增 `SettlementCommandsTest` 覆盖真实命令树的读写权限、开关/资源白名单、Tab补全、整数上下界、最近聚落范围、材料差额、导出批次顺序与满仓部分交接。运行方式：

```sh
./gradlew test --tests '*SettlementCommandsTest'
```

本增量不启动本地游戏服务器。游戏实测按以上流程统一执行。
