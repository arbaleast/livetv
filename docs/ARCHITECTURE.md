# ARCHITECTURE — LiveTV 现状架构与演进方向

> 本文分两部分：**第一部分 100% 描述当前源码事实**（每条都能在指定文件里指到），
> **第二部分是演进方向**（尚未实现，仅供排期参考，不代表现状）。
> 本阶段（阶段一）**不重构目录结构、不迁移硬编码频道数据**。

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

依赖（全部 `implementation`，无 `testImplementation`/`androidTestImplementation`）：
`androidx.core:core-ktx:1.13.1`、`androidx.fragment:fragment-ktx:1.8.2`、
`androidx.leanback:leanback:1.0.0`、`androidx.recyclerview:recyclerview:1.3.2`、
`androidx.appcompat:appcompat:1.7.0`、`androidx.media3:media3-exoplayer:1.4.1`、
`androidx.media3:media3-ui:1.4.1`、`androidx.media3:media3-exoplayer-hls:1.4.1`。

### 2. 类清单（共 5 个 Kotlin 文件）

| 类 | 继承 | 职责 |
|---|---|---|
| `MainActivity` | `FragmentActivity` | 宿主 Activity，`setContentView(R.layout.activity_main)`，在 `savedInstanceState == null` 时提交 `MainBrowseFragment` 到 `R.id.main_browse_fragment` |
| `MainBrowseFragment` | `BrowseSupportFragment` | 浏览页：配置标题/品牌色，组装 2 行 `ListRow`，处理卡片点击跳转播放 |
| `ChannelCardPresenter` | `Presenter` | 渲染 `ImageCardView` 卡片（标题 + 描述） |
| `PlayerActivity` | `AppCompatActivity` | Media3 ExoPlayer 播放页（HLS），显示频道名与错误文案 |
| `Channel` | `data class` | 唯一数据模型：`id: String`、`name: String`、`description: String`、`streamUrl: String = ""` |

### 3. 数据流向（当前唯一一条链路）

```
MainActivity.onCreate
  └─ activity_main.xml: FrameLayout(id=main_browse_fragment)
  └─ commit(MainBrowseFragment)              [仅首次创建]

MainBrowseFragment.onCreate
  ├─ setupUi()      title / headersState=HEADERS_ENABLED / isHeadersTransitionOnBackEnabled=true / brandColor
  ├─ loadRows()     ArrayObjectAdapter(ListRowPresenter())
  │                 ├─ ListRow(HeaderItem(row_news),  adapter(ChannelCardPresenter(), sampleNewsChannels()))
  │                 └─ ListRow(HeaderItem(row_sports), adapter(ChannelCardPresenter(), sampleSportsChannels()))
  └─ setupEventListeners()
                    onItemViewClickedListener:
                      item is Channel 且 streamUrl 非空 → startActivity(PlayerActivity)
                                                          extra: EXTRA_CHANNEL_NAME / EXTRA_STREAM_URL
                      否则 → Toast(channel_selected, name)

PlayerActivity
  onCreate: window.addFlags(FLAG_KEEP_SCREEN_ON) → inflate activity_player.xml (ViewBinding)
  onStart : initializePlayer()   [player 非空则直接返回，防重复初始化]
              ExoPlayer.Builder(this).build()
              binding.playerView.player = exoPlayer
              HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
                  .createMediaSource(MediaItem.fromUri(streamUrl))
              setMediaSource → playWhenReady → seekTo(playbackPosition) → prepare()
              Player.Listener.onPlayerError → binding.errorText 显示 "播放失败: ${error.message}"
  onResume: hideSystemUi()      [不再重复初始化播放器]
  onStop  : releasePlayer()      保存 currentPosition / playWhenReady
                                  → binding.playerView.player = null → release()
```

**要点：没有网络请求、没有数据解析、没有持久化、没有 Repository、没有状态管理层。**
频道对象由 `MainBrowseFragment.sampleNewsChannels()` / `sampleSportsChannels()` 直接构造
（3 个新闻源 + 3 个体育源，均为 `https://cctvihrd.cctv.com/live/huaweiiptv/<n>/1/index.m3u8`）。

### 4. 资源与 Manifest

| 项 | 事实 |
|---|---|
| `uses-feature` | `android.software.leanback` (required=true)、`android.hardware.touchscreen` (required=false) |
| 权限 | 仅 `android.permission.INTERNET` |
| Activity | `MainActivity` exported=true + `LEANBACK_LAUNCHER` + landscape；`PlayerActivity` exported=false + landscape + `Theme.AppCompat` |
| 主题 | `Theme.LiveTv` parent=`Theme.Leanback`，含 `defaultBrandColor`/`defaultSearchColor` |
| 颜色 | `primary`/`accent`/`brand_primary`(#1565C0)/`search_highlight` |
| 字符串 | `app_name`/`browse_title`/`row_news`/`row_sports`/`channel_selected`（全部硬编码中文） |
| 布局 | `activity_main.xml` = 单个 FrameLayout；`activity_player.xml` = FrameLayout + `PlayerView` + `channel_name` + `error_text` |
| 图标 | `mipmap-anydpi-v26/ic_launcher.xml`（自适应图标）+ `drawable/ic_launcher_foreground.xml` + `drawable/app_banner.xml`（layer-list 纯色） |

---

## 第二部分：现有技术债（带证据）

| # | 技术债 | 证据 | 影响 |
|---|---|---|---|
| 1 | **业务数据硬编码在 UI 层** | `MainBrowseFragment.sampleNewsChannels/sampleSportsChannels` | 无法换源、无法解析外部列表、无法测试数据获取逻辑；是 P1 首要改造对象 |
| 2 | ~~播放器生命周期与 API 24+ 推荐模式不一致~~ **阶段一已修复** | 原 `onPause()` 释放播放器、`onResume()` 又重建；现改为 `onStart()` 初始化 / `onStop()` 释放，并在 `initializePlayer()` 开头防重复初始化 | 已对齐 Android API 24+ 推荐模式 |
| 3 | ~~释放后未断开 PlayerView 绑定~~ **阶段一已修复** | `releasePlayer()` 现先 `binding.playerView.player = null` 再 `release()` | 视图不再持有已释放的 ExoPlayer |
| 4 | **无测试基础设施** | `app/build.gradle.kts` 无任何 `testImplementation`；无 `app/src/test`、无 `app/src/androidTest` | 阶段一补 1 个 `Channel` 纯 JVM 冒烟测试打通链路；数据解析/状态流转目前无法回归 |
| 4b | ~~Lint 报错导致构建失败~~ **阶段一已修复** | 原 `PlayerActivity` 直接使用 Media3 实验 API，`lintDebug` 报 4 个 `UnsafeOptInUsageError`；现于类上加 `@androidx.annotation.OptIn(UnstableApi::class)` | `lintDebug` 可通过（剩余 9 个 Warning 均为既有写法，不阻断构建） |
| 5 | **硬编码用户可见文案绕过资源系统** | `PlayerActivity` 内 `"播放失败: ${error.message}"` 直接写死中文字面量 | 不可本地化，且与 `strings.xml` 约定不一致 |
| 6 | **使用已废弃的全屏 API** | `PlayerActivity.hideSystemUi()` 用 `systemUiVisibility`，已 `@Suppress("DEPRECATION")` | API 30+ 上沉浸式行为不完整，后续需改 `WindowInsetsController` |
| 7 | **播放页主题与主界面不统一** | Manifest 中 `PlayerActivity` 用 `@style/Theme.AppCompat`，主界面用 `Theme.LiveTv` | 无品牌色/沉浸式主题，风格割裂 |
| 8 | **无 CI、无格式约束** | 仓库无 `.github/workflows/`、无 `.editorconfig` | 改动无自动校验，风格靠人守；阶段一补齐 |
| 9 | **卡片无图像** | `Channel` 无 logo 字段，`ChannelCardPresenter.onBindViewHolder` 只设 `titleText`/`contentText` | 视觉上只有文字卡片 |
| 10 | **无错误重试/无源切换** | `PlayerActivity` 错误处理只显示文案 | 直播源抖动即失败 |
| 11 | **release 未混淆且无自定义 ProGuard 规则** | `app/build.gradle.kts` + 空 `app/proguard-rules.pro` | 体积与安全基线低；发布前需处理 Media3/Leanback 反射规则 |
| 12 | **`MainActivity` 使用 `FragmentActivity`** | 继承 `androidx.fragment.app.FragmentActivity` | 无法使用 AppCompat 主题能力；后续若引入 AppCompat 组件需评估 |

---

## 第三部分：演进方向（**尚未实现**）

目标是**轻量分层**，不是 Clean Architecture 多模块拆分：**不引入 Hilt/Dagger，不拆模块**。

```
app/src/main/java/com/livetv/android/
├── data/            ← 新增：数据获取与解析
│   ├── source/      SourceParser（m3u/m3u8/XML 解析，纯函数、可 JVM 单测）
│   └── repo/        ChannelRepository（接口）+ SampleChannelRepository（现有硬编码数据）
├── domain/          ← 新增（可选）：use case（GetChannels / PlayStream），纯 Kotlin
├── model/           Channel 等模型（已有）
└── ui/              Leanback 界面（结构保持不变）
```

演进顺序（建议）：

1. **先抽接口再换实现**：定义 `ChannelRepository`，让 `MainBrowseFragment` 先从 `SampleChannelRepository`
   取数据（行为不变、可回归），此时才具备替换为网络/本地源的条件。
2. **解析器纯函数化**：`SourceParser` 只做 `String -> List<Channel>`，天然可用纯 JVM 单测覆盖。
3. **状态流转**：引入 `StateFlow`/sealed class 表达 Loading/Content/Error，用 `repeatOnLifecycle` 订阅，避免手动 start/stop。
4. **手动注入**：`Repository` 由 Activity/Fragment 手工构造后传入 Presenter，暂不引入 DI 框架。
5. **播放增强**：重试策略、清晰度/多码率、错误分类，属于 P1 播放器范畴。

> 上述目录只是目标形态，**阶段一不落地任何 `data/` `domain/` 目录或新包**。
