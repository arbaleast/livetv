# LiveTV 全仓架构审计报告（PROJECT_AUDIT）

> **审计基线**：`f9834b3`（`main` 分支） · **审计日期**：`2026-11-24` · **审计模式**：只读源码审计（未修改任何业务代码、构建脚本或既有业务文档）

---

## 1. 模块与代码规模盘点

LiveTV 采用单 `app` 模块 + 无 DI 框架设计（`compileSdk 34` / `targetSdk 34` / `minSdk 28` / Kotlin `1.9.24` / Media3 `1.4.1` / Leanback `1.0.0`）。

| 目录 / 包 | 文件数 | 代码行数 | 核心职责与代表类 |
|---|---:|---:|---|
| 根包 (`com.livetv.android`) | 2 | 147 | `LiveTvApplication`（全局懒加载装配）、`MainActivity`（TV/触控分流） |
| `domain/` | 14 | 637 | 纯 Kotlin 模型与领域抽象（`Channel`、`ChannelCatalogStore`、`CompositeCatalogCoordinator`、`StreamRefreshCoordinator`、`HeaderHostPolicy`） |
| `data/cctv/` | 2 | 113 | CCTV 静态目录加载与协调（`CctvChannelCatalog`、`CctvCatalogCoordinator`） |
| `data/mgtv/` | 12 | 1,071 | MGTV 目录抓取、签名、选源与流解析（`MgtvProvider`、`MgtvSigner`、`MgtvStreamResolver`、`MgtvHttpClient`） |
| `player/` | 8 | 1,208 | Media3 桥接与纯 Kotlin 播放策略（`PlayerManager` 777 行 + 7 个纯 Kotlin 策略/状态机文件） |
| `media/` | 3 | 574 | Media3 扩展（`ScopedHeaderDataSourceFactory`、`NoRetryOnClientErrorPolicy`、`Anime4kCnnGlEffect`） |
| `webplayer/` | 13 | 3,907 | 手机端 CCTV 隔离 WebView 播放器（`CctvWebPlayerActivity` 2,203 行 + 预热/缓存/拦截/聚焦脚本） |
| `ui/`（TV + 共用播放页） | 3 | 2,053 | `MainBrowseFragment`、`ChannelCardPresenter`、`PlayerActivity`（1,504 行） |
| `ui/mobile/` | 15 | 5,277 | `MobileMainActivity`（796 行）、`MobilePlayerChromeController`（803 行）、`MobilePlayerLoadingOverlay`（1,443 行）等 |
| `util/` | 8 | 494 | 设备判定、遥控器按键、手势数学、超分档位与焦点恢复纯策略 |
| **Main 源码合计** | **80** | **15,481** | **全为 Kotlin，无 Java 业务源码** |
| `app/src/test/` | 58 | — | 56 个 `*Test.kt` 测试套件（440 个 `@Test`）+ 2 个测试辅助类 |
| `app/src/androidTest/` | 0 | 0 | **目录不存在**，无任何 Android 仪器测试 |

---

## 2. 全量问题索引表（`AUD-01` ~ `AUD-22`）

| 编号 | 分级 | 性质标签 | 证据等级 | 位置 | 问题简述 |
|---|---|---|---|---|---|
| `AUD-01` | P0 | `[已确认缺陷]` | `[源码已证实]` | `CctvWebPlayerActivity.kt:293-324, 959-980` | CCTV 冷启动连续创建并立即销毁一个未使用的 `WebView` |
| `AUD-02` | P0 | `[潜在风险]` | `[源码已证实]` | `PlayerManager.kt:128-131, 257-271` | `resolutionExecutor` 未在 `stop()` / `onDestroy()` 关闭，每次销毁播放页泄漏线程 |
| `AUD-03` | P0 | `[已确认缺陷]` | `[源码已证实]` | `MgtvStreamResolver.kt:46,126` vs `PlayerManager.kt:57,120` | `MonotonicClock` 与 `SystemClock` 基准混用导致过期检查恒为 `true` |
| `AUD-04` | P0 | `[潜在风险]` | `[源码已证实]` | `app/proguard-rules.pro:1`；`release.yml:83-94` | `proguard-rules.pro` 为空且 CI 缺 Release 运行冒烟测试 |
| `AUD-05` | P0 | `[已确认缺陷]` | `[源码已证实]` | `AGENTS.md`、`docs/*.md` | 多份文档与当前源码存在显著漂移（已删类、单测数、Cctv/Release 门控） |
| `AUD-06` | P1 | `[优化建议]` | `[源码已证实]` | `PlayerActivity.kt:872-881`；`CctvWebPlayerActivity.kt:988-1016` | 切台 `PixelCopy` 截图串行阻塞新台网络解析与页面加载 |
| `AUD-07` | P1 | `[优化建议]` | `[源码已证实]` | `Anime4kCnnGlEffect.kt:160-165, 194-223` | `drawFrame()` 每帧循环调用 `glGetUniformLocation` 与字符串拼接 |
| `AUD-08` | P1 | `[已确认缺陷]` | `[源码已证实]` | `MainBrowseFragment.kt:53-80` | TV 首页缺少 `onResume()`，在播放页收藏后返回首页「收藏」行不更新 |
| `AUD-09` | P1 | `[潜在风险]` | `[源码已证实]` | `PlayerManager.kt:201`；`PlaybackReducer.kt:75` | `Player.STATE_ENDED` 映射为 `Idle`，直播流意外断开时停在黑屏无提示 |
| `AUD-10` | P1 | `[潜在风险]` | `[源码已证实]` | `CctvWebPlayerActivity.kt:1543, 1846` | `mainHandler.removeCallbacksAndMessages(null)` 误清同 Handler 过期定时器 |
| `AUD-11` | P1 | `[潜在风险]` | `[源码已证实]` | `PlayerManager.kt:666-679` | `scheduleReload()` 延时回调未校验 `generationGate.isCurrent(...)` |
| `AUD-12` | P1 | `[潜在风险]` | `[源码已证实]` | `MgtvStreamResolver.kt:51, 66, 119` | `lastNpuk` 为非线程安全 `HashMap`，依赖调用方单线程隐式串行 |
| `AUD-13` | P1 | `[优化建议]` | `[源码已证实]` | `MainBrowseFragment.kt:30-31`；`PlayerActivity.kt:46-56` | TV 首页与共用播放页反向依赖 `ui/mobile/` 下的共享类 |
| `AUD-14` | P1 | `[优化建议]` | `[源码已证实]` | `CctvWebPlayerActivity.kt`（2,203 行）；`PlayerActivity.kt`（1,504 行） | 双大 Activity 职责过重，手势数学与抽屉滚动逻辑存在重复 |
| `AUD-15` | P1 | `[已确认缺陷]` | `[命令已验证]` | `app/src/androidTest/`（缺失）；`CctvWebPrototypeGuardTest.kt:88-120` | 零仪器测试；Activity/WebView 崩溃恢复仅靠脆弱源码文本匹配 |
| `AUD-16` | P1 | `[潜在风险]` | `[源码已证实]` | `MgtvHttpClientTest.kt:48-99`；`ScopedHeaderDataSourceFactory.kt` | 缺少 302/401/429/5xx/读超时与 302 跨跳脱敏测试 |
| `AUD-17` | P2 | `[优化建议]` | `[源码已证实]` | `MobilePlayerLoadingOverlay.kt:413-433`；`CctvWebResourceBlocker.kt:148-150` | EPG 展示 UI 脚手架无调用方，且 CCTV EPG 接口被主动拦截 |
| `AUD-18` | P2 | `[优化建议]` | `[源码已证实]` | `MainBrowseFragment.kt` | TV 端缺少频道搜索入口与首页「继续观看」卡片 |
| `AUD-19` | P2 | `[优化建议]` | `[源码已证实]` | `PlayerActivity.kt:1304-1367` | Stats for Nerds OSD 面板标签与状态后缀硬编码在源码中 |
| `AUD-20` | P2 | `[优化建议]` | `[源码已证实]` | `PlayerManager.kt:312-324` | 超分切回 `OFF` 后仍保持 EGL 直通，未恢复硬件 `SurfaceView api=3` |
| `AUD-21` | P2 | `[优化建议]` | `[源码已证实]` | `CctvWebStaticResourceCache.kt` | `shouldInterceptRequest` 同步拉取无同 URL 并发去重 |
| `AUD-22` | P2 | `[优化建议]` | `[源码已证实]` | `.github/workflows/release.yml:40-50`；`debug/AndroidManifest.xml:5` | CI 重复安装 SDK 组件；Debug Manifest 与 `build.gradle.kts:75` 注释过时 |

---

## 3. P0–P2 核心问题源码证据详情

### `AUD-01` CCTV 冷启动双倍 WebView 创建与销毁
- **位置**：`app/src/main/java/com/livetv/android/webplayer/CctvWebPlayerActivity.kt:293-324, 959-980`
- **证据**：`onCreate()` 在 `:293-300` 调用 `buildWebView()` 创建 `created`、赋值给 `webView` 并添加到 `touchContainer`，紧接着在 `:324` 调用 `beginWebViewLoad(created, channelId, pageUrl, generation)`。而 `beginWebViewLoad()` 在 `:959` 判断 `if (webView === view)` 为 `true`，随即在 `:961` 再次调用 `buildWebView()` 创建第二个 `fresh` 实例，并在 `:977` 调用 `destroySingleWebView(view)` 直接销毁刚刚创建的 `created`。

### `AUD-02` `PlayerManager.resolutionExecutor` 线程泄漏
- **位置**：`app/src/main/java/com/livetv/android/player/PlayerManager.kt:128-131, 257-271`；`app/src/main/java/com/livetv/android/ui/PlayerActivity.kt:205, 371-385`
- **证据**：`PlayerActivity.onCreate()` 每次创建一个 `PlayerManager` 实例，后者成员初始化 `Executors.newSingleThreadExecutor`（线程名 `livetv-session-resolve`）。`PlayerManager.stop()`（`:269`）仅向其提交 `resetSession()`，全类与 `PlayerActivity.onDestroy()` 均未调用 `shutdown()` / `shutdownNow()`。

### `AUD-03` `MonotonicClock` 与 `SystemClock` 时钟基准混用
- **位置**：`app/src/main/java/com/livetv/android/data/mgtv/MgtvStreamResolver.kt:46, 126`；`app/src/main/java/com/livetv/android/player/PlayerManager.kt:57, 120`；`app/src/main/java/com/livetv/android/domain/stream/StreamRefreshCoordinator.kt:42-44`
- **证据**：`MgtvStreamResolver` 默认注入 `MonotonicClock`（`System.nanoTime() / 1_000_000L`）写入 `expiresAtMs`；`PlayerManager` 默认注入 `SystemClock`（`System.currentTimeMillis()`）传给 `StreamRefreshCoordinator`。在 `prepareForPlayback()` 中，`now`（~1.79e12 ms）永远大于开机单调时钟 `expiresAtMs`（~1e7 ms），导致 `isExpiringSoon()` 恒为 `true`。

### `AUD-04` 空 `proguard-rules.pro` 与缺少 Release 运行验证
- **位置**：`app/build.gradle.kts:58-59`；`app/proguard-rules.pro:1`；`.github/workflows/release.yml:83-94`
- **证据**：`release` 构建类型开启了 `isMinifyEnabled = true` 与 `isShrinkResources = true`，但 `proguard-rules.pro` 仅剩 1 行默认注释；`.github/workflows/release.yml` 仅执行 `assembleRelease` 与 `apksigner verify`，未在模拟器或真机做 Release APK 启屏/播放冒烟。

### `AUD-06` 切台 `PixelCopy` 串行阻塞新台网络解析
- **位置**：`app/src/main/java/com/livetv/android/ui/PlayerActivity.kt:872-881`；`app/src/main/java/com/livetv/android/webplayer/CctvWebPlayerActivity.kt:988-1016, 1106`
- **证据**：MGTV 切台时在 `mobileLoadingOverlay.beginChannelSwitch(...) { playerManager.switchChannel(channel) }` 的回调中才触发 `switchChannel`；CCTV 同样在 `captureLastFrameAndLoad` 的 `Choreographer` + `PixelCopy` 回调完成后才调用 `beginWebViewLoad()`，使网络解析/页面加载白白等待截帧完成。

### `AUD-07` `Anime4kCnnGlEffect.drawFrame()` 每帧 GL Uniform 查询与字符串分配
- **位置**：`app/src/main/java/com/livetv/android/media/Anime4kCnnGlEffect.kt:160-165, 194-223`
- **证据**：`drawFrame()` 在每帧、每个 pass（17–18 个）、每个 bind 上动态拼接 `"${bindName}_tex"` / `"${bindName}_size"` / `"${bindName}_pt"` 并调用 `GLES30.glGetUniformLocation(...)`，且每帧分配 `IntArray(1)` / `IntArray(4)`。

### `AUD-08` ~ `AUD-16` 核心证据索引
- `AUD-08`：`MainBrowseFragment.kt:53-80` 仅在 `onCreate()` 调用 `loadRows()` 读取 `MobileUiPreferences.getFavoriteChannelIds(ctx)`，缺少 `onResume()` 同步；对照 `MobileMainActivity.kt:149-154`。
- `AUD-09`：`PlayerManager.kt:201` 将 `Player.STATE_ENDED` 触发 `PlaybackEvent.Released` -> `PlaybackState.Idle`（`PlaybackReducer.kt:75`），直播意外结束会静默黑屏。
- `AUD-10`：`CctvWebPlayerActivity.kt:1543, 1846` 调用 `mainHandler.removeCallbacksAndMessages(null)`，会连带清除 `previousSlotExpireRunnable` 等调度。
- `AUD-11`：`PlayerManager.kt:666-679` 的 `scheduleReload()` 延时 Runnable 只检查 `currentChannel === expectedChannel`，未调用 `generationGate.isCurrent(generation)`。
- `AUD-12`：`MgtvStreamResolver.kt:51, 66, 119` 使用普通 `HashMap<String, String>` 存储 `lastNpuk`，而实例为 `LiveTvApplication.streamResolver` 进程单例。
- `AUD-13`：`MainBrowseFragment.kt:30-31`、`ChannelCardPresenter.kt:16`、`PlayerActivity.kt:46-56` 导入 `ui/mobile/` 下的 `MobileChannelList` 与 `MobileUiPreferences`。
- `AUD-14`：`CctvWebGestureController.kt:158-201` 复制了 `PlayerTouchGestureMath.kt:29-70` 的亮度/音量计算；`scrollDrawerToCurrentChannel()` 在 `PlayerActivity.kt:1446-1469` 与 `MobilePlayerChromeController.kt:612-628` 重复实现。
- `AUD-15` / `AUD-16`：详见 `TEST_GAP.md`。

---

## 4. 文档漂移清单（`AUD-05`）

| 文档 | 漂移位置 | 文档描述 | 当前源码真实状态 |
|---|---|---|---|
| `AGENTS.md` | §0 | 称 CCTV `streamUrl` 恒为空、播放要等 P2（VDN） | P2 VDN 已被裁定 NO-GO；手机端 CCTV 已通过 `webplayer/CctvWebPlayerActivity` 实装播放 |
| `AGENTS.md` | §1 | 规定 `ui/mobile/` 不得改动 `PlayerActivity`、`ui/` 不涉及 `webplayer/` | `PlayerActivity` 直接导入 11 个 `ui/mobile/` 类与 `webplayer/`；TV UI 导入 `ui/mobile/MobileChannelList` |
| `docs/ARCHITECTURE.md` | `:7-9, 24-32, 40-68, 71-280` | 顶部称目录仅来自 MGTV；称 `buildConfig=false`、`isMinifyEnabled=false`；包树缺失 `media/`、`webplayer/`；§3–§4 仍描述已删除的 `SubscriptionChannelRepository` / `M3uPlaylistParser` / `channels.json` | 实际为 `CCTV + MGTV` 双源；`buildConfig=true`、`isMinifyEnabled=true`；包树与仓库已全面迁移至 `CompositeCatalogCoordinator` + `ChannelCatalogStore` |
| `docs/TESTING.md` | `:10, 23-32, 201, 281-282` | 称有 `34`/`35` 个测试类（`319`/`306` 用例）并列出已删除的 M3U/Repository 测试类；称 CCTV 仅弹窗提示无地址 | 实际有 **56** 个 `*Test.kt`（**440** 个 `@Test`）；手机端 CCTV 通过 WebView 播放，TV 端提示 `tv_cctv_unsupported` |
| `docs/ROADMAP.md` | `:26-75` | 将已删除的 M3U/订阅类标为已实现；"明确没有的能力"仍列无搜索、收藏、历史、PiP、视频增强 | 搜索、收藏/历史、PiP、Anime4K 超分均已实装上线 |
| `docs/TV-INTERACTION.md` | `:126-152` | 描述旧版 `touch_overlay_container`、`overlayRetry`、`TOUCH_OVERLAY_AUTO_HIDE_DELAY_MS = 5000` | 已重构为 `MobilePlayerChromeController`（`view_mobile_player_chrome.xml`）与 `MobilePlayerLoadingOverlay` |
| `docs/CCTV-INTEGRATION.md` / `CCTV-MEDIA-PIPELINE.md` | `:34` / `:7` | 称 `cctv4k` 为 404 且列表不可播 | 已通过 `Cctv4kVodPool` + `assets/cctv_4k_vod_pool.json` 支持 `cctv4k` 轮播 |
| `app/build.gradle.kts` / `debug/AndroidManifest.xml` / `MobileMainActivity.kt` | `:75` / `:5` / `:252` | 注释称 `CctvWebPlayerActivity` 为 Debug 专用入口 | 实际在 Release 中对所有手机端 CCTV 频道开放，`AndroidManifest.xml` 无条件注册 |
