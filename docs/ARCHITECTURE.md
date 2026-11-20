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
├── LiveTvApplication.kt                  # ← 阶段八升级：全局订阅仓库 + 订阅协调器
├── MainActivity.kt                       # 宿主 Activity
├── data/                                 # ← 阶段二新增：数据获取与解析（阶段四扩展 M3U）
│   ├── model/ChannelConfigDto.kt        # JSON 映射 DTO（version / categories / channels）
│   ├── parser/ChannelJsonParser.kt      # 纯文本 JSON → DTO/领域模型 的解析与校验
│   ├── parser/M3uPlaylistParser.kt      # ← 阶段四新增：纯文本 M3U → 领域模型 的解析与校验
│   ├── source/ChannelDataSource.kt       # 数据源接口
│   ├── source/LocalAssetChannelSource.kt # 读 assets/channels.json 并调用 Parser
│   ├── source/LocalM3uChannelSource.kt  # ← 阶段四新增：读 assets/sample.m3u 并调用 Parser
│   ├── remote/RemoteM3uFetcher.kt       # ← 阶段八新增：HttpURLConnection 远程 M3U 拉取
│   ├── mgtv/                            # ← 阶段九新增：MGTV 数据层（签名已还原，可直接解析）
│   │   ├── MgtvEndpoints.kt             # 目录/播放源 URL 构造（只含公开静态参数）
│   │   ├── MgtvCredentials.kt           # 凭据外部注入接口 + NoMgtvCredentials
│   │   ├── MgtvHttpClient.kt            # JDK HttpURLConnection 小客户端
│   │   ├── MgtvCatalogFetcher.kt        # 公开频道目录抓取（无需 Cookie/签名）
│   │   ├── HttpMgtvSourceFetcher.kt     # 播放源抓取（无合规凭据绝不发请求）
│   │   ├── MgtvJsonParser.kt            # JSONP 剥离 + 目录/播放源解析
│   │   ├── MgtvModels.kt                # 频道/分类/播放源模型
│   │   ├── MgtvSourceSelector.kt        # 白盒筛选（.m3u8 + isp2p=0 + hdcp=0 + https）
│   │   ├── MgtvStreamResolver.kt        # StreamResolver 实现
│   │   └── MgtvProvider.kt              # 目录 → 领域频道（mgtv:<cameraId>）
│   ├── subscription/SubscriptionStore.kt        # ← 阶段八新增：订阅持久化接口（纯 Kotlin）
│   ├── subscription/FileSubscriptionStore.kt    # ← 阶段八新增：SharedPreferences + filesDir 实现
│   ├── SubscriptionCoordinator.kt       # ← 阶段八新增：后台拉取 + 主线程回传
│   └── repository/DefaultChannelRepository.kt   # ← 阶段四升级：多数据源合并（阶段五加缓存）
│   └── repository/SubscriptionChannelRepository.kt # ← 阶段八新增：远程→缓存→内置 三级兜底
├── domain/                               # ← 阶段二新增：领域层
│   ├── model/Channel.kt                  # 领域模型（由阶段一 model/Channel.kt 升级迁移）
│   ├── model/ChannelCategory.kt          # 分类领域模型
│   ├── model/RefreshResult.kt            # ← 阶段八新增：刷新结果（成功/缓存兜底/内置兜底）
│   ├── repository/ChannelRepository.kt   # 仓库接口
│   ├── repository/SubscriptionRepository.kt # ← 阶段八新增：订阅仓库接口（继承 ChannelRepository）
│   └── stream/                           # ← 阶段九新增：通用播放会话解析（纯 Kotlin）
│       ├── StreamResolver.kt             # 解析抽象 + StaticStreamResolver
│       ├── ResolvedStream.kt             # 解析结果（url/headers/过期时间/MIME/Host 白名单）
│       ├── StreamResolveResult.kt        # Success/Failure + 错误枚举
│       ├── StreamRefreshCoordinator.kt   # 临期刷新 + 403 单次重解析 + 429 快速失败
│       ├── HeaderHostPolicy.kt           # 请求头 Host 白名单隔离
│       ├── StreamTypeDetector.kt         # HLS 识别（含无后缀地址）+ 显式 MIME
│       ├── RoutingStreamResolver.kt      # 按 `mgtv:` 前缀路由
│       └── Clock.kt                      # 可注入时钟
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
    ├── PlayerActivity.kt                 # 播放页（UI + 遥控器按键，不含播放器逻辑）
    └── mobile/                           # ← 阶段六新增：手机/平板触控端
        ├── MobileChannelList.kt          # 纯 Kotlin：行序列组装 + 网格列数计算
        ├── MobileChannelAdapter.kt       # RecyclerView.Adapter（分类标题 + 频道卡片两种 item）
        └── MobileMainActivity.kt         # 手机端主界面（RecyclerView + GridLayoutManager）
└── util/
    └── DeviceUtils.kt                    # ← 阶段六新增：TV / 触控设备形态判定
└── media/                                # ← 阶段九新增：Media3 请求头作用域隔离
    └── ScopedHeaderDataSourceFactory.kt  # 按目标 host 过滤请求头的 DataSource.Factory
```

| 类 | 职责 |
|---|---|
| `MainActivity` | **阶段六**：设备形态**分流入口**——`DeviceUtils.isTvDevice(this)` 为真则装载 Leanback `MainBrowseFragment`（并强制横屏），否则启动 `MobileMainActivity` 后 `finish()` |
| `LiveTvApplication`（阶段八） | 全局**订阅**仓库持有者：`by lazy` 装配 `SubscriptionChannelRepository`（内置 JSON + M3U 源、`RemoteM3uFetcher`、`FileSubscriptionStore`）；`channelRepository` 指向它供两端共用；另持有 `subscriptionCoordinator` |
| `MainBrowseFragment` | 浏览页：标题/品牌色，**通过 `ChannelRepository` 取分类列表**并组装 `ListRow`，处理卡片点击跳转播放 |
| `ChannelCardPresenter` | 渲染 `ImageCardView` 卡片（标题 + 描述） |
| `PlayerActivity` | 播放页：加载频道、渲染状态与提示、**遥控器按键映射**；不含 ExoPlayer 逻辑 |
| `Channel`（domain） | 领域模型：`id`、`name`、`description`、`streamUrl`、`backupUrls`、`headers`、`logo`、`epgId`、`enabled` |
| `ChannelCategory`（domain） | `id`、`name`、`channels`（保证非空） |
| `ChannelRepository`（domain） | 仓库接口：`getCategories(): List<ChannelCategory>`，**契约是不抛异常** |
| `ChannelDataSource`（data） | 数据源接口：`loadCategories(): List<ChannelCategory>` |
| `LocalAssetChannelSource`（data） | 读 `assets/channels.json` → 交给 `ChannelJsonParser` |
| `LocalM3uChannelSource`（data） | **阶段四**：读 `assets/sample.m3u` → 交给 `M3uPlaylistParser` |
| `DefaultChannelRepository`（data） | **阶段四**：聚合多个数据源，**按分类名合并同名分类**，单源异常只跳过该源；**阶段五**：加内存缓存（`enableCache` / `refresh()` / `invalidateCache()`） |
| `ChannelJsonParser`（data） | 纯函数解析 + 校验（URL 协议白名单、必填字段、disabled/空分类过滤） |
| `M3uPlaylistParser`（data，纯） | 纯函数解析 M3U：`#EXTINF` 属性提取（`tvg-id` / `tvg-logo` / `group-title`）、**引号感知逗号切分**、**`#EXTVLCOPT`/`#KODIPROP` 与行内属性 -> `Channel.headers`**、URL 校验、坏条目跳过 |
| `ChannelConfigDto` 等（data） | JSON 原始形状的 DTO，只做映射不做业务校验 |
| `PlaybackState`（player，纯） | 状态：`Idle` / `Loading` / `Buffering` / `Playing` / `Paused` / `Error(error, fatal)` |
| `PlaybackEvent`（player，纯） | 事件：`Prepare` / `Buffering` / `Ready` / `Playing` / `Paused` / `Failed` / `Fatal` / `ManualReset` / `Released` |
| `PlaybackReducer`（player，纯） | `reduce(state, event)`：状态转换规则（非法转换保持原状态） |
| `PlaybackError`（player，纯） | 错误分类：`Network` / `HttpStatus(code)` / `SourceParsing` / `Timeout` / `Unknown` |
| `PlaybackErrorMapper`（player，纯） | `map(responseCode, causeKind)`：原始字段 → `PlaybackError` |
| `RetryPolicy`（player，纯） | 重试与主备源状态机：候选源生成、`RetryState`、三种 `RetryOutcome` |
| `ChannelNavigator`（player，纯） | 扁平频道列表的定位（id → url 回退）与循环切台 |
| `PlayerManager`（player，桥接） | ExoPlayer 生命周期与复用、DataSource 请求头/超时、重试调度、Media3 事件翻译 |
| `DeviceUtils`（util） | **阶段六**：TV 形态判定。`isTvDevice(context)` 为 Android 门面；`isTvDevice(uiModeType, hasLeanbackFeature)` 为**纯函数**（可直接 JVM 单测） |
| `MobileChannelList`（ui/mobile，纯） | **阶段六**：纯函数——`buildRows(categories)` 把分类展开成"标题 + 卡片"行序列（跳过空分类）。**阶段十**：`gridSpanCount(dp)` 改为按 `MIN_CARD_WIDTH_DP=190` 动态算列（上限 5）、不再写死阈值；新增 `buildRailItems()`（左侧分类栏）与 `sanitizeSubtitle()`（过滤技术字段/重复名，无有效信息返回 null） |
| `MobileChannelAdapter`（ui/mobile） | **阶段六**：`RecyclerView.Adapter`，两种 item（`CategoryHeader` 跨整行 / `ChannelCard` 占一格），点击回调交给 Activity |
| `MobileMainActivity`（ui/mobile） | **阶段六**：手机/平板主界面；**阶段七**：分类胶囊 + 总频道数；**阶段十**：横屏优先双栏——左侧 `rv_categories` 分类栏（`MobileCategoryRailAdapter`）+ 右侧单行顶栏（`layout_top_header`，滚动时 `translationY` 收起）+ 自适应网格 `rv_channels`；`applyImmersiveSystemBars()` 隐藏状态栏并在 `onResume`/`onWindowFocusChanged` 重入，`applyCutoutSafeInsets()` 只对左右挖孔补 padding；D-Pad 链路：分类项 RIGHT→网格、卡片 UP→`mobile_refresh`（`OnKeyListener` 显式接管，因为 LayoutManager 会先拦截方向键）、卡片 LEFT→分类栏；「上次播放」以 `SharedPreferences("livetv_mobile_ui")` 记忆并在卡片上以强调色描边高亮 |
| `ChannelDrawerModel`（ui/mobile，纯） | **阶段七**：播放页"频道列表"抽屉的行序列——`buildRows(categories, currentChannelId)` 分组并标记当前频道，`currentRowIndex` / `currentChannelIndex` 供自动滚动定位 |
| `ChannelDrawerAdapter`（ui/mobile） | **阶段七**：抽屉的 `RecyclerView.Adapter`（分类标题 / 频道条目，当前频道显示"播放中"） |
| `RemoteM3uFetcher`（data） | **阶段八**：远程 M3U 拉取——仅 `http`/`https`，连接 5000 ms / 读取 8000 ms / 响应上限 2 MB，仅 HTTP 200 成功，UTF-8 解码；失败归一为 `HttpError` / `NetworkError` / `InvalidUrl` / `SizeExceeded` |
| `SubscriptionStore`（data，纯接口） | **阶段八**：订阅持久化接口——`getUrl()` / `saveUrl()` / `readPlaylist()` / `writePlaylist()` / `clearPlaylist()`；测试用内存 fake |
| `FileSubscriptionStore`（data） | **阶段八**：URL 存 `SharedPreferences`，播放列表存 `filesDir/remote_playlist.m3u`，临时文件 + rename 原子写 |
| `SubscriptionCoordinator`（data） | **阶段八**：单线程 `ExecutorService` 串行拉取 + 主线程 `Handler` 回传；不引入 Coroutines |
| `SubscriptionChannelRepository`（data） | **阶段八**：远程订阅 → 本地有效缓存 → 内置 assets 三级兜底；`@Volatile` 快照 + 写操作加锁；换 URL 作废旧缓存 |
| `SubscriptionRepository`（domain） | **阶段八**：在 `ChannelRepository` 之上追加 `refreshSubscription()` / `updateSubscriptionUrl()` / `findChannelById()` / `getSubscriptionUrl()` |
| `RefreshResult`（domain） | **阶段八**：刷新结果——`BuiltinOnly` / `RemoteLoaded` / `FallbackToCache` / `FallbackToBuiltin`，`channelCount` 一律是**频道总数** |
| `StreamResolver` / `StaticStreamResolver`（domain/stream，纯） | **阶段九**：播放会话解析抽象。静态实现返回 `channel.streamUrl` + `channel.headers`、`expiresAtMs = null`、`allowedHeaderHosts = emptySet()`（普通 IPTV 行为不变） |
| `ResolvedStream`（domain/stream，纯） | **阶段九**：`url` / `headers` / `expiresAtMs` / `mimeType` / `allowedHeaderHosts` / `definitionLabel`；`DEFAULT_REFRESH_LEAD_MS = 60_000L` |
| `StreamRefreshCoordinator`（domain/stream，纯） | **阶段九**：临期（< 60 s）主动刷新；403 最多重解析 1 次（`MAX_FORBIDDEN_REFRESH_ATTEMPTS = 1`），再次 403/429 立即 `FastFail`；其他状态码 `DelegateToRetryPolicy` |
| `HeaderHostPolicy`（domain/stream，纯） | **阶段九**：按 host 精确 / `.` 子域匹配过滤请求头，拦截 `evilmgtv.com` 式后缀伪造；白名单为空表示不限制 |
| `StreamTypeDetector`（domain/stream，纯） | **阶段九**：`MIME_HLS = "application/x-mpegURL"`；识别无 `.m3u8` 后缀的 HLS 路由/参数，排除 `.mp4/.mkv/.flv/.ts/.mp3/.aac/.webm` |
| `RoutingStreamResolver`（domain/stream，纯） | **阶段九**：`mgtv:` 前缀 → MGTV 解析器，其余 → 静态解析器 |
| `MgtvStreamResolver`（data/mgtv） | **阶段九**：`StreamResolver` 实现；`expiresAtMs = clock.nowMs() + urlExpireDuration*1000`；`allowedHeaderHosts = setOf("mgtv.com")`；默认 fetcher 为真实 HTTP 实现 |
| `MgtvSourceSelector`（data/mgtv，纯） | **阶段九**：白盒筛选 `format=.m3u8` + `isp2p==0` + `hdcp==0` + 合法 `https://`；无合规源返回 `UNSUPPORTED_SOURCE`，**不降级 P2P** |
| `HttpMgtvSourceFetcher`（data/mgtv） | **阶段九**：无外部合规凭据时**不发任何请求**，直接 `AuthUnavailable`；不实现签名 |
| `ScopedHeaderDataSourceFactory`（media） | **阶段九**：包装 `HttpDataSource.Factory`，每次 `open(DataSpec)` 按目标 host 过滤请求头（`DataSpec.withAdditionalHeaders`） |

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
  │     channelRepository = (requireActivity().application as LiveTvApplication).channelRepository
  │                            ↑ 阶段五：全局单例（带内存缓存），UI 层不再自行拼装数据源
  │     channelRepository.getCategories()               ← 领域接口
  │       └─ SubscriptionChannelRepository.getCategories()   ← 阶段八：远程订阅 → 缓存 → 内置三级兜底
  │           │     （内部仍复用 DefaultChannelRepository 聚合内置源：逐源 try/catch，
  │           │       单源失败只跳过该源；命中缓存则直接返回）
  │           ├─ LocalAssetChannelSource.loadCategories()
  │           │     ├─ assets.open("channels.json").use { readBytes().toString(UTF_8) }   ← 失败 → emptyList()
  │           │     └─ ChannelJsonParser.parse(json)     ← 纯函数，失败 → emptyList()
  │           │           ├─ parseToDto(json)             ← JSONObject 解析，失败 → null
  │           │           ├─ 过滤 enabled=false 的频道
  │           │           ├─ 校验 streamUrl 为 http/https，剔除非法频道
  │           │           ├─ 清洗 backupUrls（仅保留 http/https）
  │           │           └─ 丢弃过滤后为空的分类
  │           ├─ LocalM3uChannelSource.loadCategories()      ← 阶段四
  │           │     ├─ assets.open("sample.m3u").use { readBytes().toString(UTF_8) }      ← 失败 → emptyList()
  │           │     └─ M3uPlaylistParser.parse(m3u)          ← 纯函数，失败 → emptyList()
  │           │           ├─ 扫描 #EXTINF 行，提取属性与显示名（引号感知逗号切分）
  │           │           ├─ 收集 #EXTVLCOPT / #KODIPROP 与行内属性 -> HTTP 请求头
  │           │           ├─ 向下取第一个合法 http/https 行作为 URL
  │           │           ├─ 按 group-title 分组（缺失 → "未分类"）
  │           │           └─ 丢弃非法 URL / 空名称的频道
  │           └─ 合并：按分类名归并同名分类（先到先得 id），同名分类内不重复频道
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
频道内容来自 APK 内的 `assets/channels.json`（6 个 CCTV HLS 源，阶段一硬编码值无损迁移）
与 `assets/sample.m3u`（阶段四新增的 M3U 示例源，4 个频道 / 2 个分类），两者在仓库层按分类名合并。
`backupUrls` / `headers` / `logo` / `epgId` 字段中，**`logo` 与 `epgId` 已由 M3U 解析器填充**
（`tvg-logo` / `tvg-id`），但 Presenter 仍未绑定图标；`backupUrls` / `headers` 的消费方是阶段三的播放器层。

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

### 4.1 `assets/sample.m3u` 与 M3U 解析规则（阶段四）

示例文件（`app/src/main/assets/sample.m3u`，2 个分类 / 4 个频道）：

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="cctv1" tvg-logo="https://example.com/logo/cctv1.png" group-title="新闻",CCTV-1 综合
https://example.com/live/cctv1.m3u8
```

`M3uPlaylistParser` 的提取策略：

| 环节 | 规则 |
|---|---|
| 入口 | 逐行扫描（`lineSequence()`），空行直接跳过 |
| 条目起点 | 仅 `#EXTINF` 开头的行开启一个"待定条目"；新的 `#EXTINF` 会**作废**上一个还没等到 URL 的条目（连带其待定请求头一起丢弃） |
| 属性区 | `#EXTINF` 去掉前缀后按**引号外**的第一个逗号切分：逗号前是属性区（并去掉 `:-1` 时长前缀），逗号后是显示名 |
| 引号感知切分 | `indexOfTopLevelComma()` 逐字符扫描，跳过双引号 `"..."` / 单引号 `'...'` 内部的逗号；引号未闭合时按"到行尾都在引号内"处理（找不到分隔逗号 -> 该条目作废） |
| 显示名 | 逗号后的原文 `trim()`；允许包含逗号（只按引号外的第一个逗号切分）；为空则丢弃该频道 |
| 属性提取 | 正则 `([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"\|'([^']*)'\|([^\s"']+))`，**双引号 / 单引号 / 无引号三种写法都支持**，属性顺序任意、之间可有多个空白 |
| 属性映射 | `tvg-id` → `Channel.epgId`（同时作为 `Channel.id`，缺失时用频道名兜底）；`tvg-logo` → `Channel.logo`；`group-title` → 分类名（缺失 → `未分类`） |
| 请求头（指令行） | `#EXTVLCOPT:` / `#KODIPROP:` 指令 → `User-Agent` / `Referer` / `Origin`；键名大小写不敏感，值剥离外层引号，空值忽略；`http-referrer` 与 `http-referer` 等价 |
| 请求头（KODIPROP 命名空间） | `#KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Kodi` 形态下，**再往后拆一层 `=`**，真正的 Header 键在最后一层 |
| 请求头（行内属性） | `#EXTINF` 行内的 `user-agent` / `http-user-agent` / `referer` / `http-referer` / `http-referrer` 等同样映射为标准 Header 键 |
| 请求头优先级 | **指令行覆盖行内属性**（指令在行内属性之后解析）；同名指令后出现者覆盖先出现者 |
| 状态清理 | 新 `#EXTINF` 到来时**丢弃**上一条待定条目的全部请求头，杜绝"串台"污染 |
| URL | 该 `#EXTINF` 之后**向下第一个**非空、非注释行；中间夹杂 `#EXTVLCOPT` 等注释行不影响配对 |
| URL 校验 | 复用 `ChannelJsonParser.isValidStreamUrl`：只接受 `http://` / `https://`（忽略大小写）；非法则丢弃该频道 |
| 异常安全 | 任何输入（含空串、乱码、引号不闭合）都**不抛异常**，最多丢弃坏条目 |

多数据源合并（`DefaultChannelRepository`）：

| 规则 | 说明 |
|---|---|
| 归并键 | 分类 `name`（JSON 的 `name` 与 M3U 的 `group-title` 同名即合并） |
| 分类 id | 合并后沿用**第一个**提供该分类的数据源的 id |
| 顺序 | 按数据源构造顺序、源内保持原有顺序 |
| 去重 | 同名分类内 `id` + `streamUrl` 完全相同的频道只保留先出现的那个 |
| 容错 | 单个数据源抛异常只跳过该源，其余源照常合并 |
| 内存缓存 | **阶段五**：首次 `getCategories()` 聚合后缓存结果，后续直接返回；`refresh()` 强制重算并更新缓存，`invalidateCache()` 只作废缓存；`enableCache = false` 时每次都重算 |

### 5. 资源与 Manifest

| 项 | 事实 |
|---|---|
| `uses-feature` | **阶段六**：`android.software.leanback` **required=false**（改为双端通用）、`android.hardware.touchscreen` (required=false) |
| 权限 | 仅 `android.permission.INTERNET` |
| Activity | **阶段六**：`MainActivity` exported=true + `LAUNCHER` + `LEANBACK_LAUNCHER`（单一入口，双端各出现一个图标）+ **不再锁横屏**（横屏只在 TV 分支用 `requestedOrientation` 运行时设置）；`MobileMainActivity` exported=false + `Theme.LiveTv.Mobile`；`PlayerActivity` exported=false + landscape + `Theme.AppCompat` |
| Application | **阶段五**：`android:name=".LiveTvApplication"`（持有全局 `ChannelRepository`） |
| 主题 | `Theme.LiveTv` parent=`Theme.Leanback`，含 `defaultBrandColor`/`defaultSearchColor`；**阶段六**新增 `Theme.LiveTv.Mobile` parent=`Theme.AppCompat.NoActionBar`（深色，不启用 Leanback 焦点体系） |
| 颜色 | `primary`/`accent`/`brand_primary`(#1565C0)/`search_highlight`；**阶段六**新增 `mobile_background`/`mobile_card_background`/`mobile_cover_background`/`mobile_text_primary`/`mobile_text_secondary` |
| 字符串 | `app_name`/`browse_title`/`channel_selected` 在用；**`row_news`/`row_sports` 自阶段二起不再被引用**（行标题改由 JSON 的分类 `name` 提供）；阶段三新增 `player_loading`/`player_buffering`/`player_retrying`/`player_switching_source`/`player_error_*`/`player_error_hint`/`player_switched_to`/`player_no_channel`；**阶段六**新增 `mobile_empty_channels`/`mobile_channel_cover_desc` |
| 布局 | `activity_main.xml` = 单个 FrameLayout；`activity_player.xml` = FrameLayout + `PlayerView` + `channel_name` + `status_text`（阶段三新增：加载/缓冲/重试提示）+ `error_text`（阶段三起改由 `strings.xml` 提供）；**阶段六**新增 `activity_mobile_main.xml`/`item_mobile_channel.xml`/`item_mobile_category_header.xml` |
| drawable | **阶段六**新增 `ic_channel_placeholder.xml`（本地矢量封面占位）、`bg_mobile_channel_card.xml`（圆角卡片底） |
| assets | `channels.json`（默认频道配置，随 APK 打包）+ `sample.m3u`（阶段四新增：M3U 示例源） |
| 图标 | `mipmap-anydpi-v26/ic_launcher.xml`（自适应图标）+ `drawable/ic_launcher_foreground.xml` + `drawable/app_banner.xml`（layer-list 纯色） |

---

### 6. 播放页按键与控制器（阶段 5.5 真机校准）

真机实测暴露了一个**单测在结构上无法覆盖**的层次问题，这里作为长期约束记录：

| 事实 | 说明 |
|---|---|
| Media3 抢先消费方向键 | `PlayerView.dispatchKeyEvent` 在"方向键 + 控制器未完全可见"时调用 `maybeShowController()` 并返回 `true`，`Activity.onKeyDown` 永远收不到 |
| 因此按键拦截在 `dispatchKeyEvent` | 切台 / OK 重试都在视图树分发之前处理 |
| 控制器不再自动弹出 | `controllerAutoShow = false`；Media3 会在切换媒体源后异步弹起控制器，把方向键吞掉，导致连续换台只切一台 |
| 切台闸门不用控制器可见性 | 改用"用户是否主动呼出过控制器"标记（`isControllerOpenedByUser`），避免受 Media3 异步弹窗与隐藏动画影响 |
| 状态提示的写入顺序有讲究 | `showChannelSwitchedHint()` 必须在 `playerManager.switchChannel()` **之后**，否则被同步派发的 Loading 文案覆盖 |

---

### 7. 双端 UI 分流（阶段六新增）

Leanback 的 `ImageCardView` 是**固定尺寸**（`ChannelCardPresenter` 里写死 313dp × 176dp）的横版卡片，
放在手机竖屏上会排版错位。因此采用"入口分流、两套 UI、一套底层"的结构：

```
                     ┌──────────────────────────────────────────┐
   am start          │ MainActivity（唯一 LAUNCHER / LEANBACK   │
   ───────────────►  │ _LAUNCHER 入口）                        │
                     └───────────────┬──────────────────────────┘
                                     │ DeviceUtils.isTvDevice(this)
                    ┌────────────────┴────────────────┐
                    ▼                                 ▼
        ┌───────────────────────┐        ┌────────────────────────────┐
        │ TV 分支（真）          │        │ 触控分支（手机 / 平板）       │
        │ requestedOrientation  │        │ startActivity(              │
        │   = LANDSCAPE         │        │   MobileMainActivity)      │
        │ setContentView(       │        │ finish()                   │
        │   activity_main)      │        │                            │
        │ + MainBrowseFragment  │        │ RecyclerView +              │
        │   （Leanback 焦点体系） │        │   GridLayoutManager        │
        └───────────┬───────────┘        └──────────────┬─────────────┘
                    │                                   │
                    └───────────────┬───────────────────┘
                                    ▼
              LiveTvApplication.channelRepository（数据）
                                    │
                                    ▼
              PlayerActivity（播放页，双端完全共用）
```

**判定规则**（`DeviceUtils.isTvDevice(uiModeType, hasLeanbackFeature)`）：
`uiModeType == Configuration.UI_MODE_TYPE_TELEVISION || hasLeanbackFeature`。
两个信号取或：部分电视盒子不报告 television UI mode，但一定有 Leanback 特性；手机则一定没有该特性。
常量 `UI_MODE_TYPE_TELEVISION = 4` 在 `DeviceUtils` 内**本地显式定义**，让纯函数不依赖框架常量即可 JVM 单测。

**手机端网格列数**（`MobileChannelList.gridSpanCount(screenWidthDp)`，纯函数）：

| 有效宽度 | 列数 |
|---|---|
| `>= 840dp` | 4 |
| `>= 600dp` | 3 |
| 其余 | 2 |

下限取 2：单列在手机上卡片过宽、滚动过长。分类标题用 `SpanSizeLookup` 跨满整行，频道卡片各占一格。

**宽度换算（px → dp）**：`MobileMainActivity.screenWidthDp()` 用 `MobileChannelList.pxToDp(metrics.widthPixels, metrics.density)`
（纯函数，可单测）。**不能用** `TypedValue.applyDimension(COMPLEX_UNIT_DIP, px, metrics)`——那个 API 的方向是 dp → px，
拿它处理 px 会再乘一次 density，把有效宽度放大（例如 2780px @3.5x 会被算成 9730dp，直接跳到 4 列）。
正确换算是除法：2780px ÷ 3.5 = 794dp → 3 列；1264px ÷ 3.5 = 361dp → 2 列。

**两端共用与隔离**：

| 共享 | 隔离 |
|---|---|
| `LiveTvApplication.channelRepository`（数据） | 浏览页 UI（Leanback `ListRow` vs `RecyclerView` 网格） |
| `PlayerActivity` + `PlayerManager`（播放、重试、Failover） | 主题（`Theme.Leanback` vs `Theme.AppCompat`） |
| `Channel` / `ChannelCategory` 领域模型 | 卡片呈现（`ImageCardView` vs 本地矢量占位封面） |
| 三个 Intent Extra（`EXTRA_CHANNEL_ID` / `EXTRA_CHANNEL_NAME` / `EXTRA_STREAM_URL`） | 屏幕方向（TV 强制横屏 / 手机跟随系统） |
| 切台状态流（`ChannelNavigator` + `PlayerManager.switchChannel`） | 播放页操作方式（TV 遥控器 D-Pad / 手机触控浮层 + 抽屉） |

**手机端卡片（阶段七重做）**：不再是 16:9 大封面，而是紧凑信息卡——
36dp 圆角徽章（分类强调色 + 1～3 字简标，如 `CCTV-1 综合` → `C1`；取不出有效字符时兜底 `TV`）、右上角两位序号、
频道名（单行省略）、副标题（描述 → 流地址文件名主干 → 频道 id 三级兜底，保证行高一致）。
徽章配色由 `MobileChannelList.categoryAccentIndex(categoryName)` 稳定映射，同一分类永远同色。

**播放页触控浮层（阶段七）**：`isTouchDevice = !DeviceUtils.isTvDevice(this)` 才启用，
所有触控视图在 XML 里默认 `GONE`，TV 端完全不会创建焦点或拦截点击：
顶栏「返回 / 频道名 · 位置/总数 / 频道列表」、底栏「上一台 / 重试（仅错误态）/ 下一台」。
轻触画面切换浮层显隐，5 秒无操作自动隐藏（错误态不自动隐藏，"重试"必须点得到）；
`postDelayed` 的 runnable 在 `onDestroy` 里 `removeCallbacks`，不会持有 Activity。
抽屉是右侧 280dp 面板 + 遮罩：按分类列出全部频道、当前频道标"播放中"并自动滚动定位；
点条目先 `navigator.locate()` 再走与遥控器完全相同的 `switchToChannel(channel)`，
因此抽屉选台之后"上一台/下一台"的连续性也正确；BACK 优先关抽屉，再关控制器，最后才退出。

> **阶段六七未做**：手机端不加载远程频道 Logo（`Channel.logo` 已有字段但无消费方，卡片用徽章代替封面图）；
> 引入图片加载库（Glide/Coil 等）被明确排除，避免为占位图引入重型依赖。
> TV 分支**只做了代码级保证与触控视图隔离**，未在真实 Android TV 硬件上回归。

### 8. 远程 M3U 订阅与三级兜底（阶段八新增）

仓库的数据来源从"只有内置 assets"扩展为**远程订阅 → 本地有效缓存 → 内置 assets** 三级兜底。
TV 端与手机端共用同一个 `SubscriptionChannelRepository`，两端都能看到远程频道。

```
LiveTvApplication（Application）
 ├─ subscriptionRepository: SubscriptionRepository (by lazy)
 │    └─ SubscriptionChannelRepository(
 │         builtinSources = [LocalAssetChannelSource(assets), LocalM3uChannelSource(assets)],
 │         fetcher = RemoteM3uFetcher(),          ← HttpURLConnection，纯 JDK
 │         store   = FileSubscriptionStore(this))  ← SharedPreferences + filesDir
 ├─ channelRepository: ChannelRepository = subscriptionRepository   ← 两端共用
 └─ subscriptionCoordinator: SubscriptionCoordinator                ← 后台拉取 + 主线程回传
```

**拉取层 `RemoteM3uFetcher`（纯 Kotlin，无第三方依赖）**：

| 约束 | 值 | 位置 |
|---|---|---|
| 允许协议 | 仅 `http` / `https`（忽略大小写） | `RemoteM3uFetcher.fetch()` |
| 连接超时 | 5000 ms | `CONNECT_TIMEOUT_MS` |
| 读取超时 | 8000 ms | `READ_TIMEOUT_MS` |
| 响应体上限 | 2 MB，超出即 `SizeExceeded` | `MAX_RESPONSE_BYTES` |
| 成功条件 | 仅 HTTP 200，按 UTF-8 解码 | `FetchResult.Success(content)` |

失败被归一为 `HttpError(code)` / `NetworkError(reason)` / `InvalidUrl(reason)` / `SizeExceeded`，
非法协议（`file://`、`ftp://`、空串）在**建立连接之前**就被拒绝。

**持久化 `FileSubscriptionStore`**：订阅 URL 存 `SharedPreferences("livetv_subscription")`，
播放列表原文存 `context.filesDir/remote_playlist.m3u`，写入走"临时文件 + rename"保证原子性。

**兜底语义（`SubscriptionChannelRepository.refreshSubscription()`）**：

| 情况 | 结果 | 副作用 |
|---|---|---|
| 未配置订阅 URL | `RefreshResult.BuiltinOnly(n)` | 切回内置 assets |
| 拉取成功且解析出 ≥1 个频道 | `RefreshResult.RemoteLoaded(n)` | 落盘缓存 + 替换快照 |
| 拉取失败 / 解析为空，但有可解析缓存 | `RefreshResult.FallbackToCache(n, reason)` | 用缓存覆盖快照，**不清空**现有列表 |
| 拉取失败 / 解析为空，且无缓存 | `RefreshResult.FallbackToBuiltin(n, reason)` | 回退内置 assets |

`n` 一律是**频道总数**（各分类频道数之和），不是分类数。
HTTP 200 但内容为空/无有效频道时按失败处理——**绝不因为一次坏响应把用户已有列表清空**。
更换订阅 URL 会立即作废旧缓存（URL 路径大小写敏感，比较时不做大小写折叠）。

**异步**：`SubscriptionCoordinator` 用单线程 `ExecutorService` 串行执行拉取，
结果经主线程 `Handler` 回传，避免两个刷新交错改快照；不引入 Coroutines。

### 9. Debug 明文放行隔离（阶段八新增）

`targetSdk = 34` 下 Android 默认禁止明文 HTTP。为了让 Debug 包能连本机教学沙箱，
放行**只做在 Debug 源集**：

| 源集 | 文件 | 内容 |
|---|---|---|
| `app/src/debug/` | `AndroidManifest.xml` | `<application android:networkSecurityConfig="@xml/network_security_config" />` |
| `app/src/debug/res/xml/` | `network_security_config.xml` | `base-config cleartextTrafficPermitted="false"`，`domain-config` 仅放行 `127.0.0.1` 与 `localhost`（`includeSubdomains="false"`） |
| `app/src/main/` | — | **没有任何** `usesCleartextTraffic` / `networkSecurityConfig` / `cleartextTrafficPermitted` |

Release 构建不包含 `app/src/debug/`，因此正式包既不放行明文，也不会出现 `127.0.0.1` 例外。
手机端「订阅源」对话框里的「填入本地 SecLab」入口也只在
`(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0` 时出现。

> **Lint 注意**：manifest 合并以优先级更高的 Debug 文件为基准文档，Lint 的
> `MissingLeanbackLauncher` / `MissingLeanbackSupport` / `ImpliedTouchscreenHardware`
> 三条 TV 检查会把根 `<manifest>` 元素级问题定位到 `app/src/debug/AndroidManifest.xml`。
> 真正的 `leanback` / `touchscreen` 声明（`required="false"`）与 `LEANBACK_LAUNCHER` 入口
> 都在 `app/src/main/AndroidManifest.xml` 且完整，因此 Debug 覆盖文件用
> `tools:ignore="MissingLeanbackLauncher,MissingLeanbackSupport,ImpliedTouchscreenHardware"`
> 显式忽略这三条与覆盖无关的检查；**不要**为了消错去改 main 源集里已经正确的 TV 声明。

### 10. 401/403/429 快速失败（阶段八新增）

`401`（未授权）、`403`（拒绝访问 / 签名过期 / 防盗链拦截）、`429`（风控限流）属于
**重试也不会变好**的状态，因此不消耗 1500 ms / 3000 ms 的退避重试：

| 状态 | 行为 |
|---|---|
| 401 / 403 / 429，当前源还有备用源 | 取消待执行重试 → 立即（0 ms）切下一个源，重试计数清零 |
| 401 / 403 / 429，没有备用源 | 直接 `PlaybackEvent.Fatal` → `PlaybackState.Error(fatal = true)`，等待用户手动重置 |
| 5xx / 网络中断 / 超时 / 源解析失败 | 维持原策略：每源最多 2 次重试，退避 1500 ms / 3000 ms，耗尽后切源，全耗尽才 `Fatal` |

判定逻辑是纯函数，位于 `player/RetryPolicy.kt` 的 `isFastFailHttpStatus(code)` / `isFastFail(error)`，
由 `PlayerManager.handleFastFail()` 消费；`PlayerManager` 里的快速失败分支**不派发 `Failed`**，
因为 `PlaybackReducer` 一旦进入 `Error` 就会忽略后续 `Prepare` 回调。

错误文案（`PlayerActivity.buildErrorText()`）：

| HTTP 状态 | 文案 |
|---|---|
| 403 | `访问被拒绝或签名已过期（HTTP 403）` |
| 429 | `请求过于频繁，触发风控限流（HTTP 429）` |
| 其他（含 401、5xx） | `服务器返回错误（HTTP %d）` |

### 11. 动态播放会话与 MGTV 接入（阶段九新增）

这一层把「播放会话解析」从 `PlayerManager` 里彻底抽出来，普通 IPTV 与动态源走同一条出口。

#### 11.1 通用抽象（`domain/stream/`）

| 类型 | 事实 | 出处 |
|---|---|---|
| `StreamResolver` | `fun resolve(channel, preferredDefinition): StreamResolveResult` | `domain/stream/StreamResolver.kt` |
| `StaticStreamResolver` | 普通 IPTV / M3U / JSON：`url = channel.streamUrl.trim()`，`headers = channel.headers`，`expiresAtMs = null`，`allowedHeaderHosts = emptySet()` | 同上 |
| `ResolvedStream` | `url / headers / expiresAtMs / mimeType / allowedHeaderHosts / definitionLabel`；`DEFAULT_REFRESH_LEAD_MS = 60_000L` | `domain/stream/ResolvedStream.kt` |
| `StreamResolveResult` | `Success(stream)` / `Failure(error, message)`；错误枚举含 `UNSUPPORTED_SOURCE`、`HTTP_FORBIDDEN`、`HTTP_NOT_FOUND`、`HTTP_RATE_LIMITED`、`PARSE_ERROR`、`NETWORK_ERROR`、`AUTH_UNAVAILABLE` | `domain/stream/StreamResolveResult.kt` |
| `StreamRefreshCoordinator` | 起播前临期刷新 + 403 单次重解析 + 429 立即快速失败 + 其他状态码交回播放器策略 | `domain/stream/StreamRefreshCoordinator.kt` |
| `HeaderHostPolicy` | 按 host 精确 / 子域匹配过滤请求头；白名单为空表示不限制（普通 IPTV 行为不变） | `domain/stream/HeaderHostPolicy.kt` |
| `StreamTypeDetector` | `MIME_HLS = "application/x-mpegURL"`；识别 `.m3u8/.m3u`、`/hls/`、`hls.php`、`/m3u8/` 及 `format/type/protocol/fmt=m3u8\|hls`；**排除** `.mp4/.mkv/.flv/.ts/.mp3/.aac/.webm` | `domain/stream/StreamTypeDetector.kt` |
| `RoutingStreamResolver` | `mgtv:` 前缀走 MGTV 解析器，其余走 `StaticStreamResolver` | `domain/stream/RoutingStreamResolver.kt` |
| `Clock` | `fun interface Clock { fun nowMs(): Long }`，测试可注入 | `domain/stream/Clock.kt` |

#### 11.2 `PlayerManager` 的会话化改造

`PlayerManager` 构造参数新增 `streamResolver: StreamResolver = StaticStreamResolver` 与
`clock: Clock = SystemClock`，内部持有一个 `PlaybackSession` 快照：

```kotlin
private data class PlaybackSession(
    val url: String, val headers: Map<String, String>, val mimeType: String?,
    val allowedHeaderHosts: Set<String>, val expiresAtMs: Long?, val dynamic: Boolean,
)
```

| 行为 | 动态会话（`expiresAtMs != null` 或 `streamUrl` 为空） | 静态会话 |
|---|---|---|
| 候选地址 | 只有解析出来的 1 个 URL | `RetryPolicy.candidateUrls(channel)`（主源 + `backupUrls`） |
| 403 | `StreamRefreshCoordinator` 最多重解析 1 次并重连；失败/再次 403 → `Fatal` | 走既有 401/403/429 快速失败 → 切备用源 |
| 429 | 立即 `Fatal` | 走既有快速失败 |
| 5xx / 网络 | 交回既有每源 2 次退避重试 | 同左 |
| 延迟重试取地址 | `candidatesFor(activeSession, channel)` | 同左（等价于原逻辑） |
| 起播成功 | `onPlaybackRecovered()` 重置 403 刷新预算 | 同样调用，无副作用 |

`MediaSource` 构建：优先用 `ResolvedStream.mimeType`，否则 `StreamTypeDetector.inferMimeType(url)`，
把 MIME 写进 `MediaItem`，再交给 `DefaultMediaSourceFactory`。这样**无 `.m3u8` 后缀且服务端
`Content-Type` 不规范的 IPTV HLS** 也能进入 HLS 解析器，而 `.mp4/.flv` 不会被误判。

请求头隔离：`allowedHeaderHosts` 为空时沿用
`DefaultHttpDataSource.Factory.setDefaultRequestProperties(headers)`（普通 IPTV 完全不变）；
非空时改用 `media/ScopedHeaderDataSourceFactory`，在每次 `open(DataSpec)` 时按目标 host 过滤，
并关闭跨协议重定向。**已知残留**：Media3 1.4.1 在同协议 302 跳转时会沿用 `DataSpec` 头。

#### 11.3 MGTV 数据层（`data/mgtv/`）

| 类 | 职责 | 关键事实 |
|---|---|---|
| `MgtvSigner` | `/v1/live/source` 请求签名 | `UPPER(MD5(SALT + 参数名升序拼接 key+value + SALT))`；空串参与拼接，`sign`/`_support` 不参与；纯本地哈希 |
| `MgtvDeviceIdProvider` | 设备 UUID 来源 | `did` 与 `deviceId` 取同一 UUID；`SharedPrefsMgtvDeviceId` 首次生成后持久化，`EphemeralMgtvDeviceId` 仅测试用 |
| `MgtvEndpoints` | 目录 / 播放源 URL 构造 | `sourceParams()` 产出待签名参数集；`sourceUrl(params, sign)` 之后追加 `_support` 与 `sign` |
| `MgtvHttpClient` | JDK `HttpURLConnection` 小客户端 | 仅 http/https；5 s 连接 / 8 s 读取 / 2 MB 上限；`Success/HttpError/NetworkError/InvalidUrl/TooLarge`；不自动跟随 302 |
| `MgtvCatalogFetcher` | 公开频道目录抓取 | 无需 Cookie / 签名；JSONP 或纯 JSON 均可 |
| `HttpMgtvSourceFetcher` | 播放源抓取 | 组装参数 → 本地签名 → 发请求；`signer` / `urlBuilder` 可注入以便离线测试 |
| `MgtvJsonParser` | JSONP 剥离 + 目录 / 播放源解析 | `unwrapJsonp` / `parseCategoryList` / `parseSourceResponse`；`isp2p`/`hdcp` 缺失或非整数一律拒绝 |
| `MgtvSourceSelector` | 白盒筛选 | 仅放行 `.m3u8` + `isp2p==0` + `hdcp==0` + 合法 `https://`；无合规源返回 `null`，**不降级到 P2P** |
| `MgtvStreamResolver` | `StreamResolver` 实现 | `expiresAtMs = clock.nowMs() + urlExpireDuration*1000`；`allowedHeaderHosts = setOf("mgtv.com")`；`Origin` 无尾斜杠、`Referer` 带斜杠 |
| `MgtvProvider` | 频道目录 → 领域频道 | 频道 ID `mgtv:<cameraId>`、分类 ID `mgtv:<categoryId>` |

`LiveTvApplication.streamResolver` 组装为：

```kotlin
RoutingStreamResolver(
    mgtvResolver = MgtvStreamResolver(HttpMgtvSourceFetcher(SharedPrefsMgtvDeviceId(this))),
    fallbackResolver = StaticStreamResolver,
)
```

> **状态（已跑通并在真机验证）**：`sign` 从芒果 TV 网页播放器公开 JS 还原为
> 「盐值 + 参数排序拼接」的 MD5（HAR 3 组样本离线校验 3/3 一致）；`did`/`deviceId` 由客户端本地生成 UUID，
> 请求不携带 Cookie。真实网络与真机均已验证：`code=0` → HLS 清单 200 → MPEG-TS 分片 200 → 播放器出画。
>
> 关键实现补充：
> - **解析异步化**：`PlayerManager` 用单线程 `resolutionExecutor` 执行 `StreamResolver.resolve()`，
>   回主线程前用 `SessionGenerationGate` 校验代际号（`domain/stream/SessionGenerationGate.kt`）；
> - **底层不重试**：`media/NoRetryOnClientErrorPolicy` 让 Media3 对 401/403/429 直接放弃（0 次底层重试）；
> - **跳转跟随**：`media/ScopedHeaderDataSourceFactory` 自己跟随 3xx（≤5 跳、仅 https、逐跳重过滤请求头），
>   解决 CDN 边缘调度导致的 302 报错；
> - **UI 独立分组**：`data/mgtv/MgtvCatalogCoordinator` 后台拉目录 + 主线程回传，
>   手机端把 MGTV 频道作为独立分组追加到列表末尾（TV 端未接入）。

---

## 第二部分：现有技术债（带证据）

| # | 技术债 | 证据 | 影响 |
|---|---|---|---|
| 1 | ~~业务数据硬编码在 UI 层~~ **阶段二已修复** | 原 `MainBrowseFragment.sampleNewsChannels/sampleSportsChannels` 已删除；现由 `LocalAssetChannelSource` 读 `assets/channels.json` | 数据与 UI 解耦，可换源、可测试 |
| 2 | ~~播放器生命周期与 API 24+ 推荐模式不一致~~ **阶段一已修复** | `onStart()` 初始化 / `onStop()` 释放，`initializePlayer()` 防重复初始化 | 已对齐 Android API 24+ 推荐模式 |
| 3 | ~~释放后未断开 PlayerView 绑定~~ **阶段一已修复** | `releasePlayer()` 现先 `binding.playerView.player = null` 再 `release()` | 视图不再持有已释放的 ExoPlayer |
| 4 | ~~无测试基础设施~~ **阶段一已修复，阶段二至阶段九持续扩充** | `app/src/test` 现有 34 个测试类 /**319 个用例**（阶段二 22 + 阶段三 55 + 阶段四 27 + 阶段五 20 + 阶段六 17 + 阶段七 21 + 阶段八 31 + 阶段九 82，另有外部同步带来的增量） | 解析、过滤、重试、切台、状态流转、缓存、请求头、设备判定、网格列数、徽章/副标题/筛选/抽屉、远程拉取、订阅兜底、快速失败、HLS 识别、Host 白名单隔离、MGTV 白盒筛选、签名还原与 fail-closed 已可回归 |
| 4b | ~~Lint 报错导致构建失败~~ **阶段一已修复** | `PlayerActivity` 加 `@androidx.annotation.OptIn(UnstableApi::class)` | `lintDebug` 可通过 |
| 5 | ~~硬编码用户可见文案绕过资源系统~~ **阶段三已修复** | 原 `PlayerActivity` 的 `"播放失败: ${error.message}"` 已改为 `strings.xml` 的 `player_error_*` + `player_error_hint` | 可本地化，Lint `SetTextI18n` 已消除 |
| 6 | **使用已废弃的全屏 API** | `PlayerActivity.hideSystemUi()` 用 `systemUiVisibility`，已 `@Suppress("DEPRECATION")` | API 30+ 沉浸式行为不完整 |
| 7 | **播放页主题与主界面不统一** | Manifest 中 `PlayerActivity` 用 `@style/Theme.AppCompat`，主界面用 `Theme.LiveTv` | 风格割裂 |
| 8 | ~~无 CI、无格式约束~~ **阶段一已修复** | `.github/workflows/android-ci.yml` + `.editorconfig` | 改动有自动校验 |
| 9 | **卡片无图像** | `Channel.logo` 字段已由 M3U 解析器填充（`tvg-logo`），但 `ChannelCardPresenter.onBindViewHolder` 只设 `titleText`/`contentText` | 视觉上只有文字卡片 |
| 10 | ~~无错误重试/无源切换~~ **阶段三已修复** | `RetryPolicy` + `PlayerManager` 实现每源 2 次重试（1500/3000ms 退避）与主备源自动切换 | 直播源抖动可自动恢复 |
| 11 | **release 未混淆且无自定义 ProGuard 规则** | `app/build.gradle.kts` + 空 `app/proguard-rules.pro` | 体积与安全基线低 |
| 12 | **`MainActivity` 使用 `FragmentActivity`** | 继承 `androidx.fragment.app.FragmentActivity` | 无法使用 AppCompat 主题能力 |
| 13 | ~~数据同步仍靠重新打包 assets~~ **阶段八部分修复** | 阶段八新增远程 M3U 订阅（`RemoteM3uFetcher` + `SubscriptionChannelRepository`），订阅地址可在手机端运行时配置并刷新；但**本地文件导入**仍未做，且内置 assets 只能在没有订阅（或订阅失败且无缓存）时生效 | 换订阅源不再需要改包；本地文件导入仍待做 |
| 20 | ~~数据源在 UI 层重复装配~~ **阶段五已修复** | 原 `MainBrowseFragment` 与 `PlayerActivity` 各自 `DefaultChannelRepository(LocalAssetChannelSource(...), LocalM3uChannelSource(...))`，会重复读 assets；现统一走 `LiveTvApplication.channelRepository`（全局单例 + 内存缓存） | 重复解析 assets 的冗余开销消除 |
| 19 | ~~M3U 解析覆盖有限~~ **阶段五部分修复** | `M3uPlaylistParser` 现支持 `#EXTVLCOPT`/`#KODIPROP` 请求头与行内请求头属性、引号内逗号切分；仍不支持 `#EXTGRP` 分组、直播地址参数（`?token=`）透传 | 复杂直播源仍需手工预处理 |
| 14 | **未使用的字符串资源** | `R.string.row_news` / `R.string.row_sports` 已无引用（Lint `UnusedResources`）；行标题改由 JSON 分类 `name` 提供 | 资源项成为残留，**保留未删**（避免扩大改动面），后续随 UI 文案整理一并清理 |
| 15 | **浏览页仍无加载/空态反馈** | `MainBrowseFragment.loadRows()` 在 `onCreate` 同步取数，取不到内容时页面为空且无提示 | 用户无法区分"无频道"与"加载失败"（播放页已有状态提示） |
| 16 | **播放页未接 `PlayerView` 控制器按钮** | 切台只通过遥控器方向键；控制器里的"上一台/下一台"按钮未接入 `ChannelNavigator` | 触屏/控制器操作无法切台 |
| 17 | ~~HTTP 状态码识别依赖 Media3 错误码区间~~ **阶段 3.5 修复，阶段 5.5 修正** | `findInvalidResponseCode()` 遍历 cause 链取 `InvalidResponseCodeException.responseCode`。阶段 3.5 曾保留 `HTTP_STATUS_RANGE = 2000..2999` 作为兜底，**该兜底是错的**：2000-2008 是 Media3 的 IO 错误段（2001=网络连接失败、2002=连接超时），真机上把网络故障显示成了"HTTP 2001"。阶段 5.5 已删除该区间，只信 cause 链里的真实响应码 | 错误文案不再误导；`classifyCause()` 同时改为遍历 cause 链，能识别被 `HttpDataSourceException` 包住的 `UnknownHostException`/`SocketTimeoutException` |
| 18 | ~~切台提示不自动消失~~ **阶段 3.5 已修复** | `PlayerActivity.showChannelSwitchedHint()` 用 `lifecycleScope.launch { delay(3000) }` 自动隐藏 `statusText`；显示新提示前 `cancel()` 旧 Job，`onDestroy()` 再兜底取消 | 短时提示不再长期遮挡画面 |
| 21 | **手机端卡片不加载频道 Logo** | `Channel.logo` 已有字段（阶段五由 M3U `tvg-logo` 填充），但 `MobileChannelAdapter` 只绑名称与副标题；阶段七起卡片不再有封面位，改为分类色徽章 | 引入图片加载库被阶段六明确排除；远程图加载列为后续方向 |
| 23 | **触控浮层没有"正在播放"之外的进度信息** | `PlayerActivity` 触控浮层只显示频道名与位置，进度条仍用 Media3 `PlayerView` 内置控制器（需主动呼出） | 手机端未做常驻进度条与拖动切台 |
| 24 | **播放页 BACK 行为分三档但无统一返回栈** | `dispatchKeyEvent`/`onKeyDown`：抽屉 → 控制器 → 退出；抽屉状态用 Activity 字段 `isDrawerOpen` 而非 `OnBackPressedDispatcher` | 后续接入 `onBackPressedDispatcher` 时应整体改写这三个分支 |
| 22 | **手机端浏览页仍无加载/空态之外的错误反馈** | 与技术债 15 同源：手机端 `MobileMainActivity.loadChannels()` 同步取数，空结果展示 `mobile_empty_channels` | 手机端已有空态文案，缺"加载失败"与"重试"区分（与 TV 端同一债务） |
| 25 | **TV 端（Leanback 浏览页）没有订阅管理与刷新入口** | 阶段八的「刷新 / 订阅源」按钮只做在手机端 `MobileMainActivity`；TV 端 `MainBrowseFragment` 只读仓库快照 | TV 用户无法在遥控器界面切换订阅源，只能用手机端配置后共享同一份 `SharedPreferences` |
| 26 | **远程订阅是"替换"内置频道，而不是"合并"** | `SubscriptionChannelRepository.applyRemoteContent()` 直接把快照替换为远程解析结果；内置 assets 只在"无订阅 / 订阅失败且无缓存"时生效 | 配置订阅后内置频道消失；若需要"远程 + 内置合并"需另做合并规则 |
| 27 | **订阅地址明文存 `SharedPreferences`** | `FileSubscriptionStore` 用普通 `SharedPreferences("livetv_subscription")` 存 URL | 订阅地址本身不是密钥，但若将来接入带凭据的订阅仍需加密存储 |
| 28 | ~~MGTV 动态播放地址无法获取~~ **阶段九已解除** | `MgtvSigner` 还原了 `sign`（盐值 + 参数排序拼接的 MD5），HAR 3 组样本离线校验 3/3 一致；`did`/`deviceId` 由 `SharedPrefsMgtvDeviceId` 本地生成 | 真实网络已验证 `code=0` + HLS 200 + TS 200；剩余阻断是主线程解析（#30）与 UI 未接入（#31） |
| 29 | **同协议 302 跳转仍会沿用请求头** | `ScopedHeaderDataSourceFactory` 只在 `open(DataSpec)` 过滤；Media3 1.4.1 内部跟随同协议 302 时沿用原 `DataSpec` 头（跨协议跳转已用 `setAllowCrossProtocolRedirects(false)` 阻断） | MGTV 专用 `Origin`/`Referer` 可能随同协议跳转带到第三方 host；需 Media3 升级或自定义 `Resolver` 才能彻底消除 |
| 30 | ~~MGTV 解析在主线程同步执行~~ **已修复：改为后台解析 + 代际号校验** | `PlayerManager.switchChannel()` → `resolveSession()` → `StreamRefreshCoordinator.prepareForPlayback()` → `HttpMgtvSourceFetcher`（`HttpURLConnection`）全同步，由 `PlayerActivity` 在 UI 线程调用 | 真机上会抛 `NetworkOnMainThreadException`；需移到后台线程并用代际号防串台 |
| 31 | ~~MGTV 频道未接入 UI~~ **已修复（手机端独立分组）** | `MgtvProvider.toDomainCategories()` 已能产出 `mgtv:<cameraId>` 领域频道，但 `LiveTvApplication.channelRepository` 未并入，浏览页看不到 MGTV 分类 | 需要先决定「并入内置」还是「单独入口」再接线；当前只有 `mgtv:` 前缀频道能走动态解析 |

---

## 第三部分：演进方向（**尚未实现**）

阶段二已落地 `data/` 与 `domain/` 的骨架，阶段三已落地 `player/` 的纯策略层 + 桥接层，
阶段四已落地 M3U 解析与多数据源合并。
后续在此骨架上扩展，**不引入 Hilt/Dagger，不拆模块**。

| # | 方向 | 落点 | 备注 |
|---|---|---|---|
| 1 | ~~多数据源聚合~~ **阶段四基础版 + 阶段八网络源** | `DefaultChannelRepository` / `SubscriptionChannelRepository` | 已聚合 assets(JSON) + assets(M3U)；阶段八新增远程 M3U 订阅（远程 → 缓存 → 内置 三级兜底）；**用户本地文件导入**待做 |
| 2 | ~~M3U/M3U8 解析器~~ **阶段四已完成基础版** | `data/parser/M3uPlaylistParser.kt` | 已支持 `#EXTINF` + `tvg-*` 属性；`#EXTGRP` / URL 参数透传待补 |
| 3 | ~~主备源切换（Failover）~~ **阶段三已完成** | `player/RetryPolicy` + `player/PlayerManager` | 消费 `Channel.backupUrls`；主源重试耗尽后按顺序切备用源 |
| 4 | ~~自定义 HTTP 请求头~~ **阶段三已完成接线** | `Channel.headers` → `DefaultHttpDataSource.Factory.setDefaultRequestProperties` | 请求头走 DataSource 层，不走 `setMediaItem` |
| 5 | 频道图标 | `Channel.logo` + `ChannelCardPresenter` | 字段已由 M3U 解析器填充（`tvg-logo`），仍需在 Presenter 绑定图片 |
| 6 | 浏览页空态 / 加载态 | `MainBrowseFragment` + `domain/` 状态类 | 解决技术债 #15；播放页的状态流转阶段三已完成 |
| 7 | 持久化 | `data/` 引入轻量存储 | 方案待定，**不预设 Room** |
| 8 | EPG | `Channel.epgId` | 字段已由 M3U 解析器填充（`tvg-id`），格式与时区处理见 ROADMAP P2 |
| 9 | 播放器控制器按钮接切台 | `PlayerView` 控制器 + `ChannelNavigator` | 解决技术债 #16 |
| 10 | ~~切台提示自动消失~~ **阶段 3.5 已完成** | `PlayerActivity` 的 `statusText` 定时隐藏（`lifecycleScope` + `delay(3000)`） | 已解决技术债 #18 |
| 11 | 播放进度记忆 | `PlayerManager` 保存/恢复 `currentPosition` | 阶段一切台后重播的行为仍待评估 |
| 12 | 用户导入 / 本地文件 M3U 源 | `data/source/` 新增文件型数据源 + 导入入口 | 阶段八已做**网络 M3U 源**（远程订阅 + 本地缓存兜底）；**本地文件导入 UI** 仍未做 |
| 13 | ~~数据源在 UI 层重复装配~~ **阶段五已完成** | `LiveTvApplication` 持有全局仓库（带缓存），UI 层只取用 | 解决技术债 #20 |
| 14 | ~~双端 UI（手机 / 平板触控）~~ **阶段六已完成基础版** | `util/DeviceUtils` + `ui/mobile/*`（`MobileMainActivity` + `RecyclerView` 网格） | 入口按设备形态分流；播放页两端共用。手机端卡片 Logo 未接（技术债 #21） |
| 15 | 远程频道 Logo 加载 | `ui/mobile` + `ui/ChannelCardPresenter` 绑定 `Channel.logo` | 技术债 #21；需先决定是否引入图片加载库（阶段六刻意未引入） |
| 16 | ~~HLS 播放能力审查（P0）~~ **阶段九已完成** | `PlayerManager.buildMediaSource` 统一走 `DefaultMediaSourceFactory`；`StreamTypeDetector` 显式 MIME 兜住无后缀 HLS | 普通 IPTV、M3U 订阅、JSON 频道同一路径，无回归 |
| 17 | ~~动态播放会话抽象（P1）~~ **阶段九已完成** | `domain/stream/` + `data/mgtv/` + `media/ScopedHeaderDataSourceFactory` | `PlayerManager` 只消费 `ResolvedStream`，不含 MGTV 专有逻辑 |
| 18 | ~~MGTV 播放源接入（P2/P3）~~ **阶段九已跑通签名** | `MgtvSigner` + `HttpMgtvSourceFetcher` + `MgtvStreamResolver` | 源已可解析并出 HLS；P5 频道/UI 集成与主线程异步化仍待做 |
