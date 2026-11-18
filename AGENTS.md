# AGENTS.md — LiveTV Agent 工作准则

> 作用域：整个仓库。本文件是**路由**，不是规则仓库——只放每次都要用的高频规则。
> 细节按需读 `docs/` 分片；改代码前必须先读源码。

## 0. 项目事实基线（截至阶段 5.5）

Android TV 直播客户端原型。Kotlin + Leanback 1.0.0 + Media3 1.4.1（ExoPlayer/HLS），
单 `app` 模块，无 DI 框架、无多模块拆分。**当前只有 2 个 Activity、1 个 Fragment、1 个 Presenter**。
频道数据来自 `assets/channels.json`（JSON 源）与 `assets/sample.m3u`（M3U 源），
经 `data/` + `domain/` 轻量分层在仓库层**按分类名合并**（**UI 层不再硬编码**）。
**全局仓库由 `LiveTvApplication` 持有（带内存缓存），UI 层不得自行拼装数据源。**
播放器逻辑在 `player/` 包：纯策略层（状态机 / 重试 / 切台导航，**不依赖 android.\* / media3.\***）
+ `PlayerManager` 桥接层。
**播放页的按键拦截在 `PlayerActivity.dispatchKeyEvent`（不是 `onKeyDown`）**——Media3 会抢先吞掉方向键。
任何"已支持/已实现"的描述都必须以源码为准，不得把路线图内容写成现状。

> **真机回归要求**：改动 `PlayerActivity` 的按键处理、控制器可见性或状态文案顺序后，
> 纯 JVM 单测在结构上无法发现问题（阶段 5.5 的 D1–D4 全是这类），必须做一次真机实测。

## 0.1 分层红线（新增文件前必读）

| 目录 | 允许依赖 | 禁止 |
|---|---|---|
| `player/`（除 `PlayerManager.kt`） | 纯 Kotlin | `android.*`、`androidx.media3.*`（由 `PlayerLayerPurityTest` 强制） |
| `player/PlayerManager.kt` | Android + Media3 | 把重试/切台逻辑写进这里（应放 `RetryPolicy` / `ChannelNavigator`） |
| `data/` | `domain/` + 纯 Kotlin | 直接依赖 UI 层 |
| `domain/` | 纯 Kotlin | 依赖 `data/` 或 `android.*` |
| `ui/` | `domain/`（+ 必要的 `data/` 实现类） | 在 UI 层硬编码业务数据；**自行拼装 `ChannelDataSource`**（应取 `LiveTvApplication.channelRepository`） |

## 1. 核心行为准则（强制）

| # | 规则 | 判定 |
|---|---|---|
| 1 | **先读后改**：改任何文件前先读完整源码 + 相关 `docs/` 分片；不凭猜测写文档或代码 | diff 中不得出现未读过的文件 |
| 2 | **文档 100% 基于事实**：`docs/` 描述现状时必须能在源码里指到具体文件；未实现的能力只能写进 `docs/ROADMAP.md` 的规划区 | 抽查每条"已实现"断言 |
| 3 | **禁止擅自引入新依赖**：新增依赖需在交付说明里写明**为什么必须加**、**替代方案为何不可行**；禁止 Hilt/Dagger/Room 等重型框架 | `app/build.gradle.kts` diff |
| 4 | **禁止删功能凑编译**：不得删除、注释或绕过既有功能/代码来让构建通过；构建失败先怀疑自己的改动 | 删除类改动必须为零 |
| 5 | **禁止硬编码新业务数据**：频道/EPG/URL 等业务数据不得写进源码或布局；频道内容一律走 `assets/`（`channels.json` / `sample.m3u`） | `grep` 复核新增字面量 |
| 6 | **禁止越界扩展**：不新增 UI 页面/弹窗、不换 Leanback/Media3、不引入 DI 框架；改动范围不得超出任务书 | 交付范围与任务书一致 |
| 7 | **最小修改**：只改任务明确点名的隐患；不顺手"优化"无关代码 | 每处改动可对应到任务条目 |
| 8 | **不覆盖用户改动**：动手前 `git status`，工作区脏时先确认，不得 `git checkout/reset` 他人未提交内容 | 改前 `git status --short` |

## 2. 命令行构建规范（强制）

所有 Gradle 命令**必须**附加 `--console=plain --no-daemon`（进度条会刷屏污染上下文）：

```bash
./gradlew testDebugUnitTest --console=plain --no-daemon
./gradlew lintDebug --console=plain --no-daemon
./gradlew assembleDebug --console=plain --no-daemon
./gradlew lintDebug testDebugUnitTest assembleDebug --console=plain --no-daemon   # 交付前全量
```

- 禁止 `gradle` 全局命令、禁止 `--daemon`（每次构建都要干净上下文）。
- 优先跑**受影响的那一个**任务，别默认全量；全量只在交付前跑。
- 纯 JVM 单元测试不需要模拟器；`connectedAndroidTest` 依赖设备，未接设备时不要承诺它能跑。
- 构建日志只看失败段与任务汇总，长输出用 `grep -E "FAILURE|error:|warning:"` 过滤。

## 3. `docs/` 索引（按任务类型读取）

| 你的任务 | 先读 |
|---|---|
| 改 UI / 焦点 / 遥控器交互 | `docs/TV-INTERACTION.md` |
| 改架构、加数据层或仓库 | `docs/ARCHITECTURE.md` |
| 改播放器逻辑（状态机 / 重试 / 切源） | `docs/ARCHITECTURE.md` §3.1–3.2 + `player/` 纯策略层源码 |
| 装环境、跑构建、打日志 | `docs/DEVELOPMENT.md` |
| 写测试、评估改动风险 | `docs/TESTING.md` |
| 排期、评估新功能可行性 | `docs/ROADMAP.md` |

## 4. 交付前检查清单

1. `git status --short` 干净（只含本次改动）。
2. `./gradlew lintDebug testDebugUnitTest assembleDebug --console=plain --no-daemon` 全绿。
3. 新增测试先在旧实现上跑红、再在新实现上跑绿（见 `docs/TESTING.md`）。
4. 文档与代码同步：改了行为就改对应 `docs/` 分片，不把细节写回本文件。
5. 交付说明包含：改动文件清单、命令与结果、已知遗留问题。
