# ROADMAP — 能力现状与后续规划

> **本文件严格区分「已实现」与「规划」。**
> 「已实现」栏的每一条都能在源码中指出位置；「规划」栏**尚未实现**，
> 任何文档、注释或宣传都不得把规划写成现状。

## 阶段〇：已实现能力（当前仓库真实状态）

> **2026-11-21 更新**：M3U 订阅、内置 assets 与三级兜底管线已移除，频道**只来自 MGTV 公开目录**；
> 下文中提到 `SubscriptionChannelRepository` / `RemoteM3uFetcher` / `assets/*` / 订阅源管理的条目均为**历史记录**。

### 构建与工程

| 能力 | 证据 |
|---|---|
| 可构建的 Android TV 应用（单 `app` 模块，AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.7 / JDK 17） | `build.gradle.kts`、`gradle/wrapper/gradle-wrapper.properties` |
| 依赖走阿里云镜像 + `gradlePluginPortal` + `mavenCentral` | `settings.gradle.kts` |
| ViewBinding | `app/build.gradle.kts` `buildFeatures { viewBinding = true }` |
| TV 声明齐全：`leanback` required=**false**（阶段六改为双端通用）、`touchscreen` required=false、`banner`、`LAUNCHER` + `LEANBACK_LAUNCHER` 双入口 | `app/src/main/AndroidManifest.xml` |
| GitHub Actions CI：`push`/`pull_request` 触发，跑编译 + Lint + 单元测试 + 打包 Debug APK | `.github/workflows/android-ci.yml` |
| 纯 JVM 单元测试链路（JUnit4 + 真实 `org.json` 实现，**31 个测试类 / 285 个用例**） | `app/src/test/java/com/livetv/android/` |
| 格式与忽略规则（`.editorconfig`、`.gitignore` 排除构建产物/密钥/`.tmp/`） | 仓库根目录 |

### 功能

| 能力 | 证据 |
|---|---|
| Leanback 浏览页：标题、品牌色、Header 栏、Back 逐层返回 | `MainBrowseFragment.setupUi()` |
| 频道分类与卡片：分类行由 MGTV 目录动态生成（卫视台 / 地方台 / 特色频道 / 热门-独有），卡片显示标题 + 描述 | `MainBrowseFragment.loadRows()`、`ChannelCardPresenter.onBindViewHolder()` |
| 频道数据**唯一来源**：MGTV 公开目录（`MgtvProvider` → `mgtv:<cameraId>`），全局快照见 `LiveTvApplication.catalogStore` | `data/mgtv/`、`domain/repository/ChannelCatalogStore.kt` |
| 远程 M3U 拉取（仅 http/https、5s 连接 / 8s 读取超时、2MB 响应上限、仅接受 200、UTF-8） | `data/remote/RemoteM3uFetcher.kt` |
| 订阅持久化：URL 存 `SharedPreferences`，M3U 原文存 `filesDir/remote_playlist.m3u`（临时文件 + rename 原子写） | `data/subscription/FileSubscriptionStore.kt` |
| 全局仓库单例 + 内存缓存（UI 层零重复装配） | `LiveTvApplication`、`SubscriptionChannelRepository`、`DefaultChannelRepository` 的 `enableCache`/`refresh()`/`invalidateCache()` |
| JSON 解析与安全校验（http/https 白名单、必填校验、disabled 与空分类过滤） | `ChannelJsonParser`、`ChannelJsonParserTest` |
| M3U 解析：`#EXTINF` 属性提取（`tvg-id` → `epgId`、`tvg-logo` → `logo`、`group-title` → 分类名），支持单/双/无引号、属性乱序、坏条目跳过 | `M3uPlaylistParser`、`M3uPlaylistParserTest` |
| M3U **请求头**：`#EXTVLCOPT:` / `#KODIPROP:` 与 `#EXTINF` 行内属性 -> `User-Agent`/`Referer`/`Origin`，直通播放器 DataSource 层 | `M3uPlaylistParser`、`PlayerManager` |
| 轻量分层：`domain/`（模型 + 仓库接口）与 `data/`（DTO / 解析器 / 数据源 / 仓库实现） | `app/src/main/java/com/livetv/android/domain/`、`data/` |
| 播放器分层：`player/` 纯策略层（状态机 / 错误分类 / 重试策略 / 频道导航）+ `PlayerManager` 桥接层 | `app/src/main/java/com/livetv/android/player/` |
| 播放状态机：Idle / Loading / Buffering / Playing / Paused / Error（含致命错误） | `PlaybackState`、`PlaybackReducer`、`PlaybackReducerTest` |
| 主备源自动重试与切换：每源最多重试 2 次（1500/3000ms 退避），耗尽后切备用源，全耗尽则致命错误 | `RetryPolicy`、`PlayerManager`、`RetryPolicyTest` |
| 自定义 HTTP 请求头走 DataSource 层（含 10s 连接/读取超时、允许跨协议重定向） | `PlayerManager.buildMediaSource()` |
| 遥控器切台：播放页上下键循环切台、OK 手动重试、Back 先关控制器 | `PlayerActivity.dispatchKeyEvent()`、`docs/TV-INTERACTION.md` §4.1 |
| 播放错误分类提示（网络 / HTTP 码 / 源解析 / 超时 / 未知）+ 操作指引，全部走 `strings.xml` | `PlaybackErrorMapper`、`strings.xml` 的 `player_*` |
| OK 键进入播放页；无播放源时 Toast 提示 | `MainBrowseFragment.setupEventListeners()` |
| HLS 直播流播放（Media3 ExoPlayer + `HlsMediaSource` + `DefaultHttpDataSource`） | `PlayerActivity.initializePlayer()` |
| 播放进度与 `playWhenReady` 跨生命周期保存 | `PlayerActivity.releasePlayer()` |
| 播放失败在页面显示错误文案 | `PlayerActivity` 的 `Player.Listener.onPlayerError` |
| 播放页屏幕常亮 | `PlayerActivity.onCreate` 的 `FLAG_KEEP_SCREEN_ON` |
| 领域模型已预留主备源 / 请求头 / 图标 / EPG 字段 | `domain/model/Channel.kt` |
| **双端 UI 分流**：`MainActivity` 按设备形态分流——TV 走 Leanback `MainBrowseFragment`，手机/平板走 `MobileMainActivity`（`RecyclerView` + `GridLayoutManager` 网格，分类标题跨整行） | `util/DeviceUtils`、`ui/mobile/` |
| 手机端分类胶囊筛选（全部 / 按分类，选中蓝底白字）+ 总频道数胶囊（阶段七） | `MobileMainActivity.setupCategoryPills()`、`MobileChannelList.filterRows()` |
| 手机端徽章信息卡：分类强调色徽章、两位序号、副标题三级兜底（阶段七） | `MobileChannelAdapter`、`MobileChannelList.extractChannelBadge/formatChannelSubtitle` |
| 手机端播放页触控浮层：返回 / 上一台 / 下一台 / 重试，5 秒自动隐藏（错误态常驻）（阶段七） | `PlayerActivity.setupTouchUi()/showTouchOverlay()` |
| 手机端频道抽屉：按分类列出全部频道、当前频道标"播放中"并自动滚动定位，点选即切台（阶段七） | `PlayerActivity.openDrawer()`、`ChannelDrawerModel`、`ChannelDrawerAdapter` |
| 两端共用同一份数据与同一个播放页（`LiveTvApplication.channelRepository` + `PlayerActivity`） | `LiveTvApplication`、`ui/mobile/MobileMainActivity.kt` |
| 手机端订阅源管理：顶栏「刷新」「订阅源」按钮 + 暗色订阅对话框（保存并刷新 / 恢复内置源 / 取消），启动时先渲染内存快照再静默后台刷新（阶段八） | `ui/mobile/MobileMainActivity.kt`、`data/SubscriptionCoordinator.kt` |
| Debug 源集**仅本机地址**放行明文 HTTP（`127.0.0.1` / `localhost`），Release 无任何明文例外（阶段八） | `app/src/debug/res/xml/network_security_config.xml`、`app/src/debug/AndroidManifest.xml` |
| HTTP 401/403/429 **快速失败**：不消耗 1500/3000ms 退避，有备用源立即切换，否则进入致命错误（阶段八） | `RetryPolicy.isFastFailHttpStatus()`、`PlayerManager.handleFastFail()` |
| 403 / 429 专用错误文案（防盗链与风控限流分开提示） | `strings.xml` 的 `player_error_http_forbidden` / `player_error_http_rate_limited` |

### 明确**没有**的能力（避免误判）

- 频道源的**用户导入 / 管理 / 自定义**（配置只能改 `assets/` 后重新打包）
- **本地文件**导入 M3U（只支持 `http`/`https` 远程 URL，不读 `file://`）
- 频道源的增删改查与持久化存储（远程订阅 URL 与最近一次成功播放列表已持久化，但没有多源管理）
- **TV 端没有订阅源管理入口**（订阅配置只在手机端 `MobileMainActivity`）
- 远程订阅内容是**整体替换**内置频道，不与内置频道合并
- EPG 节目单、搜索、收藏、播放历史、设置页、Menu 菜单
- 多码率 / 清晰度切换、倍速、播放进度记忆
- 账号体系、仪器测试（`androidTest`）与 UI 自动化
- **浏览页**的加载中 / 空态 / 错误态反馈（播放页已有状态提示；浏览页取不到内容时页面直接为空）
- 播放器控制器按钮里的"上一台/下一台"（切台只支持遥控器上下键）
- M3U 的 `#EXTGRP` 分组、URL 参数（`?token=`）透传
- **手机端卡片不加载远程频道 Logo**（`Channel.logo` 有字段但无消费方，卡片用本地矢量占位图；未引入图片加载库）

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
- [x] 修复 CI 的 "Set up Android SDK" 步骤失败（`setup-android` 内置 `sdkmanager tools` 在 runner 上
      报 `Failed to find package 'tools'`；改为显式传 `cmdline-tools-version` + `packages`，
      并把 license 接受放到 install 之前），CI 最终 **success**

---

## 阶段四（本次交付）：M3U 直播源解析与多数据源合并

目标：扩展数据层，支持解析标准 M3U 播放列表，并把 JSON 源与 M3U 源按分类名合并。

- [x] `data/parser/M3uPlaylistParser.kt`：**纯 Kotlin** M3U 解析器
      （`#EXTINF` 属性提取 `tvg-id`/`tvg-logo`/`group-title`，URL 协议校验，坏条目跳过，
      任何输入都不抛异常）
- [x] `data/source/LocalM3uChannelSource.kt`：读取 `assets/sample.m3u` 并调用解析器
- [x] `assets/sample.m3u`：2 个分类 / 4 个频道的示例播放列表
- [x] `DefaultChannelRepository` 升级为**多数据源**：按分类名合并同名分类（先到先得 id）、
      同名分类内去重、单源异常只跳过该源
- [x] `MainBrowseFragment` / `PlayerActivity` 改为同时挂载 JSON 源与 M3U 源
- [x] 纯 JVM 单测 **27 个新增用例**（M3U 解析 17 / 仓库合并 5 / 示例 M3U 文件 3 + 仓库原有 2），
      用例总数 79 → **104**
- [x] 先红后绿两次红检（关闭属性提取 → 6 个失败；关闭分类合并 → 2 个失败）
- [x] 回归：`lintDebug testDebugUnitTest assembleDebug` 全绿（**0 Error**，104 个用例全过）
- [x] 同步更新 `docs/ARCHITECTURE.md`（多数据源支持、M3U 解析规则、合并策略）

### 阶段四未做（留给后续阶段）

- 用户导入 M3U / 网络拉取 M3U（**本阶段刻意不做**：不引入网络层与导入 UI）
- 频道源的增删改查与持久化
- M3U 的 `#EXTGRP` 分组、URL 参数透传、`#EXTVLCOPT` 选项
- 频道图标在 Presenter 中的绑定（`Channel.logo` 已有值，但卡片仍只显示文字）

---

## 阶段五（本次交付）：全局 Repository 容器收敛与 M3U 请求头全链路打通

目标：消除 UI 层的重复装配，并把 M3U 里的自定义请求头接到播放器的 DataSource 层。

- [x] `DefaultChannelRepository` 支持**内存缓存**：`enableCache` 构造参数（默认 true）、
      `refresh()` 强制重算、`invalidateCache()` 作废缓存
- [x] 新增 `LiveTvApplication`（`android.app.Application`）：`by lazy` 持有全局
      `ChannelRepository`（JSON 源 + M3U 源），并在 `AndroidManifest.xml` 注册
- [x] `MainBrowseFragment` / `PlayerActivity` 收敛到 `(application as LiveTvApplication).channelRepository`，
      UI 层不再各自拼装数据源（解决技术债 #20）
- [x] `M3uPlaylistParser` 支持 **`#EXTVLCOPT:` / `#KODIPROP:`** 指令与 **`#EXTINF` 行内属性**
      -> `Channel.headers`（`User-Agent` / `Referer` / `Origin`），
      与阶段三 `PlayerManager` 的 `setDefaultRequestProperties` 形成闭环
- [x] 修复 `#EXTINF` **引号内逗号**切分隐患（`group-title="新闻,综合",CCTV-1` 正确解析）
- [x] 状态清理纪律：新 `#EXTINF` 到来时丢弃上一条待定条目的请求头，杜绝串台污染
- [x] 纯 JVM 单测新增 20 个用例（M3U 解析 13 / 仓库缓存 7），用例总数 104 → **124**
- [x] 先红后绿三次红检（盲目逗号切分 → 3 个失败；关闭指令头提取 → 7 个失败；关闭缓存 → 3 个失败）
- [x] 回归：`lintDebug testDebugUnitTest assembleDebug` 全绿（**0 Error**，124 个用例全过）

### 阶段五未做（留给后续阶段）

- 用户导入 M3U / 网络拉取 M3U（仍不引入网络层与导入 UI）
- M3U 的 `#EXTGRP` 分组与 URL 参数（`?token=`）透传
- 频道图标在 Presenter 中的绑定

---

## 阶段 5.5：局域网 ADB 真机 UI 与遥控器全链路验收

目标：在真实硬件上跑完 `docs/TESTING.md` §6 的验收清单，修掉真机才暴露的缺陷。

设备：OnePlus PKG110（**手机**，Android 16 / API 36），无线 ADB 连接。

- [x] 无线 ADB 接入（`adb pair` + 端口探测 + `adb connect`），`HOME`/`ANDROID_USER_HOME` 指向可写目录
- [x] 首页多源分类验证：**新闻（4 = JSON 3 + M3U 1，已合并）/ 体育（3）/ 卫视（2）**
- [x] 焦点导航、进入播放页、Back 返回焦点恢复
- [x] 修复 **D1/D2：上下键切台与 Error 态 OK 重试在真机上完全失效**
      （Media3 `PlayerView.dispatchKeyEvent` 抢先吞键）→ 按键拦截前移到 `dispatchKeyEvent`
- [x] 修复 **D3：「已切换到」提示一帧都看不到**（被同步派发的 Loading 文案覆盖）→ 调换调用顺序
- [x] 修复 **D4：连续换台只切一台**（Media3 异步弹起控制器吞键）→ `controllerAutoShow = false` + 自定义"用户呼出"标记
- [x] 修复 **D5：网络故障显示成「HTTP 2001」**（`2000..2999` 兜底区间把 IO 错误码当 HTTP 状态码）→ 删除该兜底
- [x] 验证：切台循环、3 秒提示自动隐藏、控制器焦点隔离、Back 两段式、OK 手动重连、错误分类提示
- [x] 稳定性：连续进出播放页，`dumpsys` 中 PlayerActivity 实例数归零，无内存堆积
- [x] 回归：`lintDebug testDebugUnitTest assembleDebug` 全绿（**0 Error**，124 个用例全过）

### 阶段 5.5 未能验证的项

- **HLS 实际出画**：设备无外网（DNS 解析超时），所有源均进入 Error 态
- **主备源切换**：默认频道 `backupUrls` 为空，无备用源可切（重试计数 1/2、2/2 已验证）
- **切台不重建 ExoPlayer**：代码保证复用实例，但无日志直接佐证

---

## 阶段六（已交付）：双端 UI 智能分流（TV 端 Leanback + 手机端 RecyclerView 触控）

目标：同一份 APK 在 Android TV 与手机/平板上都能用，**TV 端遥控器体验 100% 不变**。

- [x] `util/DeviceUtils`：TV 判定 = `UI_MODE_TYPE_TELEVISION` **或** `FEATURE_LEANBACK`；提供**纯函数重载** `isTvDevice(uiModeType, hasLeanbackFeature)` 供 JVM 单测
- [x] `ui/mobile/MobileChannelList`（纯 Kotlin）：`buildRows(categories)` 展开成"分类标题 + 频道卡片"行序列（跳过空分类），`gridSpanCount(screenWidthDp)` 决定列数（>=840dp→4，>=600dp→3，其余→2），`pxToDp(px, density)` 负责 px→dp 换算（**除以** density，不能用 `TypedValue.applyDimension`，那是 dp→px）
- [x] `ui/mobile/MobileChannelAdapter`：`RecyclerView.Adapter`，分类标题用 `SpanSizeLookup` 跨满整行，频道卡片各占一格
- [x] `ui/mobile/MobileMainActivity`：`RecyclerView` + `GridLayoutManager`；从 `LiveTvApplication.channelRepository` 取数；空结果显示 `mobile_empty_channels`
- [x] 点击卡片带 `EXTRA_CHANNEL_ID` / `EXTRA_CHANNEL_NAME` / `EXTRA_STREAM_URL` 启动**共用**的 `PlayerActivity`；播放页逻辑**一行未改**
- [x] `MainActivity` 分流：TV 分支强制横屏并装载原 `MainBrowseFragment`；手机分支启动 `MobileMainActivity` 后 `finish()`
- [x] Manifest：`leanback` required 改 **false**；`MainActivity` 同时带 `LAUNCHER` + `LEANBACK_LAUNCHER`（单一入口双端各一个图标）；移除 manifest 级 landscape（避免手机启动旋转）；新增 `MobileMainActivity`（exported=false，`Theme.LiveTv.Mobile`）
- [x] 新增资源：`activity_mobile_main.xml` / `item_mobile_channel.xml` / `item_mobile_category_header.xml` / `ic_channel_placeholder.xml` / `bg_mobile_channel_card.xml` / `Theme.LiveTv.Mobile` / 移动端颜色与字符串
- [x] 先红后绿：临时改坏 Leanback 判定分支与 >=840dp 列数 → **2 个用例失败**（`137 tests completed, 2 failed`）；还原后全绿
- [x] 先红后绿（宽度换算）：临时把 `pxToDp` 改成乘法 → **3 个用例失败**（`141 tests completed, 3 failed`）；还原后全绿
- [x] 回归：`lintDebug testDebugUnitTest assembleDebug` 全绿（**0 Error**，**141 个用例**）
- [x] 真机验证（OnePlus PKG110 / Android 16）：`am start` 后 `topResumedActivity=com.livetv.android/.ui.mobile.MobileMainActivity`（手机走触控分支）；页面渲染出「电视节目」标题、分类「新闻（5）」、整行分类标题 + 网格卡片
- [x] **宽度换算修正后的真机复核**：竖屏 1264px @3.5x → 361dp → **2 列**（卡片 x=98 / 688）；横屏 2780px @3.5x → 794dp → **3 列**（卡片 x=238 / 1090 / 1942）。修正前因 `TypedValue.applyDimension` 反向换算，两种方向都被误判为 4 列

### 阶段六未做（留给后续阶段）

- **手机端卡片不加载远程频道 Logo**（技术债 #21）：`Channel.logo` 有字段但无消费方，封面用本地矢量占位图；**刻意未引入图片加载库**（Glide/Coil）
- 触摸手势增强（长按、滑动切台、缩略图预览）——当前手机端只有"点击进入播放页"
- 手机端播放页的触控增强（触摸进度条已在 Media3 `PlayerView` 内置，触摸切台未接）
- 真实 Android TV 设备上的回归（阶段六 TV 分支**只做了代码级保证**，未在 TV 硬件上实测）

---

## 阶段七（本次交付）：手机端 UI 视觉重做 + 播放页触控切台与选台抽屉

目标：把阶段六"能看"的手机端做成"好读"，并给播放页补上触控端应有的操作路径；
**TV 遥控器路径一行未改**。

### 手机浏览页

- [x] 色板重做（`colors.xml`）：画布 `#0B0E14`、卡片 `#151A23`、按压 `#1E2533`、描边 `#232B3A`、
  主/次/弱三级文字 `#F3F6FA`/`#8B95A7`/`#5E687A`、强调冷蓝 `#3B82F6`
- [x] 删除巨型黄色 16:9 占位图（`ic_channel_placeholder.xml` 已删）；改为徽章 + 文字的紧凑信息卡
- [x] 卡片：14dp 圆角（四角完整，无子图溢出）+ 1dp 描边 + ripple 按压态；36dp 圆角方形徽章（分类色 16% 底、55% 边框、原色文字）；
      右上两位序号（`01`…）；频道名 15sp 加粗单行省略；副标题 12sp 单行，**描述 → 流地址文件名主干 → 频道 id** 三级兜底
- [x] 顶部：`Live TV` / `电视节目` / `共 N 个频道` 胶囊
- [x] 分类胶囊栏：`全部` + 各分类（含数量），选中为蓝底白字（`View.isSelected` + selector），可横向滚动
- [x] 分类标题：3dp 蓝色竖条 + 分类名 + `N 台` 计数胶囊
- [x] 纯 Kotlin 下沉：`extractChannelBadge` / `categoryAccentIndex` / `filterRows` / `formatChannelIndex` / `formatChannelSubtitle`
- [x] 徽章配色修正：FNV 散列下 `新闻` 与 `体育` 实测撞同一色，改为"位置上加权的码点和"，三个随包分类稳定分到 0 / 3 / 5

### 播放页触控浮层与抽屉（仅触控设备）

- [x] `PlayerActivity` 按 `!DeviceUtils.isTvDevice(this)` 启用触控 UI；所有触控视图 XML 默认 `GONE`，TV 端不参与焦点/点击
- [x] 顶栏：返回（`finish()`）、`频道名 · 位置/总数`、频道列表（打开抽屉）
- [x] 底栏：上一台 / 重试（仅错误态可见）/ 下一台；上一台/下一台复用 `switchChannel(direction)` → `switchToChannel(channel)`，与遥控器同一条状态流
- [x] 轻触画面切换浮层显隐；5 秒无操作自动隐藏；**错误态不自动隐藏**（否则"重试"点不到）；
      `postDelayed` 的 runnable 在 `onDestroy` 里移除，不持有 Activity
- [x] 错误提示分端：触控端 `点按「重试」重新连接 · 用下方按钮切换频道`；TV 端仍是 `按确认键重试 · 按上下键切换频道`
- [x] 抽屉：右侧 280dp 暗面板 + 遮罩，`ChannelDrawerModel` 按分类分组、当前频道标"播放中"并自动滚动定位；
      点条目先 `navigator.locate(channel.id, channel.streamUrl)` 再 `switchToChannel(channel)`，切完自动收起
- [x] BACK 三段式：抽屉打开 → 只关抽屉；控制器打开 → 只关控制器；否则才退出
- [x] 修掉"离开错误态后重试按钮残留"：`renderState` 每次同步 `overlayRetry` 可见性

### 测试与真机

- [x] 新增 20 个纯 JVM 用例（`MobileChannelListTest` 扩 14 + 新增 `ChannelDrawerModelTest` 7），累计 **161 个用例 / 16 个测试类**
- [x] 先红后绿：把 `filterRows` 改成忽略筛选 → `160 tests completed, 1 failed`；
      把徽章 CJK 分支从 2 字改成 1 字 → `160 tests completed, 1 failed`；两次均已还原
- [x] 回归：`./gradlew lintDebug testDebugUnitTest assembleDebug --console=plain --no-daemon` 全绿（**0 Error**，**161 个用例**）
- [x] 真机（OnePlus PKG110 / Android 16 / 1264×2780 @3.5x）四场景实测：
  1. 竖屏首页"全部"——2 列网格，分类标题蓝条 + `5 台`/`3 台`，卡片徽章按分类分色（新闻蓝 / 体育绿）
  2. 竖屏/横屏分类筛选——点"体育 3"后只剩 3 张卡片、无冗余分类标题、序号从 `01` 重新开始
  3. 播放页触控浮层——`体育赛事 · 6/10` + 返回/频道列表/上一台/重试/下一台；轻触画面可收起与再显示
  4. 抽屉选台——打开抽屉定位到"播放中"的当前频道，点"湖南卫视"立即切到 `湖南卫视 · 9/10` 并自动收起

### 阶段七未做

- 触摸手势（长按预览、左右滑动切台）；抽屉内搜索
- 手机端加载远程 Logo（技术债 #21 仍在）
- 真实 Android TV 硬件回归：TV 分支仅代码级保证 + 触控视图 `GONE` 隔离

---

## 阶段八（本次交付）：远程 M3U 订阅与本地缓存 + 403/429 快速失败 + Debug 本地沙箱接入

目标：让"频道从哪来"从"改 assets 重新打包"变成"填一个 M3U URL"，
并让防盗链 / 风控类 HTTP 状态**不浪费退避重试**。

### 远程订阅与三级兜底

- [x] `data/remote/RemoteM3uFetcher.kt`：纯 Kotlin + JDK `HttpURLConnection`；
      仅接受 `http`/`https`（`file://`、`ftp://`、空串在**建立连接前**就返回 `InvalidUrl`）；
      连接超时 5000ms / 读取超时 8000ms；响应体硬上限 2MB（超限 `SizeExceeded`）；
      仅 HTTP 200 成功（其余 `HttpError(code)`）；响应按 UTF-8 解码
- [x] `data/subscription/SubscriptionStore.kt`：纯 Kotlin 接口（可注入内存 fake）；
      `FileSubscriptionStore` 把 URL 存 `SharedPreferences("livetv_subscription")`，
      M3U 原文存 `filesDir/remote_playlist.m3u`，**临时文件 + rename** 原子替换
- [x] `data/repository/SubscriptionChannelRepository.kt`：`SubscriptionRepository` 实现，
      `@Volatile` 快照 + `lock` 串行写；`getCategories()` / `findChannelById()` 保持**同步且线程安全**，
      因此 `PlayerActivity`、`MainBrowseFragment` 无需改动即可消费订阅频道
- [x] 兜底链：**远程订阅 → 本地有效缓存 → 内置 assets**
      - 初始化：仅当"已配置非空 URL"且"缓存 M3U 可解析出 ≥1 频道"时用缓存，否则用内置
      - `refreshSubscription()`：未配置 URL → `BuiltinOnly(n)`；
        拉取成功且解析出 ≥1 频道 → 写缓存 + 原子换快照 → `RemoteLoaded(n)`；
        拉取失败或解析为空 → 有可用缓存 `FallbackToCache(n, reason)`，否则 `FallbackToBuiltin(n, reason)`
      - HTTP 200 但内容为空 / 无频道：**按失败处理**，不清空当前有效列表，也不覆盖缓存
      - `updateSubscriptionUrl(url)`：空白视为清除 → 清缓存 + 回内置；
        换成新 URL 时旧缓存立即作废（URL 路径区分大小写，不做 ignoreCase 比较）
      - `n` 统一为**频道总数**（不是分类数）
- [x] `data/SubscriptionCoordinator.kt`：单线程 `ExecutorService` 串行刷新 + 主线程 `Handler` 回调；
      **未引入 Coroutines / OkHttp / Retrofit**
- [x] `LiveTvApplication` 全局装配 `SubscriptionChannelRepository`，
      `channelRepository` 指向它，TV 与手机端共用同一份生效快照

### Debug 本地沙箱接入（明文隔离）

- [x] `app/src/debug/res/xml/network_security_config.xml`：`base-config cleartextTrafficPermitted="false"`，
      仅 `127.0.0.1` 与 `localhost` 两个 domain `includeSubdomains="false"` 放行明文
- [x] `app/src/debug/AndroidManifest.xml`：仅 Debug 源集挂 `android:networkSecurityConfig`
- [x] `app/src/main/` **零**明文放行配置（`usesCleartextTraffic` / `cleartextTrafficPermitted` 均无命中），
      Release 无任何明文例外
- [x] Debug 覆盖文件用 `tools:ignore` 忽略 `MissingLeanbackLauncher` / `MissingLeanbackSupport` /
      `ImpliedTouchscreenHardware` 三条 TV 检查：manifest 合并以 Debug 文件为基准文档，
      Lint 会把根 `<manifest>` 元素级问题定位到该文件，而真正的 TV 声明在 main 源集且完整

### 401/403/429 快速失败

- [x] `RetryPolicy.FAST_FAIL_HTTP_STATUSES = setOf(401, 403, 429)`，
      `isFastFailHttpStatus(code)` / `isFastFail(error)` 为纯函数
- [x] 命中即**不消耗**当前源的 1500/3000ms 退避：有下一候选源直接 `SwitchSource`，
      无下一源直接 `Fatal`（进入 `PlaybackState.Error(fatal = true)`）
- [x] `PlayerManager.handleFastFail()`：取消待执行退避回调，`FAST_FAIL_SWITCH_DELAY_MS = 0L` 立即切源；
      快速失败切源**不先派发 `Failed`**（否则 reducer 进入 Error 后会忽略后续 `Prepare`）
- [x] 超时、网络中断、5xx、源解析失败**保留**每源 2 次退避重试
- [x] 文案：403 = `访问被拒绝或签名已过期（HTTP 403）`；429 = `请求过于频繁，触发风控限流（HTTP 429）`；
      其他 HTTP 码仍是 `服务器返回错误（HTTP %d）`

### 手机端订阅 UI

- [x] 顶栏新增「刷新」「订阅源」两个紧凑按钮（沿用 `#0B0E14` 画布 / `#151A23` 卡片 / `#3B82F6` 强调色）
- [x] 刷新：后台执行 + 禁用重复点击（按钮半透明）+ 按 `RefreshResult` 给出 Toast 反馈
- [x] 订阅源对话框：当前 URL 可编辑 / 清空；动作 `保存并刷新` / `恢复内置源` / `取消`
- [x] 订阅源对话框只接受用户手输的 http(s) M3U 地址（应用内不内置任何演示/沙箱快捷入口）
- [x] 启动顺序：**先渲染内存快照**（不白屏）→ 有订阅 URL 时后台静默刷新 → 成功后平滑更新

### 测试与验收

- [x] 新增 31 个纯 JVM 用例（`RemoteM3uFetcherTest` 10 + `ChannelRepositorySubscriptionTest` 11 +
      `RetryPolicyTest` 快筛 10），累计 **192 个用例 / 18 个测试类**
- [x] 网络测试**完全离线**：用 `java.net.ServerSocket` 起本地 `127.0.0.1` 随机端口服务
      （AGP 单测编译不暴露 `jdk.httpserver`，故不用 `com.sun.net.httpserver.HttpServer`），不访问公网
- [x] 先红后绿：
      1. 清空 `FAST_FAIL_HTTP_STATUSES` → `192 tests completed, 7 failed`（全部落在 `RetryPolicyTest`）
      2. 把缓存读取强制为 `null` → `192 tests completed, 2 failed`
      两次破坏均已还原，`RED-CHECK` 残留 0
- [x] 回归：`./gradlew clean && ./gradlew lintDebug testDebugUnitTest assembleDebug --console=plain --no-daemon`
      全绿（**0 Error**，**192 个用例**）

### 阶段八未做

- 订阅 URL 存 `SharedPreferences` 是**明文**（未加密），属已知技术债
- `store.saveUrl()` / `store.clearPlaylist()` 的 I/O 异常被 `runCatching` 吞掉，协调器**观察不到**持久化失败
- 本地文件导入 M3U（只支持 http/https）
- TV 端订阅源管理入口；远程订阅与内置频道的合并策略
- 403/429 的真机 UI 实测（无线 ADB 真机离线，本轮以纯 JVM 单测 + 构建为验收依据）

---

## 阶段九（本次交付）：MGTV 播放源接入（真机已出画）

> ✅ **MGTV 已在真机上播放成功**：`sign` 从芒果 TV 网页播放器公开 JS 还原
> （`UPPER(MD5(SALT + 参数名升序拼接 key+value + SALT))`，盐值为 JS 公开常量），
> HAR 3 组样本离线校验 **3/3 一致**；`did`/`deviceId` 由客户端本地生成 UUID，请求不携带 Cookie。
> 真机（OnePlus PKG110 / Android 16）实测：首页出现 MGTV 分组 → 点开「金鹰卡通」→ 画面持续播放，
> 崩溃缓冲区为空。详见 `docs/MGTV-GAP-REPORT.md`。

### 已交付（可在源码中指出）

| 项 | 事实 | 证据 |
|---|---|---|
| P0 HLS 审查 | Media3 1.4.1 已含 `media3-exoplayer-hls`；`MediaSource` 统一由 `DefaultMediaSourceFactory` 构建，未使用 `ProgressiveMediaSource` 强播 M3U8 | `app/build.gradle.kts`、`player/PlayerManager.kt` |
| P0 无后缀 HLS | `StreamTypeDetector` 显式推断 `application/x-mpegURL` 并写入 `MediaItem`，覆盖无 `.m3u8` 后缀 + 服务端 `Content-Type` 不规范的 IPTV 流；`.mp4/.mkv/.flv/.ts/.mp3/.aac/.webm` 明确不判为 HLS | `domain/stream/StreamTypeDetector.kt` |
| P1 通用抽象 | `StreamResolver` / `ResolvedStream` / `StreamResolveResult` / `StreamRefreshCoordinator` / `HeaderHostPolicy` / `RoutingStreamResolver` / `Clock`，全部纯 Kotlin，可注入时钟 | `domain/stream/` |
| P1 会话接入 | `PlayerManager` 构造注入 `streamResolver` + `clock`，持有 `PlaybackSession`；静态频道行为与改造前一致（`RetryPolicy.candidateUrls` 主备源路径不变） | `player/PlayerManager.kt` |
| P1 Host 隔离 | `allowedHeaderHosts` 非空时走 `media/ScopedHeaderDataSourceFactory`，每次 `open(DataSpec)` 按 host 过滤，并关闭跨协议重定向 | `media/ScopedHeaderDataSourceFactory.kt`、`domain/stream/HeaderHostPolicy.kt` |
| P1 刷新状态机 | 到期前 60 秒主动刷新；403 最多重解析 **1 次**（`MAX_FORBIDDEN_REFRESH_ATTEMPTS = 1`）；第二次 403 / 429 立即快速失败；其他状态码交回既有重试策略 | `domain/stream/StreamRefreshCoordinator.kt` |
| P1 MGTV 数据层 | `MgtvEndpoints` / `MgtvCredentials` / `MgtvHttpClient` / `MgtvCatalogFetcher` / `HttpMgtvSourceFetcher` / `MgtvJsonParser` / `MgtvSourceSelector` / `MgtvStreamResolver` / `MgtvProvider` | `data/mgtv/` |
| P1 白盒筛选 | 仅放行 `format=.m3u8` + `isp2p==0` + `hdcp==0` + 合法 `https://`；无合规源返回 `UNSUPPORTED_SOURCE`，**绝不降级到 P2P** | `data/mgtv/MgtvSourceSelector.kt` |
| P2 熔断门禁 | 无外部 `MgtvCredentialProvider` 时 `HttpMgtvSourceFetcher` **不发出任何请求**，直接 `AuthUnavailable`；未实现、未猜测、未伪造 `sign` | `data/mgtv/HttpMgtvSourceFetcher.kt` |
| P4 P2P 边界 | 未引入任何 P2P / WebSocket 模块或第三方依赖 | `app/build.gradle.kts` |

### P2 参数归因结论（摘要）

| 参数 | 类别 | 可否合法获取 |
|---|---|---|
| `cameraId` / `platform` / `appVersion` / `clientKey` / `auth_mode` / `init_definition` / `hdts` / `lls` / `supportFlv` / `_support` | 静态 | ✅ 公开常量或频道目录 |
| `_t` | 动态（时间戳） | ✅ 本地生成 |
| `uid` / `token` | 动态（可空） | ⚠️ 匿名会话为空 |
| `did` / `deviceId` | 动态（设备 UUID） | ✅ 客户端本地生成 UUID（网页端同样如此） |
| `sign` | 动态（32 位十六进制，绑定完整查询参数集） | ✅ 无需密钥：盐值 + 参数排序拼接的 MD5，算法在公开 JS 中 |

→ **Go**：三组 HAR 样本（287 两次、344 一次）离线复算全部命中；真实网络请求返回 `code=0`。

### 测试

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `SessionGenerationGateTest` | 5 | 代际号：快速连续切台时旧解析结果必须作废 |
| `MgtvSignerTest` | 8 | **签名算法黄金向量**（期望值由独立 Node 实现预先算出）、参数顺序无关、空串参与、`_t` 变化即变 |
| `MgtvJsonParserTest` | 9 | 脱敏 JSONP/JSON 目录与播放源解析、错误码、坏 JSON |
| `MgtvSourceSelectorTest` | 9 | `.m3u8`/`isp2p=0`/`hdcp=0`/https 白盒过滤、P2P/HDCP/HTTP/非 m3u8 拦截、清晰度优先与确定性回退 |
| `MgtvEndpointsTest` | 7 | 目录/播放源参数、`sourceParams`→`sourceUrl` 拼接顺序、**数据层无硬编码 sign/IP/敏感 token 守卫** |
| `MgtvHttpClientTest` | 8 | 本地回环服务：200/403/404、非法 scheme、超限、连接失败 |
| `HttpMgtvSourceFetcherTest` | 8 | 本地回环：RawJson、请求携带 32 位大写 `sign`、签名与 `MgtvSigner` 一致、`did==deviceId`、Referer、403/网络错误 |
| `MgtvStreamResolverTest` | 10 | 成功流、过期时间、403/404/429 映射、业务错误码、无可用源、Host 白名单 |
| `HeaderHostPolicyTest` | 10 | 精确/子域匹配、后缀伪造拦截、空白名单不限制 |
| `StreamTypeDetectorTest` | 7 | `.m3u8`、无后缀参数/路径识别、渐进式排除 |
| `StreamRefreshCoordinatorTest` | 10 | 缓存复用、临期刷新、403 单次重解析、二次 403/429 快速失败、500 委派、预算重置 |
| `StaticStreamResolverTest` | 5 | 普通 IPTV 无回归（url/headers/MIME/无过期/无 Host 限制） |
| `RoutingStreamResolverTest` | 3 | `mgtv:` 前缀路由与静态回退 |
| `MgtvFailClosedHardeningTest` | 11 | `isp2p`/`hdcp` 缺失或非整数拒绝、DRM 源拒绝、缺 `code` 拒绝、Origin 无尾斜杠、userinfo 拒绝、过期时间钳制、302 不跟随 |
| `StreamHardeningRegressionTest` | 5 | https/ftp 拒绝、userinfo 伪造、DASH/JSON 不误判、并发 403 只重解析一次、层间依赖纯度 |

累计新增 **14 个测试类 / 107 个用例**（本仓库当前合计 34 个测试类 / 319 个用例），
全部使用脱敏 fixture + 本地回环 `ServerSocket`，100% 离线。

### 阶段九未做

- **地址到期刷新的真机长测**：逻辑为「剩余 < 60 秒主动重解析」，8 小时有效期无法在验收窗口内验证
- **断网恢复真机实测**：有重试与重解析路径，但未做断网/恢复用例
- **TV（Leanback）端未接入 MGTV**：只在手机/平板端作为独立分组追加
- **清晰度切换 UI**：当前固定 `init_definition=2`（480P）

---

## P1：核心架构与数据系统

目标：让"频道从哪来"可配置、可解析、可测试。

| # | 能力 | 交付要点 |
|---|---|---|
| P1-1 | `ChannelRepository` 接口 + 内存实现 | **阶段二已完成接口，阶段四已完成多源聚合**（JSON + M3U 按分类名合并），**阶段八已完成网络订阅源**（远程 → 缓存 → 内置三级兜底） |
| P1-2 | 源解析器（m3u / m3u8 / 简单 XML） | **阶段四已完成 M3U 基础版**（`M3uPlaylistParser`，纯函数 + L1 单测）；`#EXTGRP` / URL 参数透传待补 |
| P1-3 | 源管理与本地持久化 | **阶段八已完成单 URL 订阅持久化**（`SharedPreferences` + `filesDir` 原子写）；多源增删改查仍待做 |
| P1-4 | 状态流转（Loading / Content / Empty / Error） | **播放页阶段三已完成**（`PlaybackState` + `PlaybackReducer`）；浏览页仍需 sealed class + StateFlow |
| P1-5 | 网络层与错误分类 | **阶段三已完成播放错误分类**（Network / HttpStatus / SourceParsing / Timeout / Unknown）；**阶段八已完成订阅拉取网络层**（`RemoteM3uFetcher`）；浏览页的加载 / 错误态 UI 仍待做 |
| P1-6 | 单元测试体系补齐 | **阶段二完成数据层、阶段三完成播放器层、阶段八完成远程订阅层**；状态流转落地后补浏览页 L1 测试 |

> 约束：不引入 Hilt/Dagger 等 DI 框架；不拆多模块；依赖注入用手动构造。

## P1：播放器与交互

| # | 能力 | 交付要点 |
|---|---|---|
| P1-7 | 播放重试与线路切换 | **阶段三已完成**：每源 2 次重试 + 主备源自动切换 + 手动重置；**阶段八追加 401/403/429 快速失败**（不消耗退避，立即切备用源或致命错误） |
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
| P2-9 | ~~MGTV 动态直播源（真实拉流）~~ **阶段九已跑通** | `sign` 已还原并本地计算，`did` 本地生成；真实网络验证 `code=0` + HLS/TS 200。见 `docs/MGTV-GAP-REPORT.md` |
| P2-10 | ~~MGTV 频道目录 / UI 集成（P5）~~ **手机端已完成** | `MgtvCatalogCoordinator` 后台拉目录，独立分组追加；TV 端待接 |
