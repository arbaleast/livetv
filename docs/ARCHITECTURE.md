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
├── LiveTvApplication.kt                  # ← 阶段五新增：全局 ChannelRepository 单例（带内存缓存）
├── MainActivity.kt                       # 宿主 Activity
├── data/                                 # ← 阶段二新增：数据获取与解析（阶段四扩展 M3U）
│   ├── model/ChannelConfigDto.kt        # JSON 映射 DTO（version / categories / channels）
│   ├── parser/ChannelJsonParser.kt      # 纯文本 JSON → DTO/领域模型 的解析与校验
│   ├── parser/M3uPlaylistParser.kt      # ← 阶段四新增：纯文本 M3U → 领域模型 的解析与校验
│   ├── source/ChannelDataSource.kt       # 数据源接口
│   ├── source/LocalAssetChannelSource.kt # 读 assets/channels.json 并调用 Parser
│   ├── source/LocalM3uChannelSource.kt  # ← 阶段四新增：读 assets/sample.m3u 并调用 Parser
│   └── repository/DefaultChannelRepository.kt   # ← 阶段四升级：多数据源合并（阶段五加缓存）
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
    ├── PlayerActivity.kt                 # 播放页（UI + 遥控器按键，不含播放器逻辑）
    └── mobile/                           # ← 阶段六新增：手机/平板触控端
        ├── MobileChannelList.kt          # 纯 Kotlin：行序列组装 + 网格列数计算
        ├── MobileChannelAdapter.kt       # RecyclerView.Adapter（分类标题 + 频道卡片两种 item）
        └── MobileMainActivity.kt         # 手机端主界面（RecyclerView + GridLayoutManager）
└── util/
    └── DeviceUtils.kt                    # ← 阶段六新增：TV / 触控设备形态判定
```

| 类 | 职责 |
|---|---|
| `MainActivity` | **阶段六**：设备形态**分流入口**——`DeviceUtils.isTvDevice(this)` 为真则装载 Leanback `MainBrowseFragment`（并强制横屏），否则启动 `MobileMainActivity` 后 `finish()` |
| `LiveTvApplication`（阶段五） | 全局 `ChannelRepository` 持有者（`by lazy` 装配 JSON + M3U 两个数据源）；提供 `refreshChannels()` 强制重算 |
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
| `MobileChannelList`（ui/mobile，纯） | **阶段六**：纯函数——`buildRows(categories)` 把分类展开成"标题 + 卡片"行序列（跳过空分类），`gridSpanCount(screenWidthDp)` 决定网格列数 |
| `MobileChannelAdapter`（ui/mobile） | **阶段六**：`RecyclerView.Adapter`，两种 item（`CategoryHeader` 跨整行 / `ChannelCard` 占一格），点击回调交给 Activity |
| `MobileMainActivity`（ui/mobile） | **阶段六**：手机/平板主界面——`RecyclerView` + `GridLayoutManager`，从 `LiveTvApplication.channelRepository` 取数，点击卡片带三个 Extra 启动**共用**的 `PlayerActivity` |

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
  │       └─ DefaultChannelRepository.getCategories()   ← 逐源 try/catch，单源失败只跳过该源；命中缓存则直接返回
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

> **阶段六未做**：手机端不加载远程频道 Logo（`Channel.logo` 已有字段但无消费方，卡片用本地矢量占位图）；
> 引入图片加载库（Glide/Coil 等）被明确排除，避免为占位图引入重型依赖。

---

## 第二部分：现有技术债（带证据）

| # | 技术债 | 证据 | 影响 |
|---|---|---|---|
| 1 | ~~业务数据硬编码在 UI 层~~ **阶段二已修复** | 原 `MainBrowseFragment.sampleNewsChannels/sampleSportsChannels` 已删除；现由 `LocalAssetChannelSource` 读 `assets/channels.json` | 数据与 UI 解耦，可换源、可测试 |
| 2 | ~~播放器生命周期与 API 24+ 推荐模式不一致~~ **阶段一已修复** | `onStart()` 初始化 / `onStop()` 释放，`initializePlayer()` 防重复初始化 | 已对齐 Android API 24+ 推荐模式 |
| 3 | ~~释放后未断开 PlayerView 绑定~~ **阶段一已修复** | `releasePlayer()` 现先 `binding.playerView.player = null` 再 `release()` | 视图不再持有已释放的 ExoPlayer |
| 4 | ~~无测试基础设施~~ **阶段一已修复，阶段二/三/四/五/六扩充** | `app/src/test` 现有 15 个测试类 / **141 个用例**（阶段二 22 + 阶段三 55 + 阶段四 27 + 阶段五 20 + 阶段六 17） | 解析、过滤、重试、切台、状态流转、缓存、请求头、设备判定、网格列数已可回归 |
| 4b | ~~Lint 报错导致构建失败~~ **阶段一已修复** | `PlayerActivity` 加 `@androidx.annotation.OptIn(UnstableApi::class)` | `lintDebug` 可通过 |
| 5 | ~~硬编码用户可见文案绕过资源系统~~ **阶段三已修复** | 原 `PlayerActivity` 的 `"播放失败: ${error.message}"` 已改为 `strings.xml` 的 `player_error_*` + `player_error_hint` | 可本地化，Lint `SetTextI18n` 已消除 |
| 6 | **使用已废弃的全屏 API** | `PlayerActivity.hideSystemUi()` 用 `systemUiVisibility`，已 `@Suppress("DEPRECATION")` | API 30+ 沉浸式行为不完整 |
| 7 | **播放页主题与主界面不统一** | Manifest 中 `PlayerActivity` 用 `@style/Theme.AppCompat`，主界面用 `Theme.LiveTv` | 风格割裂 |
| 8 | ~~无 CI、无格式约束~~ **阶段一已修复** | `.github/workflows/android-ci.yml` + `.editorconfig` | 改动有自动校验 |
| 9 | **卡片无图像** | `Channel.logo` 字段已由 M3U 解析器填充（`tvg-logo`），但 `ChannelCardPresenter.onBindViewHolder` 只设 `titleText`/`contentText` | 视觉上只有文字卡片 |
| 10 | ~~无错误重试/无源切换~~ **阶段三已修复** | `RetryPolicy` + `PlayerManager` 实现每源 2 次重试（1500/3000ms 退避）与主备源自动切换 | 直播源抖动可自动恢复 |
| 11 | **release 未混淆且无自定义 ProGuard 规则** | `app/build.gradle.kts` + 空 `app/proguard-rules.pro` | 体积与安全基线低 |
| 12 | **`MainActivity` 使用 `FragmentActivity`** | 继承 `androidx.fragment.app.FragmentActivity` | 无法使用 AppCompat 主题能力 |
| 13 | **数据同步仍靠重新打包 assets** | `LocalAssetChannelSource` 固定读 `assets/channels.json`、`LocalM3uChannelSource` 固定读 `assets/sample.m3u` | 换源需要改包；用户导入 / 网络源仍未做 |
| 20 | ~~数据源在 UI 层重复装配~~ **阶段五已修复** | 原 `MainBrowseFragment` 与 `PlayerActivity` 各自 `DefaultChannelRepository(LocalAssetChannelSource(...), LocalM3uChannelSource(...))`，会重复读 assets；现统一走 `LiveTvApplication.channelRepository`（全局单例 + 内存缓存） | 重复解析 assets 的冗余开销消除 |
| 19 | ~~M3U 解析覆盖有限~~ **阶段五部分修复** | `M3uPlaylistParser` 现支持 `#EXTVLCOPT`/`#KODIPROP` 请求头与行内请求头属性、引号内逗号切分；仍不支持 `#EXTGRP` 分组、直播地址参数（`?token=`）透传 | 复杂直播源仍需手工预处理 |
| 14 | **未使用的字符串资源** | `R.string.row_news` / `R.string.row_sports` 已无引用（Lint `UnusedResources`）；行标题改由 JSON 分类 `name` 提供 | 资源项成为残留，**保留未删**（避免扩大改动面），后续随 UI 文案整理一并清理 |
| 15 | **浏览页仍无加载/空态反馈** | `MainBrowseFragment.loadRows()` 在 `onCreate` 同步取数，取不到内容时页面为空且无提示 | 用户无法区分"无频道"与"加载失败"（播放页已有状态提示） |
| 16 | **播放页未接 `PlayerView` 控制器按钮** | 切台只通过遥控器方向键；控制器里的"上一台/下一台"按钮未接入 `ChannelNavigator` | 触屏/控制器操作无法切台 |
| 17 | ~~HTTP 状态码识别依赖 Media3 错误码区间~~ **阶段 3.5 修复，阶段 5.5 修正** | `findInvalidResponseCode()` 遍历 cause 链取 `InvalidResponseCodeException.responseCode`。阶段 3.5 曾保留 `HTTP_STATUS_RANGE = 2000..2999` 作为兜底，**该兜底是错的**：2000-2008 是 Media3 的 IO 错误段（2001=网络连接失败、2002=连接超时），真机上把网络故障显示成了"HTTP 2001"。阶段 5.5 已删除该区间，只信 cause 链里的真实响应码 | 错误文案不再误导；`classifyCause()` 同时改为遍历 cause 链，能识别被 `HttpDataSourceException` 包住的 `UnknownHostException`/`SocketTimeoutException` |
| 18 | ~~切台提示不自动消失~~ **阶段 3.5 已修复** | `PlayerActivity.showChannelSwitchedHint()` 用 `lifecycleScope.launch { delay(3000) }` 自动隐藏 `statusText`；显示新提示前 `cancel()` 旧 Job，`onDestroy()` 再兜底取消 | 短时提示不再长期遮挡画面 |
| 21 | **手机端卡片不加载频道 Logo** | `Channel.logo` 已有字段（阶段五由 M3U `tvg-logo` 填充），但 `MobileChannelAdapter` 只绑名称与描述，封面用本地矢量占位图 | 引入图片加载库被阶段六明确排除；远程图加载列为后续方向 |
| 22 | **手机端浏览页仍无加载/空态之外的错误反馈** | 与技术债 15 同源：手机端 `MobileMainActivity.loadChannels()` 同步取数，空结果展示 `mobile_empty_channels` | 手机端已有空态文案，缺"加载失败"与"重试"区分（与 TV 端同一债务） |

---

## 第三部分：演进方向（**尚未实现**）

阶段二已落地 `data/` 与 `domain/` 的骨架，阶段三已落地 `player/` 的纯策略层 + 桥接层，
阶段四已落地 M3U 解析与多数据源合并。
后续在此骨架上扩展，**不引入 Hilt/Dagger，不拆模块**。

| # | 方向 | 落点 | 备注 |
|---|---|---|---|
| 1 | ~~多数据源聚合~~ **阶段四已完成基础版** | `DefaultChannelRepository` | 已聚合 assets(JSON) + assets(M3U)；网络源 / 用户导入待做 |
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
| 12 | 用户导入 / 网络 M3U 源 | `data/source/` 新增 `NetworkM3uChannelSource` + 设置页 | 阶段四**刻意未做**（不引入网络层与导入 UI）；阶段五补齐了请求头链路，导入 UI 仍未做 |
| 13 | ~~数据源在 UI 层重复装配~~ **阶段五已完成** | `LiveTvApplication` 持有全局仓库（带缓存），UI 层只取用 | 解决技术债 #20 |
| 14 | ~~双端 UI（手机 / 平板触控）~~ **阶段六已完成基础版** | `util/DeviceUtils` + `ui/mobile/*`（`MobileMainActivity` + `RecyclerView` 网格） | 入口按设备形态分流；播放页两端共用。手机端卡片 Logo 未接（技术债 #21） |
| 15 | 远程频道 Logo 加载 | `ui/mobile` + `ui/ChannelCardPresenter` 绑定 `Channel.logo` | 技术债 #21；需先决定是否引入图片加载库（阶段六刻意未引入） |
