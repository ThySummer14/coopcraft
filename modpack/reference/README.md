# 参考整合包（借鉴用）

这里存的是 GD656 系列整合包的**模组清单**（`modrinth.index.json`），用来参考别人的模组选择、版本搭配和配置思路，不是可以直接装的整合包。

## 清单

| 前缀 | 整合包 | Minecraft | Loader | 文件数 | 特点 |
| --- | --- | --- | --- | --- | --- |
| `youhua` | GD656极致优化 | 1.18.2 / 1.19.2 / 1.20.1 / 1.20.2 / 1.21.4 / 1.21.5 | Fabric | 37–61 | 纯客户端性能优化 + 基础 QoL，和我们的定位最接近 |
| `shengdian` | GD656极致生电 | 1.20.1 / 1.21.4 | Fabric | 133 | Carpet 全家桶、投影/Litematica、MiniHUD、ReplayMod，技术生存向 |
| `meihua` | GD656极致美化 | 1.20.1 | Fabric | 176–182 | 光影、材质、动画、界面美化（Iris + 大量资源包） |
| `yanglao` | GD656极致养老 | 1.20.1 | **Forge 47.4.0** | 313 | 大型整合（Create、天境、暮色、地形生成），内容向 |

文件名格式：`<前缀>--<原包文件名>.json`。

## 借鉴结论

- 1.21.1 没有任何一版 GD656，我们的客户端包是**按 1.21.1 重新解析版本**的，没有直接搬清单。
- 抄得动的：Sodium / Lithium / ModernFix / FerriteCore / ImmediatelyFast / EntityCulling / MoreCulling / EBE / Continuity / Krypton / BadOptimizations / Dynamic FPS / Debugify 这一套优化组合，版本搭配在 1.21.4 和 1.21.5 版里验证过。
- 没抄的：Carpet 技术向工具（投影、MiniHUD、ReplayMod）——我们的服是休闲联机，装上去容易和 CoopCraft 的交互打架；Forge 养老包的模组全部用不上。
- 资源包/光影没打包，留成可选，避免把整合包做到几百 MB。

## 为什么不存原始压缩包

原始包体积：优化 215 MB、生电 476 MB、美化 570 MB、养老 967 MB，合计约 2.2 GB；GitHub 单文件上限 100 MB、仓库建议 1 GB 以内，直接提交会把仓库撑爆。

需要原始包时从 GD656 的发布渠道（Modrinth / 123 云盘 / QQ 群）下载，用 PCL 或 Modrinth App 导入 `.mrpack` 即可。本地留档在 `~/工作/会话/` 之外，不进仓库。

## 许可证

清单里的每个模组都带原始下载 URL 和 sha1/sha512，各自的许可证以模组页面为准（Sodium 是 Polyform Shield、Jade 是 CC-BY-NC-SA、Xaero 是 All-Rights-Reserved 等）。**借鉴清单不等于可以直接再分发这些 jar**：真正要打进我们整合包的模组，按 `modpack/README.md` 的规则逐个登记许可证。
