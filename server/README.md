# CoopCraft 专用服务器

这个目录是给 Mac mini 用的开服包：Fabric 1.21.1 + CoopCraft，面向 2–5 人私密联机。

推荐用 **Tailscale** 组网，不开公网端口、不改路由器；朋友用 Tailscale 分配到的 `100.x.x.x` 直连。公网端口转发在文末作为备选。

## 需要准备

- Mac mini（Apple Silicon 或 Intel 都可以），macOS
- JDK 21
- CoopCraft 的 jar（在开发机上构建，或直接在 Mac mini 上克隆仓库构建）
- 朋友那边也要装 Fabric 1.21.1、Fabric API 和同版本的 CoopCraft

## 1. 安装 JDK 21

```bash
brew install openjdk@21
```

`setup.sh` 和 `start.sh` 会自动寻找 Homebrew 安装的 JDK 21，不需要改 shell 配置。想给 IDEA 或其他工具用，可以再做一个系统链接：

```bash
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```

Intel Mac 的 Homebrew 路径是 `/usr/local/opt/openjdk@21/...`。

## 2. 安装服务端

在开发机上先构建（需要 JDK 25，这是 Gradle/Loom 的要求；服务端运行只需要 JDK 21）：

```bash
./gradlew build
```

把 `build/libs/coopcraft-0.1.0.jar` 传到 Mac mini，然后：

```bash
cd server
./setup.sh /path/to/coopcraft-0.1.0.jar
```

如果 `server/` 就在仓库里、`build/libs` 也有 jar，可以直接 `./setup.sh`，脚本会自动找。

脚本会做这些事：

- 下载 Fabric Installer，安装 Fabric 服务端 1.21.1 + Loader 0.20.1（会下载 Minecraft 服务端，第一次较慢）
- 下载 Fabric API `0.116.17+1.21.1` 到 `mods/`
- 复制 CoopCraft jar 到 `mods/`
- 从 `server.properties.example` 生成 `server.properties`
- 生成一个 `eula=false` 的 `eula.txt`

服务端运行时的 `server.jar`、`libraries/`、`world/`、`logs/`、`mods/*.jar` 都在 `server/.gitignore` 里，不会进仓库。

## 3. 同意 EULA 并启动

Minecraft 服务端必须由你本人同意 EULA：

```bash
# 编辑 eula.txt，把 eula=false 改成 eula=true
# 或者：
sed -i '' 's/^eula=false/eula=true/' eula.txt
```

前台启动：

```bash
./start.sh
```

推荐用 screen，让服务端在退出终端后继续运行：

```bash
screen -S coopcraft ./start.sh
```

- 暂时离开 screen：按 `Ctrl+A`，再按 `D`
- 回到控制台：`screen -r coopcraft`
- 停止服务端：在控制台输入 `stop`，等它保存完再退出；不要在控制台直接 `Ctrl+C`

第一次启动会生成世界，看到 `Done (...)!` 就算启动成功。日志在 `logs/latest.log`。

内存默认 2GB，2–5 人够用；机器内存富裕可以：

```bash
MEMORY=4G ./start.sh
```

## 4. 白名单和管理员

`server.properties` 默认 `white-list=true`。在服务端控制台执行：

```text
op <你的游戏ID>
whitelist add <朋友的游戏ID>
whitelist reload
```

名字区分大小写，并且要和正版账号一致（`online-mode=true`）。删人用 `whitelist remove <名字>`。

## 5. Tailscale 联机（推荐）

你们不在同一局域网，也不需要公网 IP。Tailscale 会建一条加密的点对点连接；打不通时自动走中继。

1. Mac mini 安装 [Tailscale](https://tailscale.com/download/mac) 并登录。
2. 查看 mini 的地址：

   ```bash
   tailscale ip -4
   # 例如 100.101.102.103
   ```

3. 把 mini 这台机器分享给朋友：Tailscale 管理后台 → **Machines** → 找到 mini → **Share**，把链接发给朋友；朋友用自己的 Tailscale 账号接受。
   - 如果后台没有 Share 选项，也可以让朋友登录同一个 tailnet，但这样他会看到你 tailnet 里的其他设备。
4. 朋友安装 Tailscale、登录并接受分享。
5. 朋友在 Minecraft 里：**多人游戏 → 直接连接**，输入：

   ```text
   100.101.102.103:25565
   ```

6. 联机质量检查：

   ```bash
   tailscale ping <朋友设备名或IP>
   ```

   显示 `via DERP` 说明走了中继，延迟会高一些；显示直连则最好。如果中继长期很慢，再考虑 ZeroTier 或一台小 VPS + frp/WireGuard。

不要做 25565 的公网端口转发，Tailscale 已经够用。macOS 第一次可能会弹防火墙提示，允许 Java 接收入站连接。

## 6. 让 Mac mini 不休眠

Mac mini 是台机，但 macOS 仍可能自动休眠。三种办法任选：

- 用 launchd 示例（见下一节），它用 `/usr/bin/caffeinate -s` 包住服务端，服务在跑时不会睡
- 手动启动时用 `caffeinate -s ./start.sh` 或 `caffeinate -s screen -S coopcraft ./start.sh`
- 系统设置 → 能源 → 关闭“显示器关闭时自动休眠/进入睡眠”；检查 `pmset -g` 里的 `sleep` 值

能接网线就接网线，Wi-Fi 长时间开服不如有线稳。

## 7. 开机自启（可选）

`com.coopcraft.server.plist.example` 提供了一份 launchd 配置，用 `caffeinate -s` 启动服务端，崩溃后自动拉起。

1. 复制并编辑路径和 JAVA_HOME：

   ```bash
   cp com.coopcraft.server.plist.example ~/Library/LaunchAgents/com.coopcraft.server.plist
   ```

   把 `/Users/YOURNAME/coopcraft/server` 换成真实路径。Apple Silicon 的 Homebrew JDK 路径是 `/opt/homebrew/opt/openjdk@21/...`，Intel 是 `/usr/local/opt/openjdk@21/...`。

2. 加载：

   ```bash
   launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.coopcraft.server.plist
   launchctl print gui/$(id -u)/com.coopcraft.server | head
   ```

3. 查看日志：

   ```bash
   tail -f logs/launchd.out.log
   ```

4. 停止并取消自启：

   ```bash
   launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/com.coopcraft.server.plist
   ```

因为 `KeepAlive` 会重启，用 `stop` 命令停服后 launchd 也会再拉起来；要彻底停就用上面的 `bootout`。不要同时用 screen 和 launchd 启动同一个服务端。

## 8. 备份

`backup.sh` 会把世界目录打包到 `backups/`：

```bash
./backup.sh
```

如果服务端是用 `screen -S coopcraft` 启动的，脚本会先暂停存档、`save-all flush`，打包后再恢复存档。如果服务端是 launchd 拉起的，最稳妥是 `bootout` 停服后备份，再重新加载。

每天自动备份可以加到 crontab：

```cron
0 4 * * * /Users/YOURNAME/coopcraft/server/backup.sh >> /Users/YOURNAME/coopcraft/server/logs/backup.log 2>&1
```

备份最好再定期拷一份到别的磁盘或 iCloud，别只放在 mini 上。升级模组或服务端前先备份。

## 9. 客户端配置

- Minecraft 1.21.1
- Fabric Loader 0.20.1+
- Fabric API `0.116.17+1.21.1`
- CoopCraft `0.1.0`

客户端把 CoopCraft 和 Fabric API 放进 `.minecraft/mods`；服务端放在 `server/mods`。两边 CoopCraft 版本要一致，否则 Fabric 可能拒绝连接。客户端可以额外装 Sodium 之类的客户端优化模组，但不要放到服务端。

## 10. 常见问题

**启动后马上退出，提示 EULA**  
把 `eula.txt` 里的 `eula=false` 改成 `eula=true`。

**`Address already in use`**  
25565 被别的进程占了：

```bash
lsof -nP -iTCP:25565 -sTCP:LISTEN
```

**朋友连接超时**  
依次检查：服务端在跑（`logs/latest.log` 有 `Done`）、朋友 Tailscale 在线、`tailscale ping` 能通、IP 和端口没写错、macOS 防火墙允许 Java。不要在朋友那边填 `localhost`。

**`Incompatible mod set`**  
两边 Fabric API / CoopCraft 版本不一致，或者服务端缺少 Fabric API。看 `logs/latest.log` 里的 mod 列表。

**`op` / `whitelist` 提示未知命令**  
这两条要在服务端的控制台里执行；游戏内执行需要你先有 OP 权限。

**服务器过一会儿就断**  
大概率是 Mac mini 休眠了，检查 `pmset -g`，用 `caffeinate` 或能源设置关掉自动休眠。
