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
| 纯 JVM 单元测试链路（JUnit4 + 真实 `org.json` 实现，4 个测试类 / 22 个用例） | `app/src/test/java/com/livetv/android/` |
| 格式与忽略规则（`.editorconfig`、`.gitignore` 排除构建产物/密钥/`.tmp/`） | 仓库根目录 |

### 功能

| 能力 | 证据 |
|---|---|
| Leanback 浏览页：标题、品牌色、Header 栏、Back 逐层返回 | `MainBrowseFragment.setupUi()` |
| 频道分类与卡片（新闻、体育两行），卡片显示标题 + 描述 | `MainBrowseFragment.loadRows()`、`ChannelCardPresenter.onBindViewHolder()` |
| 频道数据来自 APK 内 `assets/channels.json`（6 个 CCTV HLS 源），**UI 层不再硬编码** | `LocalAssetChannelSource`、`assets/channels.json` |
| JSON 解析与安全校验（http/https 白名单、必填校验、disabled 与空分类过滤） | `ChannelJsonParser`、`ChannelJsonParserTest` |
| 轻量分层：`domain/`（模型 + 仓库接口）与 `data/`（DTO / 解析器 / 数据源 / 仓库实现） | `app/src/main/java/com/livetv/android/domain/`、`data/` |
| 播放器分层：`player/` 纯策略层（状态机 / 错误分类 / 重试策略 / 频道导航）+ `PlayerManager` 桥接层 | `app/src/main/java/com/livetv/android/player/` |
| 播放状态机：Idle / Loading / Buffering / Playing / Paused / Error（含致命错误） | `PlaybackState`、`PlaybackReducer`、`PlaybackReducerTest` |
| 主备源自动重试与切换：每源最多重试 2 次（1500/3000ms 退避），耗尽后切备用源，全耗尽则致命错误 | `RetryPolicy`、`PlayerManager`、`RetryPolicyTest` |
| 自定义 HTTP 请求头走 DataSource 层（含 10s 连接/读取超时、允许跨协议重定向） | `PlayerManager.buildMediaSource()` |
| 遥控器切台：播放页上下键循环切台、OK 手动重试、Back 先关控制器 | `PlayerActivity.onKeyDown()`、`docs/TV-INTERACTION.md` §4.1 |
| 播放错误分类提示（网络 / HTTP 码 / 源解析 / 超时 / 未知）+ 操作指引，全部走 `strings.xml` | `PlaybackErrorMapper`、`strings.xml` 的 `player_*` |
| OK 键进入播放页；无播放源时 Toast 提示 | `MainBrowseFragment.setupEventListeners()` |
| HLS 直播流播放（Media3 ExoPlayer + `HlsMediaSource` + `DefaultHttpDataSource`） | `PlayerActivity.initializePlayer()` |
| 播放进度与 `playWhenReady` 跨生命周期保存 | `PlayerActivity.releasePlayer()` |
| 播放失败在页面显示错误文案 | `PlayerActivity` 的 `Player.Listener.onPlayerError` |
| 播放页屏幕常亮 | `PlayerActivity.onCreate` 的 `FLAG_KEEP_SCREEN_ON` |
| 领域模型已预留主备源 / 请求头 / 图标 / EPG 字段 | `domain/model/Channel.kt` |

### 明确**没有**的能力（避免误判）

- 频道源的导入 / 管理 / 用户自定义（配置只能改 `assets/channels.json` 后重新打包）
- M3U 导入、网络拉取频道列表、持久化存储
- EPG 节目单、搜索、收藏、播放历史、设置页、Menu 菜单
- 多码率 / 清晰度切换、倍速、播放进度记忆
- 账号体系、网络层、仪器测试（`androidTest`）与 UI 自动化
- **浏览页**的加载中 / 空态 / 错误态反馈（播放页已有状态提示；浏览页取不到内容时页面直接为空）
- 播放器控制器按钮里的"上一台/下一台"（切台只支持遥控器上下键）

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

## 阶段二（本次交付）：频道数据系统与 Repository 轻量分层

目标：把频道数据从 UI 层剥离出来，建立可扩展、可测试的 `data/` + `domain/` 轻量分层。

- [x] `domain/` 分层：`Channel`（迁移并扩展）/ `ChannelCategory` / `ChannelRepository` 接口
- [x] `data/` 分层：`ChannelConfigDto` / `ChannelJsonParser` / `ChannelDataSource` /
      `LocalAssetChannelSource` / `DefaultChannelRepository`
- [x] `assets/channels.json`：默认频道配置，阶段一 6 个硬编码频道**无损迁移**
- [x] UI 解耦：`MainBrowseFragment` 移除 `sampleNewsChannels()` / `sampleSportsChannels()`，
      改由 `ChannelRepository` 提供分类；`PlayerActivity` 的 Intent Extra 与播放链路保持不变
- [x] 领域模型前瞻字段：`backupUrls` / `headers` / `logo` / `epgId` / `enabled`
- [x] 安全容错：URL 仅允许 `http/https`（忽略大小写），非法频道与空分类被过滤，
      缺省字段回退默认值，异常 JSON 返回空列表且不崩溃
- [x] 纯 JVM 单测 22 个用例（解析器 11 / 领域模型 5 / 仓库 3 / 真实 assets 3），
      含 `org.json:json:20231013` 测试依赖
- [x] 同步更新 `docs/ARCHITECTURE.md`（包结构、数据流、Schema 与容错规则）

### 阶段二未做（留给后续阶段）

- M3U/M3U8 解析器、多数据源聚合、用户导入与持久化
- ~~主备源切换与自定义请求头的**接线**~~ **阶段三已完成**
- ~~加载中 / 空态 / 错误态的界面反馈~~ **播放页阶段三已完成**；浏览页仍缺（见技术债 #15）

---

## 阶段三（本次交付）：播放器状态机封装、错误恢复与遥控器切台

目标：把 `PlayerActivity` 里的 Media3 逻辑抽离成独立 `player/` 包，建立可测的状态机、
主备源自动重试与切换，并实现符合 TV 直觉的遥控器切台。

- [x] `player/` 纯策略层（**不依赖 `android.*` / `media3.*`**，由 `PlayerLayerPurityTest` 固化）：
      `PlaybackState` / `PlaybackEvent` / `PlaybackReducer` / `PlaybackError` /
      `PlaybackErrorMapper` / `RetryPolicy` / `ChannelNavigator`
- [x] `player/PlayerManager.kt` 桥接层：ExoPlayer **实例复用**（切台/切源/重试不重建）、
      请求头与超时走 `DataSource` 层、独立 `retryHandler`、Media3 事件翻译
- [x] 播放状态机：Idle / Loading / Buffering / Playing / Paused / Error(error, fatal)
- [x] 主备源 Failover：每源最多重试 2 次（1500ms / 3000ms 退避），耗尽切下一条源，
      全部耗尽 → 致命错误等待手动重置
- [x] 遥控器切台：播放页上下键循环切台、OK 手动重试、Back 先关控制器
- [x] `EXTRA_CHANNEL_ID` 新增 + 仓库定位完整频道（带 `backupUrls` / `headers`），旧 Extra 仍兼容
- [x] 播放页文案全部迁入 `strings.xml`（消除 Lint `SetTextI18n`）
- [x] 纯 JVM 单测 **77 个**（阶段二 22 + 阶段三新增 55），两次"先红后绿"红检已记录
- [x] 同步更新 `docs/ARCHITECTURE.md`（包结构、状态流转、Failover 机制）、
      `docs/TV-INTERACTION.md`（按键映射表）、`docs/TESTING.md`、`docs/ROADMAP.md`

### 阶段三未做（留给后续阶段）

- 播放进度记忆（切台后重播从头开始）
- 播放器控制器按钮里的"上一台/下一台"
- 浏览页的加载/空态反馈
- 真机验收（本机无 Android TV 设备，见 `docs/TESTING.md` §6）

---

## 阶段 3.5（本次交付）：体验优化收尾与 CI 验证

目标：清理阶段三遗留的两个技术债，并把前三阶段代码推到远端跑通 CI。

- [x] **技术债 #18**：切台提示 3 秒自动隐藏（`lifecycleScope.launch { delay(3000) }`，
      显示新提示前 `cancel()` 旧 Job，`onDestroy()` 兜底取消）
- [x] **技术债 #17**：HTTP 状态码改为**遍历 cause 链**取
      `HttpDataSource.InvalidResponseCodeException.responseCode`；
      Media3 的 `2000..2999` 错误码区间降级为兜底防线
- [x] `PlaybackErrorMapperTest` 追加 2 个边界用例（code 保留、code=0），
      用例总数 77 → **79**
- [x] 回归：`lintDebug testDebugUnitTest assembleDebug` 全绿（**0 Error**，79 个用例全过）
- [x] 推送到远端 `feat/livetv-phase3` 并触发 GitHub Actions

---

## P1：核心架构与数据系统

目标：让"频道从哪来"可配置、可解析、可测试。

| # | 能力 | 交付要点 |
|---|---|---|
| P1-1 | `ChannelRepository` 接口 + 内存实现 | **阶段二已完成基础版**（接口 + assets 实现）；后续补多源聚合 |
| P1-2 | 源解析器（m3u / m3u8 / 简单 XML） | JSON 解析器阶段二已完成；m3u 系列按同样"纯函数 + L1 单测"模式补齐 |
| P1-3 | 源管理与本地持久化 | 频道源增删改查；持久化方案**待定**（轻量方案优先，不预设 Room/数据库框架） |
| P1-4 | 状态流转（Loading / Content / Empty / Error） | **播放页阶段三已完成**（`PlaybackState` + `PlaybackReducer`）；浏览页仍需 sealed class + StateFlow |
| P1-5 | 网络层与错误分类 | **阶段三已完成播放错误分类**（Network / HttpStatus / SourceParsing / Timeout / Unknown）；浏览页的网络层仍待做 |
| P1-6 | 单元测试体系补齐 | **阶段二完成数据层、阶段三完成播放器层**；状态流转落地后补浏览页 L1 测试 |

> 约束：不引入 Hilt/Dagger 等 DI 框架；不拆多模块；依赖注入用手动构造。

## P1：播放器与交互

| # | 能力 | 交付要点 |
|---|---|---|
| P1-7 | 播放重试与线路切换 | **阶段三已完成**：每源 2 次重试 + 主备源自动切换 + 手动重置 |
| P1-8 | 多码率 / 清晰度 | 使用 Media3 的轨道选择能力 |
| P1-9 | 沉浸式全屏现代化 | `systemUiVisibility` → `WindowInsetsController`（API 30+ 正确行为） |
| P1-10 | 错误文案本地化 | **阶段三已完成**（播放页文案全部移入 `strings.xml`） |
| P1-11 | 焦点与遥控器细节完善 | **阶段三完成播放页上下键切台**；浏览页焦点兜底、刷新后焦点恢复、Menu 键入口仍待做 |
| P1-12 | 播放页主题统一 | `PlayerActivity` 与主界面共用 Leanback 主题/品牌色 |

## P2：扩展能力

| # | 能力 | 备注 |
|---|---|---|
| P2-1 | EPG 节目单 | 依赖外部 EPG 源格式与时区处理 |
| P2-2 | 搜索（频道名 / 拼音） | Leanback `SearchSupportFragment` |
| P2-3 | 收藏与播放历史 | 需持久化方案落地 |
| P2-4 | 设置页（默认线路、播放偏好） | |
| P2-5 | 频道 Logo 与分类细化 | `Channel.logo` **字段已在阶段二预留**，待 Presenter 绑定图片 |
| P2-6 | 多语言 | 现有资源仅中文 |
| P2-7 | 仪器测试 / UI 自动化 | 需引入 `androidx.test` 依赖（属新增依赖，需单独论证） |
| P2-8 | Release 流程 | 签名配置、混淆规则、产物归档；与 CI 的 PR 检查**分离** |
