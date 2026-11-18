# ROADMAP — 能力现状与后续规划

> **本文件严格区分「已实现」与「规划」。**
> 「已实现」栏的每一条都能在源码中指出位置；「规划」栏**尚未实现**，
> 任何文档、注释或宣传都不得把规划写成现状。

## 阶段〇：已实现能力（当前仓库真实状态）

### 构建与工程

| 能力 | 证据 |
|---|---|
| 可构建的 Android TV 应用（单 `app` 模块，AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.7 / JDK 17） | `build.gradle.kts`、`gradle/wrapper/gradle-wrapper.properties` |
| 依赖走阿里云镜像 + `gradlePluginPortal` + `mavenCentral` | `settings.gradle.kts` |
| ViewBinding | `app/build.gradle.kts` `buildFeatures { viewBinding = true }` |
| TV 声明齐全：`leanback` required、`touchscreen` required=false、`banner`、`LEANBACK_LAUNCHER` | `app/src/main/AndroidManifest.xml` |
| GitHub Actions CI：`push`/`pull_request` 触发，跑编译 + Lint + 单元测试 + 打包 Debug APK | `.github/workflows/android-ci.yml` |
| 纯 JVM 单元测试链路（JUnit4，`Channel` 模型冒烟测试） | `app/src/test/java/com/livetv/android/model/ChannelTest.kt` |
| 格式与忽略规则（`.editorconfig`、`.gitignore` 排除构建产物/密钥/`.tmp/`） | 仓库根目录 |

### 功能

| 能力 | 证据 |
|---|---|
| Leanback 浏览页：标题、品牌色、Header 栏、Back 逐层返回 | `MainBrowseFragment.setupUi()` |
| 两行频道卡片（新闻、体育），卡片显示标题 + 描述 | `MainBrowseFragment.loadRows()`、`ChannelCardPresenter.onBindViewHolder()` |
| OK 键进入播放页；无播放源时 Toast 提示 | `MainBrowseFragment.setupEventListeners()` |
| HLS 直播流播放（Media3 ExoPlayer + `HlsMediaSource` + `DefaultHttpDataSource`） | `PlayerActivity.initializePlayer()` |
| 播放进度与 `playWhenReady` 跨生命周期保存 | `PlayerActivity.releasePlayer()` |
| 播放失败在页面显示错误文案 | `PlayerActivity` 的 `Player.Listener.onPlayerError` |
| 播放页屏幕常亮 | `PlayerActivity.onCreate` 的 `FLAG_KEEP_SCREEN_ON` |

### 明确**没有**的能力（避免误判）

- 频道源配置 / 导入 / 管理（数据硬编码在 `MainBrowseFragment`）
- EPG 节目单、搜索、收藏、播放历史、设置页、Menu 菜单
- 多码率 / 清晰度切换、播放重试、线路切换
- 账号体系、持久化存储、网络层与解析器
- 仪器测试（`androidTest`）与 UI 自动化

---

## 阶段一（本次交付）：工程基线

目标：把原型变成 Agent 可长期、安全、可验证维护的工程。

- [x] `AGENTS.md`（渐进式披露，含行为准则与构建命令规范）
- [x] `README.md`（定位、技术栈、环境、构建与运行）
- [x] `docs/ARCHITECTURE.md`（真实结构、数据流、技术债、演进方向）
- [x] `docs/DEVELOPMENT.md`（环境、构建/Lint/测试、ADB 日志规范）
- [x] `docs/TESTING.md`（测试分层与边界）
- [x] `docs/TV-INTERACTION.md`（遥控器、焦点、Presenter 规范）
- [x] `docs/ROADMAP.md`（本文件）
- [x] `.editorconfig`、`.gitignore`（排除构建产物、密钥、`.tmp/`）
- [x] `.github/workflows/android-ci.yml`（push/PR：编译 → Lint → 单测 → 打包）
- [x] 播放器生命周期修正为 `onStart()` 初始化 / `onStop()` 释放，并断开 `PlayerView` 绑定
- [x] 屏幕常亮确认（已有 `FLAG_KEEP_SCREEN_ON`，未改动）
- [x] Manifest TV 规范确认（leanback / touchscreen required=false，已合规）
- [x] `Channel` 纯 JVM 单元测试（打通 `testDebugUnitTest` 链路）

---

## P1：核心架构与数据系统

目标：让"频道从哪来"可配置、可解析、可测试。

| # | 能力 | 交付要点 |
|---|---|---|
| P1-1 | `ChannelRepository` 接口 + 内存实现 | 先抽接口，UI 行为不变；仓库层可用 L1 单测覆盖 |
| P1-2 | 源解析器（m3u / m3u8 / 简单 XML） | 纯函数 `String -> List<Channel>`，逐个输入输出写单测 |
| P1-3 | 源管理与本地持久化 | 频道源增删改查；持久化方案**待定**（轻量方案优先，不预设 Room/数据库框架） |
| P1-4 | 状态流转（Loading / Content / Empty / Error） | 用 sealed class + StateFlow 表达，UI 订阅随生命周期启停 |
| P1-5 | 网络层与错误分类 | 区分网络失败/解析失败/源无效，给出可行动提示 |
| P1-6 | 单元测试体系补齐 | 解析器、Repository、状态流转全覆盖 L1 |

> 约束：不引入 Hilt/Dagger 等 DI 框架；不拆多模块；依赖注入用手动构造。

## P1：播放器与交互

| # | 能力 | 交付要点 |
|---|---|---|
| P1-7 | 播放重试与线路切换 | 失败自动重试 N 次；多线路可切换 |
| P1-8 | 多码率 / 清晰度 | 使用 Media3 的轨道选择能力 |
| P1-9 | 沉浸式全屏现代化 | `systemUiVisibility` → `WindowInsetsController`（API 30+ 正确行为） |
| P1-10 | 错误文案本地化 | 播放错误等文案移入 `strings.xml` |
| P1-11 | 焦点与遥控器细节完善 | 焦点丢失兜底、刷新后焦点恢复、Menu 键入口 |
| P1-12 | 播放页主题统一 | `PlayerActivity` 与主界面共用 Leanback 主题/品牌色 |

## P2：扩展能力

| # | 能力 | 备注 |
|---|---|---|
| P2-1 | EPG 节目单 | 依赖外部 EPG 源格式与时区处理 |
| P2-2 | 搜索（频道名 / 拼音） | Leanback `SearchSupportFragment` |
| P2-3 | 收藏与播放历史 | 需持久化方案落地 |
| P2-4 | 设置页（默认线路、播放偏好） | |
| P2-5 | 频道 Logo 与分类细化 | `Channel` 增加 logo 字段，Presenter 绑定图片 |
| P2-6 | 多语言 | 现有资源仅中文 |
| P2-7 | 仪器测试 / UI 自动化 | 需引入 `androidx.test` 依赖（属新增依赖，需单独论证） |
| P2-8 | Release 流程 | 签名配置、混淆规则、产物归档；与 CI 的 PR 检查**分离** |
