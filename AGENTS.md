# AGENTS.md — LiveTV Agent 工作准则

> 作用域：整个仓库。本文件是**路由**，不是规则仓库——只放每次都要用的高频规则与不可默认的地基。
> 事实细节（版本、包结构、数据流、技术债）一律以 `docs/ARCHITECTURE.md` 与源码为准。

## 0. 项目地基（动手前必知）

Android TV 直播客户端原型，**单 `app` 模块、无 DI 框架、不拆多模块**。最容易踩的隐性约定：

- 频道数据**只来自 MGTV 公开目录**（`data/mgtv/`），由 `MgtvProvider` 映射为 `mgtv:<cameraId>`；
  全局快照由 `LiveTvApplication.catalogStore` 持有 → `docs/ARCHITECTURE.md` §2–§3
- 播放页按键拦截在 `PlayerActivity.dispatchKeyEvent`（**不是** `onKeyDown`）——Media3 会抢先吞掉方向键 → `docs/TV-INTERACTION.md` §4.1
- 入口按设备形态分流：`MainActivity` 判定 TV 后走 Leanback、触控设备走 `ui/mobile/`；**播放页两端共用** → `docs/ARCHITECTURE.md` §7

> **真机回归要求**：改动播放页的按键处理、控制器可见性或状态文案顺序后，纯 JVM 单测在结构上
> 无法发现问题（阶段 5.5 的 D1–D4 全是这类），必须做一次真机实测。
> 改 `MainActivity` 分流或 `ui/mobile/` 布局后，也要真机验证（阶段六只验证了手机分支）。

## 1. 分层红线（新增文件前必读）

| 目录 | 允许依赖 | 禁止 |
|---|---|---|
| `player/`（除 `PlayerManager.kt`） | 纯 Kotlin | `android.*`、`androidx.media3.*`（由 `PlayerLayerPurityTest` 强制） |
| `player/PlayerManager.kt` | Android + Media3 | 把重试/切台逻辑写进这里（应放 `RetryPolicy` / `ChannelNavigator`） |
| `data/` | `domain/` + 纯 Kotlin | 直接依赖 UI 层 |
| `domain/` | 纯 Kotlin | 依赖 `data/` 或 `android.*` |
| `ui/` | `domain/` + 必要的 `data/` 实现类 | 硬编码业务数据；自行拼装 `ChannelDataSource` |
| `util/` | 纯 Kotlin（判定逻辑）+ 框架门面 | 在 `util/` 里塞业务逻辑 |
| `ui/mobile/`（除 `MobileChannelList.kt`） | `domain/` + Android View | **改动 `PlayerActivity` / `PlayerManager`**；引入图片加载库等重型依赖 |

## 2. 核心行为准则（强制）

| # | 规则 | 判定 |
|---|---|---|
| 1 | **先读后改**：改任何文件前先读完整源码 + 相关 `docs/` 分片；不凭猜测写文档或代码 | diff 中不得出现未读过的文件 |
| 2 | **文档 100% 基于事实**：`docs/` 描述现状时必须能在源码里指到具体文件；未实现的能力只能写进 `docs/ROADMAP.md` 的规划区 | 抽查每条"已实现"断言 |
| 3 | **禁止擅自引入新依赖**：新增依赖需在交付说明里写明**为什么必须加**、**替代方案为何不可行**；禁止 Hilt/Dagger/Room 等重型框架 | `app/build.gradle.kts` diff |
| 4 | **禁止删功能凑编译**：不得删除、注释或绕过既有功能/代码来让构建通过；构建失败先怀疑自己的改动 | 删除类改动必须为零 |
| 5 | **禁止硬编码新业务数据**：频道/EPG/URL 等业务数据不得写进源码或布局；频道内容一律来自 MGTV 公开目录（`data/mgtv/`） | `grep` 复核新增字面量 |
| 6 | **禁止越界扩展**：不新增 UI 页面/弹窗、不换 Leanback/Media3、不引入 DI 框架；改动范围不得超出任务书 | 交付范围与任务书一致 |
| 7 | **最小修改**：只改任务明确点名的隐患；不顺手"优化"无关代码 | 每处改动可对应到任务条目 |
| 8 | **不覆盖用户改动**：动手前 `git status`，工作区脏时先确认，不得 `git checkout/reset` 他人未提交内容 | 改前 `git status --short` |

## 3. 命令行构建规范（强制）

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

## 4. 按需阅读索引（读在动手之前）

| 你要做的事 | 先读 | 触发时机 |
|---|---|---|
| 改 UI / 焦点 / 遥控器交互 | `docs/TV-INTERACTION.md` | 动 `ui/` 或任何按键处理之前 |
| 改手机端（触控）UI 或设备分流 | `docs/ARCHITECTURE.md` §7 | 动 `ui/mobile/`、`util/DeviceUtils`、`MainActivity` 之前 |
| 改架构、加数据层 / 解析器 / 仓库 | `docs/ARCHITECTURE.md` | 新增包或改数据流之前 |
| 改播放器状态机 / 重试 / 切源 | `docs/ARCHITECTURE.md` §3.1–3.2 + `player/` 纯策略层源码 | 动 `player/` 之前 |
| 写测试、评估改动风险 | `docs/TESTING.md` | 写第一个用例之前 |
| 装环境、跑构建、抓日志 | `docs/DEVELOPMENT.md` | 构建失败或换机器时 |
| 排期、评估新功能可行性 | `docs/ROADMAP.md` | 回答"能不能做/做到哪了"之前 |

## 5. 交付前检查清单

1. `git status --short` 干净（只含本次改动）。
2. `./gradlew lintDebug testDebugUnitTest assembleDebug --console=plain --no-daemon` 全绿。
3. 新增测试先在旧实现上跑红、再在新实现上跑绿（见 `docs/TESTING.md`）。
4. 文档与代码同步：改了行为就改对应 `docs/` 分片，不把细节写回本文件。
5. 交付说明包含：改动文件清单、命令与结果、已知遗留问题。

## 6. 优先级

用户当前明确指令 > 本文件 > `docs/` 分片 > 源码里既成的写法。
冲突时按此顺序裁决；**安全红线（第 1、2 节）不因任务书宽松而失效**。
