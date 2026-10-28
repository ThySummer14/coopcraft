# 协作指南

## 开始之前

- 装好 JDK 25（跑 Gradle 用；模组编译目标是 Java 21）
- 直接 `git clone https://github.com/ThySummer14/coopcraft.git`；想向仓库直接推分支需要协作者权限，没有的话 fork 后提 PR
- 在 IDEA 里用 “Open” 打开 `build.gradle`，等 Gradle 同步完成

## 分支与 PR

1. 从最新的 `main` 开分支：`git switch -c feat/描述`（修复用 `fix/`，杂项用 `chore/`）
2. 一个 PR 只做一件事，改动尽量小
3. 提交前本地跑 `./gradlew build runGameTest`
4. push 分支后在 GitHub 开 PR 到 `main`，填好 PR 模板
5. CI 绿 + 至少 1 人看过再合并

不要直接 push `main`，也不要 force push 别人正在用的分支。

## 代码约定

- modid 固定为 `coopcraft`；注册 id 一律用 `CoopCraft.id("path")`
- 主逻辑放 `src/main`，只在客户端跑的代码（渲染、按键、HUD）放 `src/client`
- 资源放 `assets/coopcraft/`，语言文件用 `lang/zh_cn.json` 和 `lang/en_us.json`
- 用官方 Mojang 映射，类名是 `ResourceLocation`、`ItemStack` 这种；网上 Yarn 教程的类名和参数顺序可能对不上，照抄前先核对
- 新增依赖前先在 PR 里说明理由

## 不要提交

- `run/`、`build/`、`.gradle/`（已在 .gitignore 里）
- 本地日志、崩溃报告、导出的 jar
- 任何密钥、token、服务器地址
