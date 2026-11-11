# CoopCraft Probe

开发期专用、**不随模组发布**的 Minecraft 客户端/服务端 HTTP 探针，用来让 Qoder 直接观察和操作真实游戏。

灵感来自 [yan-mc/minecraft-mod-dev](https://github.com/yan-mc/minecraft-mod-dev)（MIT）：它证明了“真实客户端 + UI 控件树 + 截图 + YAML 卡片”这条路线可行。本项目为 **Minecraft 1.21.1 + Fabric** 重写的轻量版，不依赖 DeepSeek Harness。

## 边界

- 只绑定 `127.0.0.1`，默认 token `coopcraft`。
- 只用于 dev 环境：**不要**放进 PCL 正式实例、Mac 测试服或整合包。
- 客户端必须是真实客户端（OpenGL），不是 headless；服务端可以是独立 dev server。
- 不做 WASD 实时操作，不替代人的手感/美术验收。
- 目前没有伪造玩家和 tick 冻结；需要玩家在线的卡片要先手动/命令进服。

## 构建

```sh
python -m pip install -r tools/probe/requirements.txt
./gradlew -p tools/probe build
```

产物：

```text
tools/probe/build/libs/coopcraft-probe-0.1.0.jar
```

## 装进开发环境

主工程的 `runClient` / `runServer` 共用 `run/`。把探针 jar 放进主工程的 `run/mods/`：

```sh
tools/probe/prepare_dev_run.sh
```

脚本会构建探针、把它复制到 `build/probe-server/mods` 和 `build/probe-client/mods`，并写入离线 server.properties/EULA。然后：

```sh
./gradlew runProbeServer   # 服务端探针：39001（build/probe-server）
./gradlew runProbeClient   # 客户端探针：39002（build/probe-client）
```

客户端到标题界面后可以调用 `/connect` 自动连接 `127.0.0.1:25565`。

探针也可以在自己的目录里单独启动（只有探针和 Fabric API，没有 CoopCraft）：

```sh
./gradlew -p tools/probe runClient
./gradlew -p tools/probe runServer
```

## HTTP API

默认端口：客户端 `39002`，服务端 `39001`；请求头 `X-Probe-Token: coopcraft`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/status` | 屏幕/标题/玩家/FPS（客户端）；tick/玩家数（服务端） |
| GET | `/ui/snapshot` | 当前 Screen 实例、控件树、容器槽位、该实例最近绘制的文字 |
| POST | `/ui/click` | `{slot}` 或 `{x,y,button,shift}` |
| POST | `/ui/close` | 关闭当前界面 |
| POST | `/ui/reset` | 清空文字捕获 |
| POST | `/key` | `{mapping:"key.coopcraft.add_waypoint"}` 或 `{key:"key.keyboard.n"}` |
| POST | `/player/use` | `{x,y,z,face,shift}` 客户端右键方块；潜行时发送真实按下/释放包 |
| POST | `/player/use-entity` | `{uuid,shift}` 右键已加载实体；交互包显式携带潜行标志，HTTP 等待实际发送结果 |
| POST | `/command` | 客户端以玩家身份发命令；服务端以控制台发命令，最多等待主线程 20 秒 |
| POST | `/connect` | 客户端连接服务器：`{host,port}` |
| POST | `/quit` | 关闭客户端 |
| POST | `/screenshot` | 截图到 `screenshots/`，返回绝对路径 |
| GET | `/world/block` | 服务端读取方块 id/state、`entityTicking`；有方块实体时附 NBT |
| GET | `/world/settlement-data` | 只读聚落存档 NBT，包括需求、照料求助与工作承诺 |
| GET | `/world/settlement-survey` | `{x,y,z}` 只读真实村庄结构、床位、耕地、作物和工坊调查，用于核对聚落起源 |
| GET | `/world/resident-goal` | `{uuid}` 只读居民行动、阻碍、目标、背包物品和内部 `VillagerWorkGoal` 调试状态 |
| GET | `/world/blueprints` | 只读检查已加载的火塘/住宅蓝图；未安装 CoopCraft 时返回不可用 |
| GET | `/log/tail` | 服务端 `logs/latest.log` 尾部 |

首次进入测试区应先等待实体区块激活。1.21 的物品 NBT 数量字段是小写 `count`，不是旧版 `Count`；写错会静默得到默认数量 1。

## YAML 卡片

`tools/cards/` 下的卡由 `tools/probe/run_cards.py` 执行：

```sh
python tools/probe/run_cards.py --card tools/cards/client_smoke.yml
python tools/probe/run_cards.py --card tools/cards/settlement_roster.yml --dry-run
```

支持的步骤：

| 步骤 | 说明 |
| --- | --- |
| `server_command` / `client_command` | 执行命令 |
| `key` / `mapping` | 触发按键映射 |
| `player_use` | 客户端右键方块；可选 `shift: true` 模拟潜行交互 |
| `player_use_until` | 按坐标反复右键方块，直到 `screen_contains` 指定界面出现；`timeout_ms` 限总等待，处理首次区块激活抖动 |
| `player_use_entity` | 按 `{uuid,shift}` 右键已加载实体 |
| `click_text` | 按控件文字点击；可传字符串或候选列表（例如 `["居民","Residents"]`） |
| `click_text_until` | 每页清空文字捕获并查找 `text_any`，未找到则点击 `button` 翻页；`max_clicks` 限页数，防止跨页旧文字造成假阳性 |
| `wait` | 毫秒 |
| `screenshot` | 截图并等待文件落盘 |
| `assert_ui` | `title_contains` / `title_contains_any` / `text_contains` / `text_contains_any` / `screen_contains`；可选 `timeout_ms` 轮询等待 |
| `ui_click` | 直接转发 `/ui/click`，`{slot}` 或 `{x,y,button,shift}` |
| `ui_close` | 关闭当前界面 |
| `assert_json` | `side`、`endpoint`、`path`、`op`、`value`；`endpoint` 可用 `status`、`ui` 或完整探针路径，POST 参数放 `request`；`request_any` 可给多组互斥候选参数并断言任一请求满足；可选 `timeout_ms`；路径中的 `*` 展开列表，`contains_any` 接受候选值列表，`not_contains` 排除事件或文字 |
| `assert_block` | 服务端方块 id |

`setup.server_commands` 在步骤前执行；`--dry-run` 只报告计划，不发请求。未知字段进入 `warnings`，不会静默通过。`data.*` 继续表示响应根字段；若接口自身还有 `data` 对象（例如聚落 NBT），解析器会在根路径未命中时自动进入该对象。

## 示例卡

- `client_smoke.yml`：标题界面连通性 + 截图。
- `waypoint_screen.yml`：按 N 打开路径点命名界面并检查标题。
- `settlement_roster.yml`：固定测试平台上放钟/村民/箱子，右键钟开后查看名册，断言居民分工、共同生活、照料与供养观察。会修改世界，**只在一次性 dev 世界跑**。
- `settlement_stage.yml`：搭建带床/农田/工作站的村庄，右键钟后断言自动识别的文明阶段，并检查居民页不再显示原始 `Villager` 名称。会修改世界。
- `settlement_care.yml`：用固定 UUID 的高社交性居民与幼儿完成真实照料时段，重开名册验证事件和界面文字。会修改世界，**只在一次性 dev 世界跑**。
- `settlement_care_food.yml`：直接右键幼儿赠送真实面包，等待照料完成，再检查箱子物品和存档中的求助、赠送、照料因果链。会修改世界，**只在一次性 dev 世界跑**。
- `settlement_coordination.yml`：真实公仓、建筑材料和浆果丛验证缺粮暂停与补粮恢复，并截图；使用独立测试区。
- `settlement_coordination_retry.yml`：无浆果时断言多个规划周期没有恢复事件，补成熟浆果后才恢复，并检查工作标签翻译。会修改世界，**只在一次性 dev 世界跑**。
- `settlement_farming.yml`：在农业聚落中用现有食物承诺收获真实甜菜，先断言作物恢复零龄，再用 `request_any` 核对任一居民真实持有收获物；同时验证精简名册显示“下一步：补充食物”。甜菜与种子分离使产出确定，封闭平台并清理背包与旧掉落物可避免重跑污染。会修改世界。
- `minimal_camp_prototype.yml`：M2 最小可玩原型。两名成年创始人建档后仅保留一名工作居民，用公仓仅有的一个马铃薯在 4×4 田首次播种，再加速成熟并断言留种补种；床位隔离住房任务，不测试家庭或土地产权。会修改世界。
- `natural_village_origin.yml`：使用种子 `34239575550581757` 的新 dev 世界，在自然生成的住宅型平原村庄真实敲钟，确认没有耕地或作物的村庄仍从农业起源开始；校验种子、真实结构、床位、名册和存档，不放置人工结构。
- `container_slot.yml`：打开箱子、读取槽位、点击槽位并断言物品消失。

本地实测：`client_smoke.yml`、`waypoint_screen.yml`、`settlement_roster.yml`、`settlement_stage.yml`、`settlement_care.yml`、`settlement_care_food.yml`、`settlement_coordination.yml`、`settlement_coordination_retry.yml`、`settlement_farming.yml`、`minimal_camp_prototype.yml`、`natural_village_origin.yml`、`container_slot.yml` 均 `PASS`；截图保存在对应 run 目录的 `screenshots/`。首次启动较慢时可用 `--timeout 30` 放大服务端命令等待。

## 后续

- 提供更精简的成员/行动结构化快照，避免诊断时读取完整聚落 NBT。
- tick 冻结/步进，减少等待。
- 客户端 `/connect`、伪造玩家、更多容器断言。

## 许可证

探针代码为本仓库原创，MIT；只借鉴了 minecraft-mod-dev 的架构思路，没有复制其代码。
