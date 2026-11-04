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
| GET | `/perf/frame` | 客户端最近 4096 帧间隔统计和累计长帧次数（仅世界渲染时） |
| POST | `/perf/reset` | 清空客户端帧间隔采样和累计计数 |
| GET | `/ui/snapshot` | 当前 Screen、控件树、容器槽位、最近一帧绘制文字 |
| POST | `/ui/click` | `{slot}` 或 `{x,y,button,shift}` |
| POST | `/ui/close` | 关闭当前界面 |
| POST | `/ui/reset` | 清空文字捕获 |
| POST | `/key` | `{mapping:"key.coopcraft.add_waypoint"}` 或 `{key:"key.keyboard.n"}` |
| POST | `/player/use` | `{x,y,z,face}` 客户端右键方块 |
| POST | `/command` | 客户端以玩家身份发命令；服务端以控制台发命令 |
| POST | `/connect` | 客户端连接服务器：`{host,port}` |
| POST | `/quit` | 关闭客户端 |
| POST | `/screenshot` | 截图到 `screenshots/`，返回绝对路径 |
| GET | `/world/block` | 服务端读取方块 id/state |
| GET | `/log/tail` | 服务端 `logs/latest.log` 尾部 |

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
| `player_use` | 客户端右键方块 |
| `click_text` | 按控件文字点击；可传字符串或候选列表（例如 `["居民","Residents"]`） |
| `wait` | 毫秒 |
| `screenshot` | 截图并等待文件落盘 |
| `assert_ui` | `title_contains` / `title_contains_any` / `text_contains` / `text_contains_any` / `screen_contains` |
| `ui_click` | 直接转发 `/ui/click`，`{slot}` 或 `{x,y,button,shift}` |
| `ui_close` | 关闭当前界面 |
| `assert_json` | `side`、`endpoint`（`status`/`ui`）、`path`、`op`、`value` |
| `assert_block` | 服务端方块 id |

`setup.server_commands` 在步骤前执行；`--dry-run` 只报告计划，不发请求。未知字段进入 `warnings`，不会静默通过。

## 示例卡

- `client_smoke.yml`：标题界面连通性 + 截图。
- `waypoint_screen.yml`：按 N 打开路径点命名界面并检查标题。
- `settlement_roster.yml`：固定测试平台上放钟/村民/箱子，右键钟开后查看名册和居民分工。会修改世界，**只在一次性 dev 世界跑**。
- `settlement_stage.yml`：搭建带床/农田/工作站的村庄，右键钟后断言自动识别的文明阶段，并检查居民页不再显示原始 `Villager` 名称。会修改世界。
- `container_slot.yml`：打开箱子、读取槽位、点击槽位并断言物品消失。

本地实测：`client_smoke.yml`、`waypoint_screen.yml`、`settlement_roster.yml`、`settlement_stage.yml`、`container_slot.yml` 均 `PASS`；截图保存在对应 run 目录的 `screenshots/`。

## 联机掉帧诊断（dev-only）

此功能只在开发客户端探针生效，不修改正式模组或 Mac mini。进入真实游戏世界后采集（主菜单没有 WorldRenderEvents.END 帧间隔）：

```sh
python3 tools/probe/collect_perf.py --duration 90 --interval 1 --with-server \
  --scenario idle-empty-hand --output build/perf/idle-empty-hand.json
```

只保存 FPS、帧间隔统计、服务器 tick 和本地探针响应耗时，不保存 IP、玩家名和令牌。生成文件放在被 Git 忽略的 build/ 中。离线测试命令：

```sh
python3 -m unittest discover -s tools/probe/tests -p 'test_*.py'
```

帧间隔不等于纯渲染 CPU/GPU 耗时；localhost 探针耗时不等于玩家的网络 RTT；tick 差分不等于服务端 MSPT。完整实验矩阵参见 [双人联机掉帧排查](../../docs/client-stutter-triage.md)。

## 后续

- `/coop/state` 结构化聚落快照（成员/床位/火塘/分工），不再依赖 UI 文本。
- tick 冻结/步进，减少等待。
- 客户端 `/connect`、伪造玩家、更多容器断言。

## 许可证

探针代码为本仓库原创，MIT；只借鉴了 minecraft-mod-dev 的架构思路，没有复制其代码。
