# AGENTS.md

本文件是给 AI 协作者（以及人）看的长期规则。改代码、写文档、提交前先读一遍。
文件名用 `AGENTS.md` 是 agent 生态的约定入口，别改名成 `agent.md`。

## 1. 回答风格（用户明确要求）

- 中文回答。
- 先给结论，再给细节；能一句话说清就不写第二句。
- 排版：短标题 + 表格 + 短列表，不写大段散文，不堆表情。
- 不寒暄、不复述问题、不写"希望对你有所帮助"之类的话。
- 需要用户确认时，直接在回复末尾列成问题；**不要调用交互式提问工具**（本机存在"用户已回答但 agent 收不到"的 bug，会一直白等）。
- 引用文件给仓库内相对路径 + 行号，例如 `src/main/java/com/thysummer/coopcraft/net/CoopNetworking.java:28`。

## 2. 项目速览

| 项 | 值 |
| --- | --- |
| 内容 | Minecraft 1.21.1 Fabric 联机生存向模组（共享进度、队友血量、聚落模拟等） |
| modid | `coopcraft`，注册一律 `CoopCraft.id("path")` |
| 构建 JDK | 25（Fabric Loom 1.18.4 要求） |
| 目标字节码 | Java 21（`options.release = 21`） |
| Minecraft / Loader / API | 1.21.1 / Fabric Loader 0.20.1+ / Fabric API 0.116.17+1.21.1 |
| 映射 | Mojang 官方映射（类名如 `ResourceLocation`、`ItemStack`） |
| 许可证 | MIT |

## 3. 本机开发环境

```sh
export JAVA_HOME=/Users/filisi/工作/会话/jdks/jdk25        # 构建
export JAVA_HOME21=/Users/filisi/工作/会话/jdks/jdk21      # 运行服务端/客户端
export GRADLE_USER_HOME=/Users/filisi/工作/会话/.gradle
```

系统代理开启时（`127.0.0.1:7897`），Gradle 需把 `*.fabricmc.net`、`libraries.minecraft.net`、`repo.maven.apache.org` 排除在代理外，否则报 `bad_record_mac` / `Read timed out`；代理不在线时全部直连。现成配置见 `/Users/filisi/工作/会话/coopcraft-env.sh`。

## 4. 常用命令

| 命令 | 用途 |
| --- | --- |
| `./gradlew build` | 构建，产物 `build/libs/coopcraft-0.1.0.jar` |
| `./gradlew runGameTest` | 跑 Fabric GameTest（服务端逻辑） |
| `./gradlew runClient` / `runServer` | 本地起客户端 / 服务端 |
| `./gradlew build runGameTest` | 提交前的标准检查 |

服务端安装：`JAVA_HOME=<jdk21> ./setup.sh <jar>`（`server/README.md` 有完整流程）。

## 5. 代码约定

- 主逻辑放 `src/main`；只在客户端跑的代码（渲染、按键、HUD）放 `src/client`（`splitEnvironmentSourceSets()`）。
- 新增网络包：在 `src/main/java/com/thysummer/coopcraft/net/CoopNetworking.java` 注册 `PayloadTypeRegistry`，S2C/C2C 分清楚。
- 资源放 `src/main/resources/assets/coopcraft/`，语言文件 `lang/zh_cn.json` + `lang/en_us.json` 同时加。
- 用官方映射的类名和参数顺序；网上 Yarn 教程的写法对不上，照抄前核对。
- 新增依赖必须在 PR 里说明理由。

## 6. 测试与合并

- `./gradlew build` 必须绿。
- `./gradlew runGameTest` 有一个已知不稳定用例：`CivilizationGameTest#foundsandreusessettlement`（`timeoutTicks = 60000`，靠村民寻路搭 Cottage，位置随机，本地常超时）。失败先重跑一次，别急着改逻辑；CI 上通过说明代码没问题。
- 开 PR 前先把最新 `main` 合进分支解冲突（仓库历史惯例：`Merge branch 'main' into feat/xxx`），CI 绿再合并。
- 不直接 push `main`；不对别人正在用的分支 force push。
- 分支命名：`feat/`、`fix/`、`chore/`。

## 7. 整合包与第三方资源（`modpack/`）

- `modpack/mods/` 放第三方客户端模组 jar，`modpack/licenses/` 放许可证原文，`modpack/sources/` 放对应源码（GPL 类必须给）。
- 每加一个第三方模组，在 `modpack/README.md` 的表格登记：名称、版本、来源链接、许可证、SHA-256；有修改的写明改了哪里。
- `modpack/reference/` 放**借鉴用的参考整合包**：只存 `modrinth.index.json` 这类清单和说明，不存原始大文件（GitHub 单文件 100MB、仓库建议 1GB 以内）。原始压缩包放本地或 Release。
- 安装说明面向 **PCL**（用户使用 PCL 启动器），给 1.21.1 + Fabric Loader 0.20.1+ 的步骤。

## 8. 不要提交

`run/`、`build/`、`.gradle/`、本地日志与崩溃报告、导出的 jar、任何密钥/token/服务器地址/玩家数据。`server/` 下的运行时文件已被 `server/.gitignore` 覆盖，注意别强行 `git add -f`。
