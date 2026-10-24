# CoopCraft

[![build](https://github.com/ThySummer14/coopcraft/actions/workflows/build.yml/badge.svg)](https://github.com/ThySummer14/coopcraft/actions/workflows/build.yml)

和朋友一起开发的 Minecraft Fabric 模组，目前是能跑起来的空壳。

## 功能

- **共享进度播报**：队友达成进度时，你的屏幕上也会弹出同一个成就 toast。

  原版只会在聊天栏广播一条公告，toast 只有达成者本人能看到；装了这个模组之后，
  同一服务器上的所有人都会一起看到弹窗。配方解锁（`minecraft:recipes/...`）不会触发，
  避免捡到新物品就刷屏。

- **队友坐标分享**：按 `V`（可在“控制”里改）把当前坐标和维度发到队友聊天栏，
  点击聊天里的坐标可以直接复制。

- **队友死亡点**：队友死亡时，死亡坐标和维度会发到其他队友的聊天栏，
  点击坐标复制，方便回去帮忙捡装备。

## 版本

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| Fabric Loader | 0.20.1+ |
| Fabric API | 0.116.17+1.21.1 |
| Java | 21 |
| 映射 | Mojang 官方映射 |

## 本地开发

构建需要 JDK 25（当前 Fabric Loom 要求），模组本身按 Java 21 编译、在 Java 21 上运行。推荐 IntelliJ IDEA，并把它设置成用 JDK 25 跑 Gradle。

Mac 上用 Homebrew 装的 JDK 25：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

```bash
./gradlew runClient   # 启动带模组的客户端
./gradlew runServer   # 启动带模组的服务端
./gradlew build       # 构建，产物在 build/libs/
```

首次构建会下载 Gradle、Minecraft、映射和依赖，需要几分钟。之后走缓存会快很多。

## 给朋友装来一起玩

1. 安装 Fabric Loader（Minecraft 1.21.1）
2. 把 [Fabric API 0.116.17+1.21.1](https://modrinth.com/mod/fabric-api/versions?g=1.21.1) 和 `build/libs/coopcraft-0.1.0.jar` 放进 `.minecraft/mods`
3. 两端都装好模组后，一方开单人世界并在“对局域网开放”，另一方在多人游戏里连接

## 目录结构

```
src/main/java/com/thysummer/coopcraft/         通用逻辑（客户端和服务端都会加载）
src/client/java/com/thysummer/coopcraft/client/ 仅客户端逻辑（渲染、按键等）
src/main/resources/                            fabric.mod.json、图标、语言文件等
src/main/resources/assets/coopcraft/           方块模型、贴图、lang 文件
```

## 一起写代码

仓库公开，欢迎直接提 PR。详见 [CONTRIBUTING.md](CONTRIBUTING.md)。核心约定：不直接 push `main`，所有改动走分支 + PR，CI 通过后再合并。

## 许可证

[MIT](LICENSE)。可以自由使用、修改、分发，包括商用，保留版权声明即可。
