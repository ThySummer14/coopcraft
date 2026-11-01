# CoopCraft 1.21.1 Fabric 整合包

与 CoopCraft 分开的第三方客户端模组、整合包产物和参考清单。CoopCraft 本体从 `build/libs/` 构建，不在 `mods/` 里重复保存。

## 目录

| 路径 | 内容 |
| --- | --- |
| `dist/` | 可直接导入启动器的 `.mrpack` 整合包 |
| `mods/` | 需要手动放进 `mods/` 的第三方 jar（如 DangoMusic） |
| `sources/` | 修订版 jar 对应的源码 |
| `licenses/` | 许可证原文 |
| `reference/` | 借鉴用的参考整合包清单（GD656 系列） |
| `PACK-MODS.md` | 整合包内的模组清单（版本、许可证、Modrinth 链接） |

## 客户端整合包

`dist/CoopCraft-1.21.1-Client-0.1.0.mrpack`：48 个第三方模组 + CoopCraft 本体（在 `overrides/mods/` 里），45.7 MiB 下载量，346 KiB 的包文件。

内容分三类：性能优化（Sodium、Lithium、ModernFix、FerriteCore、ImmediatelyFast、EntityCulling、Krypton、BadOptimizations 等）、界面提示（Jade、AppleSkin、Xaero 地图、Chat Heads、BetterF3）、实用功能（REI、LambdaDynamicLights、3D Skin Layers、I18NUpdateMod）。完整清单见 [PACK-MODS.md](PACK-MODS.md)。

### 安装到 PCL

1. PCL 首页 →「版本管理」→ 左下「导入整合包」，选 `dist/CoopCraft-1.21.1-Client-0.1.0.mrpack`。
2. PCL 会自动创建 1.21.1 + Fabric Loader 0.20.1 实例并下载全部模组。
3. 启动即可进服；服务端地址由服主单独提供。

不想用整合包的话，把 `mods/` 里的 jar 和构建出来的 `coopcraft-0.1.0.jar` 手动复制进实例的 `mods/` 也行（启用版本隔离时是 `.minecraft/versions/<实例名>/mods/`）。

## 服务端建议加的模组

专用服务器只要 `coopcraft-0.1.0.jar` + `fabric-api-0.116.17+1.21.1.jar` 就能跑，想省资源再加这些（不进客户端整合包）：

| 模组 | 用途 |
| --- | --- |
| Lithium、FerriteCore、ModernFix | 实体/内存/启动优化，纯服务端收益，兼容性好 |
| Krypton | 网络栈优化，多人同时在线时明显 |
| Noisium、Chunky | 地形生成与区块预生成 |
| c2me | 区块加载并行，目前是 alpha 版，出问题先删它 |
| spark | 卡顿采样，`/spark profiler start` 定位问题 |

## 手动模组：DangoMusic

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

装法：把 `mods/DangoMusic-*.jar` 放进实例 `mods/`，启动后按 `M` 打开，网易云扫码登录按上游界面提示进行。DangoMusic 只装客户端，不需要复制到专用服务器。

登录凭证、缓存和设置位于实例的 `config/dangomusic/`。不要把这些文件提交到仓库或发给其他玩家。上游“一起听歌”功能还需要同一个 Firebase 项目；官方源码没有附带项目配置，未配置时该功能不可用。后续如自行配置，不要把密钥或私人数据库配置提交到仓库。

## 更新整合包

改动模组清单后重新生成：

```sh
python3 tools/pack/resolve.py       # 解析 1.21.1 + Fabric 的可用版本，写 api/ 缓存与 pack.json
python3 tools/pack/make_mrpack.py   # 重新打包 dist/*.mrpack
python3 tools/pack/gen_modlist.py   # 刷新 PACK-MODS.md
```

脚本默认把缓存和临时文件放在仓库外的 `packbuild/`，需要时用 `--workdir` 指到别处。

## 参考整合包

GD656 系列（优化 / 生电 / 美化 / 养老）的模组清单在 [reference/](reference/README.md)，只做借鉴，不进整合包。
