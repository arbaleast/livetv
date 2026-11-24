# LiveTV 技术债与重构决策清单（TECH_DEBT）

> 编号规范与详细代码证据见 [PROJECT_AUDIT.md](PROJECT_AUDIT.md)。本清单严格区分 **P0 / P1（完整 7 项评估）** 与 **P2（紧凑决策表）**。

---

## 1. P0 级技术债（必须优先处理）

### `AUD-01`：CCTV 冷启动双倍创建并销毁 WebView
1. **当前实现**：`CctvWebPlayerActivity.onCreate()` 先调用 `buildWebView()` 创建 `WebView` 并挂入 `touchContainer`，随即调用 `beginWebViewLoad(created, ...)`；而 `beginWebViewLoad()` 在 `webView === view` 时又创建第二个 `WebView` 并立即销毁第一个。
2. **存在的问题**：每次冷启动进入 CCTV 都白白初始化并销毁一整套 Chromium WebView 实例，直接拉高冷切首帧延迟（TTFF）与瞬时内存峰值。
3. **代码证据**：`app/src/main/java/com/livetv/android/webplayer/CctvWebPlayerActivity.kt:293-324, 959-980`。
4. **预期收益**：减少冷启动首台初始化耗时（省去一次完整 WebView 创建/销毁开销）与内存抖动。
5. **实施成本**：0.5 人日 / 低复杂度（增加 `replaceExisting: Boolean` 参数或冷启动首次加载分支跳过二次重建）。
6. **风险与回滚点**：风险低；需确认冷启动首次注入 `FOCUS_SCRIPT` 与 `WebViewClient` 状态一致，回滚仅需恢复 `beginWebViewLoad` 条件。
7. **是否值得实施**：**是**（零依赖、高收益、立竿见影改善 CCTV 冷切体验）。

### `AUD-02`：`PlayerManager.resolutionExecutor` 线程泄漏
1. **当前实现**：`PlayerManager` 构造时创建 `Executors.newSingleThreadExecutor`，`stop()` 仅提交 `resetSession()`，从不关闭线程池。
2. **存在的问题**：每次 `PlayerActivity` 销毁重建（例如手机端 MGTV ↔ CCTV 来回跨引擎切换、或退出重进）都会永久残留一条 `livetv-session-resolve` 线程与 `ExecutorService`。
3. **代码证据**：`app/src/main/java/com/livetv/android/player/PlayerManager.kt:128-131, 257-271`；`app/src/main/java/com/livetv/android/ui/PlayerActivity.kt:205, 371-385`。
4. **预期收益**：消除 `PlayerActivity` 生命周期级的线程与对象泄漏。
5. **实施成本**：0.5 人日 / 低复杂度（在 `PlayerManager` 新增 `release()` 调用 `resolutionExecutor.shutdownNow()`，并在 `PlayerActivity.onDestroy()` 调用）。
6. **风险与回滚点**：需确保 `onStop()` 后若 `onStart()` 复用了同一 `PlayerManager`，线程池不能在 `onStop()` 被关闭，只能在 `onDestroy()` 关闭。
7. **是否值得实施**：**是**。

### `AUD-03`：`MonotonicClock` 与 `SystemClock` 时钟基准混用
1. **当前实现**：`MgtvStreamResolver` 用 `MonotonicClock`（`nanoTime`）计算 `expiresAtMs`，`PlayerManager` 用 `SystemClock`（`currentTimeMillis`）传给 `StreamRefreshCoordinator` 做过期比较。
2. **存在的问题**：两套时钟差 ~10 个数量级，`isExpiringSoon()` 在非 `resetSession()` 路径下恒返回 `true`，一旦后续复用会话缓存会触发无意义重复刷新；同时掩盖了真实的 TTL 续期逻辑。
3. **代码证据**：`data/mgtv/MgtvStreamResolver.kt:46, 126`；`player/PlayerManager.kt:57, 120`；`domain/stream/StreamRefreshCoordinator.kt:42-44`。
4. **预期收益**：修复隐藏缺陷，确保动态地址 TTL 缓存与临期刷新判定准确可靠。
5. **实施成本**：0.2 人日 / 极低复杂度（将 `PlayerManager` 默认 `Clock` 统一为 `MonotonicClock` 并补充集成单测）。
6. **风险与回滚点**：几乎无风险，单行改动可随时回滚。
7. **是否值得实施**：**是**。

### `AUD-04`：空 `proguard-rules.pro` 与缺少 Release 运行冒烟
1. **当前实现**：`release` 开启 `isMinifyEnabled = true` 与 `isShrinkResources = true`，`proguard-rules.pro` 为空，CI 未做 Release 冒烟。
2. **存在的问题**：虽当前已靠手测 PKG110 验证，但后续一旦引入新反射、JS 桥接或动态资源查找，CI 无法在合并前发现 R8 误删。
3. **代码证据**：`app/build.gradle.kts:58-59`、`app/proguard-rules.pro:1`、`.github/workflows/release.yml:83-94`。
4. **预期收益**：防患 Release 线上崩溃与资源缺失。
5. **实施成本**：1 人日 / 低复杂度（在 `android-ci.yml` 增加 `assembleRelease`，整理显式 keep 规则并接入模拟器启动冒烟）。
6. **风险与回滚点**：需注意 GitHub Actions 免费 Runner 运行模拟器耗时，可放至 `release.yml` 或 nightly。
7. **是否值得实施**：**是**。

### `AUD-05`：`AGENTS.md` 与 `docs/` 文档系统性漂移
1. **当前实现**：多篇文档仍将已删除的 M3U/订阅类、旧版测试计数、旧 UI 组件名及 Debug-only 假设当成现状。
2. **存在的问题**：严重误导 AI Agent 与新协作者（尤其是 `AGENTS.md` §0–§1 的路由规则直接与当前 `PlayerActivity`/`webplayer` 冲突）。
3. **代码证据**：见 [PROJECT_AUDIT.md §4](PROJECT_AUDIT.md#4-文档漂移清单aud-05)。
4. **预期收益**：恢复「文档 100% 基于事实」基线，消除后续协作与自动化决策误判。
5. **实施成本**：1 人日 / 低复杂度（分批校准 `AGENTS.md` 与 `docs/*.md`）。
6. **风险与回滚点**：纯文档更新，零代码风险。
7. **是否值得实施**：**是**。

---

## 2. P1 级技术债（影响性能、双端体验与可维护性）

### `AUD-06`：切台 `PixelCopy` 串行阻塞新台加载
1. **当前实现**：MGTV 与 CCTV 切台均先等待 `Choreographer` + `PixelCopy` 截帧回调完成后，才启动 `switchChannel` / `beginWebViewLoad`。
2. **存在的问题**：每次切台白白增加 1 Vsync + `PixelCopy` 耗时（约 30–180ms）的串行阻塞，放大 TTFF。
3. **代码证据**：`ui/PlayerActivity.kt:872-881`；`webplayer/CctvWebPlayerActivity.kt:988-1016, 1106`。
4. **预期收益**：在保持平滑模糊过渡遮罩的同时缩短切台首帧耗时。
5. **实施成本**：1 人日 / 中复杂度（在发起 `PixelCopy` 的同时立即触发异步网络解析，仅将旧画面截图覆盖与新页面揭幕保持同步）。
6. **风险与回滚点**：需注意 `PixelCopy` 采样时机不得晚于旧 `PlayerView`/`WebView` 被新流清屏。
7. **是否值得实施**：**是**。

### `AUD-07`：`Anime4kCnnGlEffect.drawFrame()` 每帧 GL Uniform 查询开销
1. **当前实现**：17–18 个 pass 的每帧渲染循环中重复调用 `glGetUniformLocation` 与字符串拼接/小数组分配。
2. **存在的问题**：在手机端开启 AI 超分时增加不必要的 CPU/GL 驱动开销与 GC 压力。
3. **代码证据**：`app/src/main/java/com/livetv/android/media/Anime4kCnnGlEffect.kt:160-165, 194-223`。
4. **预期收益**：降低超分模式下的主/GL 线程 CPU 占用与帧抖动。
5. **实施成本**：0.5 人日 / 低复杂度（在 `configure()` 阶段预缓存所有 pass 的 uniform location，复用 `IntArray` 成员）。
6. **风险与回滚点**：需在真机实测验证 `STANDARD`/`STRONG` 两种超分档位渲染无异常。
7. **是否值得实施**：**是**。

### `AUD-08`：TV 首页 `MainBrowseFragment` 缺少 `onResume()` 同步收藏状态
1. **当前实现**：`MainBrowseFragment` 仅在 `onCreate()` 加载分类行与收藏列表；在 `PlayerActivity` 中切换收藏后返回首页，收藏行不会刷新。
2. **存在的问题**：TV 端与手机端收藏状态体验不一致，用户需要手动点击「重新加载频道」才能看到变化。
3. **代码证据**：`app/src/main/java/com/livetv/android/ui/MainBrowseFragment.kt:53-80`（对比 `MobileMainActivity.kt:149-154`）。
4. **预期收益**：TV 端收藏切换即刻同步，双端一致。
5. **实施成本**：0.5 人日 / 低复杂度（增加 `onResume()` 检测 `favoriteIds` 变更后局部重建/刷新 `收藏` 行并保留焦点）。
6. **风险与回滚点**：需注意不要在每次 `onResume()` 全量重置行导致 Leanback 焦点闪烁，应对比前后 `favoriteIds` 集合。
7. **是否值得实施**：**是**。

### `AUD-09` ~ `AUD-14`：稳定性与耦合类技术债摘要（含 7 项结论）

| 编号 | 1) 当前实现 | 2) 存在的问题 | 3) 代码证据 | 4) 预期收益 | 5) 成本 | 6) 风险/回滚 | 7) 是否值得实施 |
|---|---|---|---|---|---|---|---|
| `AUD-09` | `STATE_ENDED` 映射为 `Idle` | 直播流意外结束时黑屏无重试无提示 | `PlayerManager.kt:201`、`PlaybackReducer.kt:75` | 异常断流可自动重试或提示错误 | 0.5 人日 | 注意退出时 `stop()` 不误触发 | **是** |
| `AUD-10` | `removeCallbacksAndMessages(null)` | 误清同 Handler 上 `previousSlotExpireRunnable` | `CctvWebPlayerActivity.kt:1543, 1846` | 防止异常分支后旧槽位永不过期泄漏内存 | 0.5 人日 | 使用 token 或显式 `removeCallbacks` | **是** |
| `AUD-11` | `scheduleReload()` 只比对 channel 引用 | 连续重切同一频道时未拦截旧代次延迟回调 | `PlayerManager.kt:666-679` | 消除同台快速重连竞态 | 0.2 人日 | 无风险 | **是** |
| `AUD-12` | `lastNpuk` 为普通 `HashMap` | 进程单例非线程安全 | `MgtvStreamResolver.kt:51,66,119` | 消除并发扩展时的潜在 `ConcurrentModificationException` | 0.1 人日 | 换为 `ConcurrentHashMap` | **是** |
| `AUD-13` | TV 与 `PlayerActivity` 导入 `ui/mobile/` | 包依赖反向耦合 | `MainBrowseFragment.kt:30-31`、`PlayerActivity.kt:46-56` | 理清包边界，符合文档分层 | 1 人日 | 仅搬迁包名，需同步单测 | **暂缓**（待功能稳定后做包整理） |
| `AUD-14` | 双大 Activity + 重复手势/抽屉代码 | 单文件过长（2,203 / 1,504 行），维护成本上升 | `CctvWebPlayerActivity.kt`、`PlayerActivity.kt`、`CctvWebGestureController.kt` | 提升可读性、消除手势数值分叉 | 2–3 人日 | 拆错易引入生命周期回归 | **暂缓**（先收口手势公式即可，勿大规模拆分） |

> `AUD-15` 与 `AUD-16`（测试缺口）的完整 7 项评估见 [TEST_GAP.md](TEST_GAP.md)。

---

## 3. P2 级与低优先级优化建议

| 编号 | 位置 | 问题简述 | 是否值得实施 |
|---|---|---|---|
| `AUD-17` | `MobilePlayerLoadingOverlay.kt:413-433`、`CctvWebResourceBlocker.kt:148-150` | EPG 展示 UI 脚手架无人调用且 CCTV EPG 被拦截；暂无公开稳定免费 EPG 源 | **否**（暂不投入 EPG，除非确定稳定数据源） |
| `AUD-18` | `MainBrowseFragment.kt` | TV 端缺少频道搜索与首页「继续观看」卡片 | **暂缓**（TV 当前约 12 个 MGTV 台，遥控器直选成本低；后续频道扩容后再加） |
| `AUD-19` | `PlayerActivity.kt:1304-1367` | Stats for Nerds 面板含硬编码文本 | **暂缓**（当前仅中文单语言，低优先级） |
| `AUD-20` | `PlayerManager.kt:312-324` | 超分切回 `OFF` 保留 EGL 直通而非恢复 `SurfaceView api=3` | **暂缓**（Media3 运行时切换 Effects 机制限制，重建播放器代价过高） |
| `AUD-21` | `CctvWebStaticResourceCache.kt` | `shouldInterceptRequest` 对同 URL 并发拉取未去重 | **否**（预热延迟 1.8s 启动，实测碰撞概率极低） |
| `AUD-22` | `.github/workflows/release.yml:40-50`、`debug/AndroidManifest.xml:5` | CI 重复安装 SDK 组件、过时注释清理 | **是**（顺手清理，耗时 < 0.2 人日） |
