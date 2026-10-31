# CoopCraft 1.21.1 Fabric 整合包

这里存放与 CoopCraft 分开的第三方客户端模组。CoopCraft 本身从 `build/libs/` 构建，不在此重复保存。

## 当前模组

| 模组 | 版本 | 来源 | 许可证 |
| --- | --- | --- | --- |
| DangoMusic（CoopCraft 修订版） | 1.5.0-coopcraft.1+1.21.1-fabric | [上游仓库](https://github.com/Sorihiko/DangoMusic) · [官方 v1.5.0](https://github.com/Sorihiko/DangoMusic/releases/tag/v1.5.0) | GPL-3.0 |

官方原版 jar 的 SHA-256：

```text
e6467cede5dabbeb228de5144c53b6ae80a21b990f3b273ffb189a994c6fbe11
```

修订版 jar 的 SHA-256：

```text
adf084e16297b7dae2d954b6f51c86cbd2939172323a49233470c3d6f786b3bd
```

修订版基于官方 v1.5.0（提交 `da696d0871fa348323782d7d299ba10df46e07db`），将三个辉光着色器引用的缺失 `outline/base.vert` 改为已有的 `quad.vert`，接口保持为 `a_Pos`、`a_UV`。未修改播放器、账号、网络或音乐服务行为，也不表示上游作者为此修订版背书。

完整许可证见 `licenses/DangoMusic-GPL-3.0.txt`。修订版对应源码（含修改说明）在 [DangoMusic-1.5.0-coopcraft.1-src.zip](sources/DangoMusic-1.5.0-coopcraft.1-src.zip)，其 SHA-256 为 `17feb07663b168d87dc581c9ae4262af3142397d4d16339bfdea1463b2d2b681`。DangoMusic 与 CoopCraft 是独立模组，DangoMusic 的 GPL-3.0 不改变 CoopCraft 代码的 MIT 许可证；后续修改版仍须标明修改、采用 GPL-3.0 并提供对应源码。

## 安装到 PCL

1. 使用 Minecraft 1.21.1、Fabric Loader 0.20.1+，安装 Fabric API 0.116.17+1.21.1。
2. 把 `mods/` 中的 jar 与构建得到的 `coopcraft-0.1.0.jar` 复制到游戏实例的 `mods/` 目录。启用版本隔离时，使用 `.minecraft/versions/<实例名>/mods/`。
3. 启动游戏后按 `M` 打开 DangoMusic；网易云扫码登录按上游界面提示进行。

DangoMusic 只安装在客户端，不需要复制到专用服务器。其网易云接口、歌曲版权和会员限制见[上游说明](https://github.com/Sorihiko/DangoMusic#readme)。

登录凭证、缓存和设置位于实例的 `config/dangomusic/`。不要把这些文件提交到仓库或发给其他玩家。上游“一起听歌”功能还需要同一个 Firebase 项目；官方源码没有附带项目配置，未配置时该功能不可用。后续如自行配置，不要把密钥或私人数据库配置提交到仓库。
