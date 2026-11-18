# ARCHITECTURE — LiveTV 现状架构与演进方向

> 本文分两部分：**第一部分 100% 描述当前源码事实**（每条都能在指定文件里指到），
> **第二部分是演进方向**（尚未实现，仅供排期参考，不代表现状）。
> 阶段二已落地轻量分层（`data/` + `domain/`），**仍未拆分多 Gradle 模块**。

---

## 第一部分：当前真实结构

### 1. 模块与构建

| 项 | 事实 | 出处 |
|---|---|---|
| 模块 | 只有 `:app`，根项目名 `LiveTv` | `settings.gradle.kts` |
| namespace / applicationId | 均为 `com.livetv.android` | `app/build.gradle.kts` |
| SDK | compileSdk 34 / minSdk 28 / targetSdk 34 | `app/build.gradle.kts` |
| 工具链 | AGP 8.5.2、Kotlin 1.9.24、Gradle Wrapper 8.7、JDK 17 | `build.gradle.kts`、`gradle/wrapper/gradle-wrapper.properties` |
| 特性开关 | 仅 `viewBinding = true`；无 `buildConfig` 自定义字段、无多渠道、无 `buildTypes` 定制 | `app/build.gradle.kts` |
| release | `isMinifyEnabled = false`，只挂默认 ProGuard 规则 | `app/build.gradle.kts` |
| 仓库源 | 阿里云 google/public 镜像 + `gradlePluginPortal()`；依赖解析额外含 `mavenCentral()` | `settings.gradle.kts` |

运行期依赖（全部 `implementation`）：`androidx.core:core-ktx:1.13.1`、
`androidx.fragment:fragment-ktx:1.8.2`、`androidx.leanback:leanback:1.0.0`、
`androidx.recyclerview:recyclerview:1.3.2`、`androidx.appcompat:appcompat:1.7.0`、
`androidx.media3:media3-exoplayer:1.4.1`、`androidx.media3:media3-ui:1.4.1`、
`androidx.media3:media3-exoplayer-hls:1.4.1`。

测试期依赖（`testImplementation`）：`junit:junit:4.13.2`、
`org.json:json:20231013`（后者是纯 JVM 单测能跑真实 JSON 解析的前提，见 `docs/TESTING.md`）。

### 2. 包结构与类清单

```
app/src/main/java/com/livetv/android/
├── MainActivity.kt                       # 宿主 Activity
├── data/                                 # ← 阶段二新增：数据获取与解析
│   ├── model/ChannelConfigDto.kt        # JSON 映射 DTO（version / categories / channels）
│   ├── parser/ChannelJsonParser.kt      # 纯文本 JSON → DTO/领域模型 的解析与校验
│   ├── source/ChannelDataSource.kt       # 数据源接口
│   ├── source/LocalAssetChannelSource.kt # 读 assets/channels.json 并调用 Parser
│   └── repository/DefaultChannelRepository.kt
├── domain/                               # ← 阶段二新增：领域层
│   ├── model/Channel.kt                  # 领域模型（由阶段一 model/Channel.kt 升级迁移）
│   ├── model/ChannelCategory.kt          # 分类领域模型
│   └── repository/ChannelRepository.kt   # 仓库接口
├── player/                               # ← 阶段三新增：播放器层
│   ├── PlaybackState.kt                  # 纯 Kotlin：状态密封接口（纯策略层）
│   ├── PlaybackEvent.kt                  # 纯 Kotlin：事件密封接口（纯策略层）
│   ├── PlaybackReducer.kt                # 纯 Kotlin：状态归约（纯策略层）
│   ├── PlaybackError.kt                  # 纯 Kotlin：错误分类（纯策略层）
│   ├── PlaybackErrorMapper.kt            # 纯 Kotlin：错误映射（纯策略层）
│   ├── RetryPolicy.kt                    # 纯 Kotlin：重试与主备源状态机（纯策略层）
│   ├── ChannelNavigator.kt               # 纯 Kotlin：扁平列表定位与循环切台（纯策略层）
│   └── PlayerManager.kt                  # Android/Media3 桥接层
└── ui/
    ├── MainBrowseFragment.kt             # Leanback 浏览页（通过 Repository 取数）
    ├── ChannelCardPresenter.kt           # 频道卡片 Presenter
    └── PlayerActivity.kt                 # 播放页（UI + 遥控器按键，不含播放器逻辑）
```

| 类 | 职责 |
|---|---|
| `MainActivity` | 宿主 Activity，`setContentView(R.layout.activity_main)`，在 `savedInstanceState == null` 时提交 `MainBrowseFragment` |
| `MainBrowseFragment` | 浏览页：标题/品牌色，**通过 `ChannelRepository` 取分类列表**并组装 `ListRow`，处理卡片点击跳转播放 |
| `ChannelCardPresenter` | 渲染 `ImageCardView` 卡片（标题 + 描述） |
| `PlayerActivity` | 播放页：加载频道、渲染状态与提示、**遥控器按键映射**；不含 ExoPlayer 逻辑 |
| `Channel`（domain） | 领域模型：`id`、`name`、`description`、`streamUrl`、`backupUrls`、`headers`、`logo`、`epgId`、`enabled` |
| `ChannelCategory`（domain） | `id`、`name`、`channels`（保证非空） |
| `ChannelRepository`（domain） | 仓库接口：`getCategories(): List<ChannelCategory>`，**契约是不抛异常** |
| `ChannelDataSource`（data） | 数据源接口：`loadCategories(): List<ChannelCategory>` |
| `LocalAssetChannelSource`（data） | 读 `assets/channels.json` → 交给 `ChannelJsonParser` |
| `DefaultChannelRepository`（data） | 仓库实现：调用数据源并兜底异常 |
| `ChannelJsonParser`（data） | 纯函数解析 + 校验（URL 协议白名单、必填字段、disabled/空分类过滤） |
| `ChannelConfigDto` 等（data） | JSON 原始形状的 DTO，只做映射不做业务校验 |
| `PlaybackState`（player，纯） | 状态：`Idle` / `Loading` / `Buffering` / `Playing` / `Paused` / `Error(error, fatal)` |
| `PlaybackEvent`（player，纯） | 事件：`Prepare` / `Buffering` / `Ready` / `Playing` / `Paused` / `Failed` / `Fatal` / `ManualReset` / `Released` |
| `PlaybackReducer`（player，纯） | `reduce(state, event)`：状态转换规则（非法转换保持原状态） |
| `PlaybackError`（player，纯） | 错误分类：`Network` / `HttpStatus(code)` / `SourceParsing` / `Timeout` / `Unknown` |
| `PlaybackErrorMapper`（player，纯） | `map(responseCode, causeKind)`：原始字段 → `PlaybackError` |
| `RetryPolicy`（player，纯） | 重试与主备源状态机：候选源生成、`RetryState`、三种 `RetryOutcome` |
| `ChannelNavigator`（player，纯） | 扁平频道列表的定位（id → url 回退）与循环切台 |
| `PlayerManager`（player，桥接） | ExoPlayer 生命周期与复用、DataSource 请求头/超时、重试调度、Media3 事件翻译 |

> **纯策略层约束**：`player` 包中除 `PlayerManager.kt` 外的文件**禁止**引用 `android.*` 与
> `androidx.media3.*`，由 `PlayerLayerPurityTest` 做源码扫描固化（3 个用例）。

### 3. 数据流向（当前唯一一条链路）

```
MainActivity.onCreate
  └─ activity_main.xml: FrameLayout(id=main_browse_fragment)
  └─ commit(MainBrowseFragment)              [仅首次创建]

MainBrowseFragment.onCreate
  ├─ setupUi()      title / headersState=HEADERS_ENABLED / isHeadersTransitionOnBackEnabled=true / brandColor
  ├─ loadRows()
  │     channelRepository = DefaultChannelRepository(LocalAssetChannelSource(requireContext().assets))
  │     channelRepository.getCategories()               ← 领域接口
  │       └─ DefaultChannelRepository.getCategories()   ← try/catch 兜底，异常 → emptyList()
  │           └─ LocalAssetChannelSource.loadCategories()
  │               ├─ assets.open("channels.json").use { readBytes().toString(UTF_8) }   ← 失败 → emptyList()
  │               └─ ChannelJsonParser.parse(json)     ← 纯函数，失败 → emptyList()
  │                     ├─ parseToDto(json)             ← JSONObject 解析，失败 → null
  │                     ├─ 过滤 enabled=false 的频道
  │                     ├─ 校验 streamUrl 为 http/https，剔除非法频道
  │                     ├─ 清洗 backupUrls（仅保留 http/https）
  │                     └─ 丢弃过滤后为空的分类
  │     for (category in categories):
  │       rowsAdapter.add(ListRow(HeaderItem(category.name),
  │                              ArrayObjectAdapter(ChannelCardPresenter()) + category.channels))
  └─ setupEventListeners()
         onItemViewClickedListener:
           item is Channel 且 streamUrl 非空 → startActivity(PlayerActivity)
                                                extra: EXTRA_CHANNEL_ID / EXTRA_CHANNEL_NAME / EXTRA_STREAM_URL
           否则 → Toast(channel_selected, name)

PlayerActivity（阶段三：播放器逻辑已抽离到 player/ 包）
  onCreate: window.addFlags(FLAG_KEEP_SCREEN_ON) → inflate activity_player.xml (ViewBinding)
            resolveInitialChannel():
              repository.getCategories() → ChannelNavigator.flatten() → 扁平列表
              按 EXTRA_CHANNEL_ID 定位；未命中按 EXTRA_STREAM_URL 匹配；再未命中用 Extra 构造临时 Channel
            PlayerManager(context, onStateChanged, onChannelChanged)
  onStart : playerManager.start(currentChannel)
              ├─ player == null → ExoPlayer.Builder(...).build()   ← onStart~onStop 期间只建一次
              └─ switchChannel(channel):
                   retryState = RetryPolicy.onChannelSwitched(candidates.size)   ← 丢弃旧频道状态
                   prepare(mainUrl):
                     ProgressiveMediaSource.Factory(
                       DefaultHttpDataSource.Factory()
                         .setDefaultRequestProperties(channel.headers)   ← 请求头走 DataSource 层
                         .setConnectTimeoutMs(10000).setReadTimeoutMs(10000)
                         .setAllowCrossProtocolRedirects(true))
                     exoPlayer.setMediaSource(mediaSource) → prepare()   ← 不重建 ExoPlayer
  onResume: hideSystemUi()
  onStop  : playerManager.stop()
              retryHandler.removeCallbacksAndMessages(null)
              player.removeListener → player.release() → player = null
              dispatch(Released) → state = Idle
```

### 3.1 播放状态流转

```
Idle ──Prepare──▶ Loading ──Buffering/Ready──▶ Buffering ──Playing──▶ Playing
                     │                            │                    │
                     │                            └──Paused──▶ Paused ──┘
                     ▼
                  Error(error, fatal=false)      ← 单次失败，自动重试
                     │  (手动重置 ManualReset)
                     ▼
                  Loading

Error(error, fatal=true)  ← 所有候选源耗尽，只能按 OK 手动重置
```

规则（由 `PlaybackReducer` 固化，15 个 L1 用例覆盖）：

| 当前状态 | 事件 | 结果 |
|---|---|---|
| `Idle` | `Prepare` | `Loading` |
| `Idle` | `Buffering` / `Playing` / `Paused` / `Ready` | 保持 `Idle`（非法转换） |
| `Loading` | `Buffering` / `Ready` | `Buffering` |
| `Loading` | `Playing` | 保持 `Loading`（还没 ready） |
| `Buffering` | `Playing` | `Playing` |
| `Playing` | `Paused` | `Paused` |
| `Playing` | `Buffering` | `Buffering` |
| 任意 | `Failed(error)` | `Error(error, fatal=false)`；**已在 Error 态时保持首个错误不被覆盖** |
| 任意 | `Fatal(error)` | `Error(error, fatal=true)` |
| `Error` | `Buffering` / `Playing` / `Ready` / `Prepare` | **保持 Error**（忽略旧回调，避免"复活"） |
| `Error` | `ManualReset` | `Loading` |
| 任意 | `Released` | `Idle` |

### 3.2 主备源 Failover 机制

候选源列表由 `RetryPolicy.candidateUrls(channel)` 生成：`[streamUrl] + backupUrls`，
按阶段二同款规则过滤（仅 `http://` / `https://`，忽略大小写）。

`RetryPolicy.RetryState(sourceIndex, retryCount, candidateCount)` 为不可变状态，
`onFailure(state, error)` 返回三种结果之一：

| 场景 | 条件 | 结果 | 后续动作 |
|---|---|---|---|
| 单次失败 | `retryCount + 1 <= 2` | `Retry(delayMs)` | 退避 **1500ms**（第 1 次）/ **3000ms**（第 2 次）后重试**当前源** |
| 当前源耗尽 | `retryCount == 2` 且还有下一条源 | `SwitchSource(index + 1)` | 500ms 后加载下一条源，**新源计数归零** |
| 全部耗尽 | `retryCount == 2` 且没有下一条源 | `Fatal` | 进入 `Error(fatal=true)`，等待用户按 OK |

其它语义：

- `onSuccess(state)`：**计数清零、保持当前源**（不回到主源）；
- `onManualReset(state)`：回到主源（`sourceIndex = 0`）、计数归零，开启新一轮；
- `onChannelSwitched(candidateCount)`：切台时**丢弃旧频道的全部重试状态**，新频道从主源、计数 0 开始；
- 调度由桥接层的独立 `retryHandler = Handler(Looper.getMainLooper())` 负责，
  纯策略层不 sleep、不持有 Handler；
- 切台 / 手动重连 / `onStop` 都执行 `retryHandler.removeCallbacksAndMessages(null)`，
  且延迟回调执行前会校验"频道没变"，避免旧任务误伤新频道。

**要点**：仍然**没有网络请求、没有持久化、没有状态管理层**。
频道内容来自 APK 内的 `assets/channels.json`（6 个 CCTV HLS 源，阶段一硬编码值无损迁移）。
`backupUrls` / `headers` / `logo` / `epgId` 字段已在模型中预留，但**尚无消费方**。

### 4. `assets/channels.json` Schema

```jsonc
{
  "version": 1,                       // 可选，缺省为 1
  "categories": [                     // 分类数组
    {
      "id": "news",                  // 必填；为空则丢弃该分类
      "name": "新闻",                 // 可选；为空则回退为 id
      "channels": [
        {
          "id": "n1",                // 必填；为空则丢弃该频道
          "name": "综合新闻",         // 必填；为空则丢弃该频道
          "description": "24 小时滚动", // 可选，缺省 ""
          "streamUrl": "https://…",  // 必填；非 http/https 则丢弃该频道
          "backupUrls": [],          // 可选；仅保留 http/https 项
          "headers": {},             // 可选；请求头（当前无消费方）
          "logo": "",                // 可选；频道图标（当前无消费方）
          "epgId": "",               // 可选；EPG 标识（当前无消费方）
          "enabled": true            // 可选，缺省 true；false 则该频道被剔除
        }
      ]
    }
  ]
}
```

容错规则（全部在 `ChannelJsonParser` 内实现，可被纯 JVM 单测覆盖）：

| 输入情况 | 结果 |
|---|---|
| JSON 非法 / 残缺 / 空串 | 返回空列表，**不抛异常** |
| 顶层结构不符（`categories` 不是数组等） | 返回空列表 |
| 缺 `version` | 回退为 `1` |
| 缺 `description`/`backupUrls`/`headers`/`logo`/`epgId` | 回退为 `""` / `[]` / `{}` / `""` / `""` |
| 缺 `enabled` | 回退为 `true` |
| 分类缺 `name` | 回退为分类 `id` |
| 分类缺 `id` | 丢弃该分类 |
| 频道缺 `id` 或 `name` | 丢弃该频道 |
| `enabled = false` | 丢弃该频道 |
| `streamUrl` 为空 / `file://` / `javascript:` / `rtmp://` 等 | 丢弃该频道 |
| `backupUrls` 含非法项 | 仅保留合法的 http/https 项 |
| 分类内频道被全部过滤 | 丢弃该分类（最终列表不含空分类） |

### 5. 资源与 Manifest

| 项 | 事实 |
|---|---|
| `uses-feature` | `android.software.leanback` (required=true)、`android.hardware.touchscreen` (required=false) |
| 权限 | 仅 `android.permission.INTERNET` |
| Activity | `MainActivity` exported=true + `LEANBACK_LAUNCHER` + landscape；`PlayerActivity` exported=false + landscape + `Theme.AppCompat` |
| 主题 | `Theme.LiveTv` parent=`Theme.Leanback`，含 `defaultBrandColor`/`defaultSearchColor` |
| 颜色 | `primary`/`accent`/`brand_primary`(#1565C0)/`search_highlight` |
| 字符串 | `app_name`/`browse_title`/`channel_selected` 在用；**`row_news`/`row_sports` 自阶段二起不再被引用**（行标题改由 JSON 的分类 `name` 提供）；阶段三新增 `player_loading`/`player_buffering`/`player_retrying`/`player_switching_source`/`player_error_*`/`player_error_hint`/`player_switched_to`/`player_no_channel` |
| 布局 | `activity_main.xml` = 单个 FrameLayout；`activity_player.xml` = FrameLayout + `PlayerView` + `channel_name` + `status_text`（阶段三新增：加载/缓冲/重试提示）+ `error_text`（阶段三起改由 `strings.xml` 提供） |
| assets | `channels.json`（默认频道配置，随 APK 打包） |
| 图标 | `mipmap-anydpi-v26/ic_launcher.xml`（自适应图标）+ `drawable/ic_launcher_foreground.xml` + `drawable/app_banner.xml`（layer-list 纯色） |

---

## 第二部分：现有技术债（带证据）

| # | 技术债 | 证据 | 影响 |
|---|---|---|---|
| 1 | ~~业务数据硬编码在 UI 层~~ **阶段二已修复** | 原 `MainBrowseFragment.sampleNewsChannels/sampleSportsChannels` 已删除；现由 `LocalAssetChannelSource` 读 `assets/channels.json` | 数据与 UI 解耦，可换源、可测试 |
| 2 | ~~播放器生命周期与 API 24+ 推荐模式不一致~~ **阶段一已修复** | `onStart()` 初始化 / `onStop()` 释放，`initializePlayer()` 防重复初始化 | 已对齐 Android API 24+ 推荐模式 |
| 3 | ~~释放后未断开 PlayerView 绑定~~ **阶段一已修复** | `releasePlayer()` 现先 `binding.playerView.player = null` 再 `release()` | 视图不再持有已释放的 ExoPlayer |
| 4 | ~~无测试基础设施~~ **阶段一已修复，阶段二/三扩充** | `app/src/test` 现有 10 个测试类 / **77 个用例**（阶段二 22 个 + 阶段三新增 55 个） | 解析、过滤、重试、切台、状态流转已可回归 |
| 4b | ~~Lint 报错导致构建失败~~ **阶段一已修复** | `PlayerActivity` 加 `@androidx.annotation.OptIn(UnstableApi::class)` | `lintDebug` 可通过 |
| 5 | ~~硬编码用户可见文案绕过资源系统~~ **阶段三已修复** | 原 `PlayerActivity` 的 `"播放失败: ${error.message}"` 已改为 `strings.xml` 的 `player_error_*` + `player_error_hint` | 可本地化，Lint `SetTextI18n` 已消除 |
| 6 | **使用已废弃的全屏 API** | `PlayerActivity.hideSystemUi()` 用 `systemUiVisibility`，已 `@Suppress("DEPRECATION")` | API 30+ 沉浸式行为不完整 |
| 7 | **播放页主题与主界面不统一** | Manifest 中 `PlayerActivity` 用 `@style/Theme.AppCompat`，主界面用 `Theme.LiveTv` | 风格割裂 |
| 8 | ~~无 CI、无格式约束~~ **阶段一已修复** | `.github/workflows/android-ci.yml` + `.editorconfig` | 改动有自动校验 |
| 9 | **卡片无图像** | `Channel.logo` 字段已预留但 `ChannelCardPresenter.onBindViewHolder` 只设 `titleText`/`contentText` | 视觉上只有文字卡片 |
| 10 | ~~无错误重试/无源切换~~ **阶段三已修复** | `RetryPolicy` + `PlayerManager` 实现每源 2 次重试（1500/3000ms 退避）与主备源自动切换 | 直播源抖动可自动恢复 |
| 11 | **release 未混淆且无自定义 ProGuard 规则** | `app/build.gradle.kts` + 空 `app/proguard-rules.pro` | 体积与安全基线低 |
| 12 | **`MainActivity` 使用 `FragmentActivity`** | 继承 `androidx.fragment.app.FragmentActivity` | 无法使用 AppCompat 主题能力 |
| 13 | **数据同步仍靠重新打包 assets** | `LocalAssetChannelSource` 固定读 `assets/channels.json` | 换源需要改包或后续引入导入/网络源 |
| 14 | **未使用的字符串资源** | `R.string.row_news` / `R.string.row_sports` 已无引用（Lint `UnusedResources`）；行标题改由 JSON 分类 `name` 提供 | 资源项成为残留，**保留未删**（避免扩大改动面），后续随 UI 文案整理一并清理 |
| 15 | **浏览页仍无加载/空态反馈** | `MainBrowseFragment.loadRows()` 在 `onCreate` 同步取数，取不到内容时页面为空且无提示 | 用户无法区分"无频道"与"加载失败"（播放页已有状态提示） |
| 16 | **播放页未接 `PlayerView` 控制器按钮** | 切台只通过遥控器方向键；控制器里的"上一台/下一台"按钮未接入 `ChannelNavigator` | 触屏/控制器操作无法切台 |
| 17 | ~~HTTP 状态码识别依赖 Media3 错误码区间~~ **阶段 3.5 已修复** | `PlayerManager.findInvalidResponseCode()` 现在**遍历 cause 链**取 `HttpDataSource.InvalidResponseCodeException.responseCode`；`HTTP_STATUS_RANGE = 2000..2999` 降级为兜底防线 | 状态码来自真实 HTTP 响应，不再依赖 Media3 内部错误码语义 |
| 18 | ~~切台提示不自动消失~~ **阶段 3.5 已修复** | `PlayerActivity.showChannelSwitchedHint()` 用 `lifecycleScope.launch { delay(3000) }` 自动隐藏 `statusText`；显示新提示前 `cancel()` 旧 Job，`onDestroy()` 再兜底取消 | 短时提示不再长期遮挡画面 |

---

## 第三部分：演进方向（**尚未实现**）

阶段二已落地 `data/` 与 `domain/` 的骨架，阶段三已落地 `player/` 的纯策略层 + 桥接层。
后续在此骨架上扩展，**不引入 Hilt/Dagger，不拆模块**。

| # | 方向 | 落点 | 备注 |
|---|---|---|---|
| 1 | 多数据源聚合 | `DefaultChannelRepository` | 聚合 assets / 网络 / 用户导入的 M3U，UI 层无需改动 |
| 2 | M3U/M3U8 解析器 | `data/parser/` 新增 `M3uParser` | 与 `ChannelJsonParser` 同为纯函数，可直接复用 L1 单测 |
| 3 | ~~主备源切换（Failover）~~ **阶段三已完成** | `player/RetryPolicy` + `player/PlayerManager` | 消费 `Channel.backupUrls`；主源重试耗尽后按顺序切备用源 |
| 4 | ~~自定义 HTTP 请求头~~ **阶段三已完成接线** | `Channel.headers` → `DefaultHttpDataSource.Factory.setDefaultRequestProperties` | 请求头走 DataSource 层，不走 `setMediaItem` |
| 5 | 频道图标 | `Channel.logo` + `ChannelCardPresenter` | 字段已预留，需引入图片加载 |
| 6 | 浏览页空态 / 加载态 | `MainBrowseFragment` + `domain/` 状态类 | 解决技术债 #15；播放页的状态流转阶段三已完成 |
| 7 | 持久化 | `data/` 引入轻量存储 | 方案待定，**不预设 Room** |
| 8 | EPG | `Channel.epgId` | 字段已预留，格式与时区处理见 ROADMAP P2 |
| 9 | 播放器控制器按钮接切台 | `PlayerView` 控制器 + `ChannelNavigator` | 解决技术债 #16 |
| 10 | ~~切台提示自动消失~~ **阶段 3.5 已完成** | `PlayerActivity` 的 `statusText` 定时隐藏（`lifecycleScope` + `delay(3000)`） | 已解决技术债 #18 |
| 11 | 播放进度记忆 | `PlayerManager` 保存/恢复 `currentPosition` | 阶段一切台后重播的行为仍待评估 |
