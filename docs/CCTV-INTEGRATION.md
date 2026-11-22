# CCTV-INTEGRATION — CCTV 官方直播接入（P0 架构归档）

> **最终结论（2026-11-22 归档）**：
> - **原生 VDN + Media3 ExoPlayer 路线：NO-GO**。实测移动端与桌面端官方直播页均依赖 `live.worker.js` WASM DRM 及约 20 个官方外围脚本完成自举与流授权，按合规红线（G2/G5）不可直连 Media3，P2–P4 原生解析路线正式终止。
> - **已落地替代方案（仅手机端）**：隔离包 `app/src/main/java/com/livetv/android/webplayer/` + P1 静态目录（`assets/cctv_channels.json` + `CompositeCatalogCoordinator` 分槽合并）。Android TV 端保持克制兜底，不引入 WebView。
> - **合规红线**：不绕过 DRM / HDCP / 任何访问控制；不使用 Cookie 或他人会话；不模拟用户点击或强改播放状态。

---

## 0. 最终架构裁决与已落地实现（2026-11-22）

### 0.1 原生 VDN + Media3 路线：NO-GO 结论

| 闸门 | 判定 | 实证结论 |
|---|---|---|
| **G1（认证与自举）** | **未通过（NO-GO）** | 移动端与桌面端播放流程均强依赖约 20 个官方外围脚本与 `live.worker.js` WASM 模块协同自举，无公开无状态签名接口 |
| **G2（标准明文/AES-128 可播流）** | **未通过（NO-GO）** | 实际直播流走 `live.worker.js` WASM DRM 链路，Media3 原生不可直接播放 |
| **G5（合规红线）** | **触发终止** | 逆向/复刻 WASM DRM 或外围风控违反合规红线，正式终止 P2–P4 原生解析路线 |

### 0.2 已落地的手机端隔离方案（`webplayer/`）

播放引擎核心（`PlayerManager`、`RoutingStreamResolver`、`StreamRefreshCoordinator`、`data/mgtv/`、`media/`）**零侵入**；手机端 CCTV 能力全部收口在 `com.livetv.android.webplayer` 与 P1 目录层：

| 模块 | 职责与实现要点 |
|---|---|
| P1 目录与多源聚合 | `assets/cctv_channels.json`（仅静态元数据，无任何 URL）→ `CctvChannelCatalog` / `CctvCatalogCoordinator` + `CompositeCatalogCoordinator` / `ChannelCatalogMerger` 与 MGTV 分槽合并写入 `ChannelCatalogStore` |
| 频道与安全白名单 | `CctvWebChannel`（仅放行已验证 200 的 `cctv1`–`cctv17` 官方直播页 `https://tv.cctv.com/live/cctvN/`）；`CctvWebNavigationPolicy`（Fail-Closed，仅允许 `https://tv.cctv.com` 及严格子域）；`CctvWebUserAgent`（仅剔除 `; wv`） |
| 两阶段纯视频视口聚焦 | `CctvWebViewportFocusScript`（静态常量脚本）：阶段一固定 `#player` 为 `100vw×100vh` 黑底并隐藏外围兄弟至 `document.body`，保持 `object-fit:contain !important`；`MutationObserver` 280ms 节流并在就绪后收缩至 `document.body`（`subtree: false`）；阶段二通过 `playing`/`timeupdate` 与播放态检查置位 `window.__livetvPlaying`，从 `#player` 内媒体节点向上遍历至 `#player` 边界标记 `.livetv-pure-control-hidden`，由 `html.livetv-pure-video #player .livetv-pure-control-hidden{opacity:0 !important;pointer-events:none !important;}` 隐藏网页控制层（**严禁**对内部控件用 `display:none`，不搬移 DOM、不模拟点击） |
| 播放页交互与手势 | `CctvWebPlayerActivity` + `CctvWebGestureController`：原生黑幕过渡 + `awaitingPageCommit` 屏障与 7s 超时兜底；包裹 `WebView` 的 `CctvTouchContainer`（`onInterceptTouchEvent` 三态分流：左半屏亮度 `[0.01f, 1.0f]`、右半屏音量浮点累加器 `[0, maxVolume]` 与居中 HUD，纯视频态单击切换 3.5s 自动隐藏的渐变顶栏，待起播与「原控」态单击透传网页并保持顶栏常驻） |
| 双向选台与生命周期 | `CctvWebSwitchPolicy` + 复用 `ChannelDrawerModel`/`ChannelDrawerAdapter` 右侧抽屉：CCTV 1–17 原地 `stopLoading()` + `loadUrl()` 复用 `WebView`（不重建 Activity）；跨源切 MGTV/IPTV 前严格销毁 `WebView` 再拉起 `PlayerActivity`；`PlayerActivity` 仅在手机触控端选 `cctv:` 时停止 ExoPlayer 并转交 `CctvWebPlayerActivity`；`WebSettings.LOAD_DEFAULT`、`onPause/onResume` 成对 `pauseTimers()/resumeTimers()`、`onRenderProcessGone` 彻底移树并 `.destroy()` |

### 0.3 已知边界与真机基线（OnePlus PKG110 / Android 16）

- **`cctv4k` / `cctv8k`**：官方直播页实测 404，入口与抽屉均提前短路提示「该频道暂不支持网页播放」，不拉起 Chromium。
- **首次起播 / 切台交互**：受合规红线约束（严禁模拟 `.click()` / `.play()`），需用户轻点一次官方播放键起播；起播后自动进入纯视频态并隐藏官方控制栏。
- **Android TV 端**：不引入 WebView；`MainBrowseFragment` 主页与 `PlayerActivity` 侧边选台/上下切台均经 `TvChannelRoutePolicy` 拦截 `cctv:` 频道并弹出「电视端暂不支持央视频道播放」，不打断当前页面或正在播放的 MGTV/IPTV 节目。
- **真机性能基线（PKG110，稳定播放 `cctv1` 30s 后采集）**：
  - 主进程 `com.livetv.android`：**TOTAL PSS = 144.0 MB（147,418 KB）** / 前台 CPU **60.7%**（单核口径，8 核整机约 7.6%）；
  - WebView 渲染进程 `sandboxed_process0`：**TOTAL PSS = 243.0 MB（248,881 KB）** / 前台 CPU **114.0%**（8 核整机约 14.3%）；
  - 按 Home 键切后台 4s 后：主进程与渲染进程 CPU 分别降至 **7.1% / 17.8%**（音频与 JS 定时器已暂停），回前台恢复无崩溃。

---

> 以下 §1–§7 保留为 P0 阶段针对原生 VDN 路线的原始审计与设计历史记录（该路线已由 §0 正式裁定 NO-GO）。

## 1. 源码审计结论与复用映射表

### 1.1 A 组：既有架构与复用点

#### A1 路由与装配

- `domain/stream/RoutingStreamResolver.kt:11-29`：按**单一前缀** `mgtv:` 路由，其余走
  `StaticStreamResolver`；前缀常量见 `:28`。当前没有多来源路由能力，CCTV 必须扩展该分支，
  但不能改变 `mgtv:` 与静态源的行为。
- `LiveTvApplication.kt:31/38/61`：手动 `by lazy` 装配 `catalogStore` /
  `mgtvCatalogCoordinator` / `streamResolver`，**无 DI 框架**；新增 CCTV 也按此方式追加。
- `PlayerManager.kt:320-336`：`resolveSession()` 对**静态频道解析失败**回退到
  `channel.streamUrl`；对 `streamUrl` 为空的动态频道直接 `Failed`。CCTV 频道必须
  `streamUrl=""`（动态源），否则会被静态回退逻辑误处理。

#### A2 参考标杆：`data/mgtv/` 的分层

| 关心点 | MGTV 现状（可作为 CCTV 对齐基准） |
|---|---|
| 端点/参数 | `MgtvEndpoints.kt`：URL 构造与参数集分离，纯 Kotlin |
| 认证/签名 | `MgtvSigner.kt`：纯本地哈希，独立于 HTTP；**签名算法因源而异，CCTV 不得套用** |
| HTTP 客户端 | `MgtvHttpClient.kt`：JDK `HttpURLConnection`，5s 连接 / 8s 读取 / 2MB 上限 / 禁止自动重定向 / `finally disconnect()` |
| 抓取器 | `HttpMgtvSourceFetcher.kt`：只负责"参数+签名→请求→原始字符串"，便于 fixture 注入 |
| 解析 | `MgtvJsonParser.kt`：纯函数，`org.json`，脱敏 fixture 单测 |
| 选源 | `MgtvSourceSelector.kt`：白盒硬过滤 + 确定性清晰度回退 |
| 会话 | `MgtvStreamResolver.kt`：错误映射 + TTL + `ResolvedStream` 输出 |
| 目录 | `MgtvProvider.kt` + `MgtvCatalogCoordinator.kt`：目录→领域分类，后台线程 + 主线程回调 |

**结论**：CCTV 模块按同一职责切分（端点 / 认证 / HTTP / 抓取 / 解析 / 选源或清单 / Resolver / 目录），
类名以 `Cctv` 前缀，放在 `data/cctv/`。

#### A3 动态刷新与切台防竞态

- `StreamRefreshCoordinator.kt:30-40`：同频道且未临期（`ResolvedStream.DEFAULT_REFRESH_LEAD_MS = 60s`）
  复用缓存；否则重新解析。
- `StreamRefreshCoordinator.kt:60-79`：**403 最多重解析 1 次**（`MAX_FORBIDDEN_REFRESH_ATTEMPTS = 1`），
  429 立即 `FastFail`，其余状态码 `DelegateToRetryPolicy`。
- `SessionGenerationGate.kt:14-27` + `PlayerManager.kt:236-248/261-274`：每次解析 `next()` 代际号，
  回主线程时 `isCurrent()` + `currentChannel === channel` 双校验，旧结果丢弃。
- `PlayerManager.kt:117-121`：解析在**单线程** `resolutionExecutor`（`livetv-session-resolve`）执行；
  **没有请求级取消**——切台只是丢弃结果，慢请求仍占着线程（见 §5 风险 R4）。

#### A4 目录聚合

- `ChannelCatalogStore.kt:10-28`：**当前只保存一份快照**（`update` / `getAllCategories` / `isEmpty`），
  不再有"订阅段 + 补充段"。这是上一轮 MGTV-only 重构的结果。
- `MgtvCatalogCoordinator.kt:37-45`：后台 `ExecutorService` 拉目录，**主线程回调**；
  失败返回空列表。
- 手机 `MobileMainActivity.loadChannels()/reloadMgtvCatalog()`、TV
  `MainBrowseFragment.reloadMgtvCatalog()`、播放页 `PlayerActivity.ensureMgtvCatalogLoaded()`
  都在写/读同一个 `catalogStore`。
- **对 CCTV 的影响**：P1 若直接 `update(cctvList)` 会**覆盖掉 MGTV 目录**。
  必须引入**分槽聚合协调器 + 合并器**两层（§3.4 给出 `CompositeCatalogCoordinator` /
  `ChannelCatalogMerger` 方案），这是本轮审计最重要的架构差异。

#### A5 现有测试体系

- 测试目录：`app/src/test/java/com/livetv/android/{data/mgtv,domain,player,ui,util}`，全部纯 JVM。
- 离线 HTTP 桩：`data/mgtv/TinyHttpServer.kt`（`ServerSocket` 手写，100% 离线）。
- 脱敏 fixture：`data/mgtv/MgtvSanitizedFixtures.kt`（`.invalid` 域名、人造签名）。
- 源码守卫：`data/mgtv/MgtvEndpointsTest.kt:104-113` 扫描 `data/mgtv/`，禁止 32 位十六进制疑似 sign、
  硬编码 IP、`Cookie:` 等。
- TDD 要求：`docs/TESTING.md:100`「新测试必须先在空实现/旧实现上跑出失败，再让实现通过」。
- **CCTV 对齐要求**：新增 `data/cctv/` 时同样建立脱敏 fixture + 源码守卫 + 先红后绿证据。

### 1.2 B 组：Android / Media3 专项风险

#### B6 跨域重定向请求头隔离（关键）

- 构造：`PlayerManager.kt:428-441` —— 仅当 `ResolvedStream.allowedHeaderHosts` 非空时使用
  `ScopedHeaderDataSourceFactory`，且 delegate 设 `setAllowCrossProtocolRedirects(false)`；
  空白名单（普通 IPTV）维持 `true`，行为不变。
- 包装器：`media/ScopedHeaderDataSourceFactory.kt:46-60` 每次 `open()` 按目标 URL
  `HeaderHostPolicy.filterHeadersForUrl` 重新过滤；`:100-118` 自行跟随 3xx，
  每一跳都用 `HeaderHostPolicy.isHostAllowed(target, allowedHostSuffixes, requireHttps = false)`
  校验 Host，非白名单目标**直接失败**而不是继续跳转。
- 白名单匹配：`domain/stream/HeaderHostPolicy.kt:35-60` —— 精确域 / 子域匹配、拒绝
  `userinfo@host`、默认强制 https。
- **已知残留（必须写进 CCTV 设计）**：Media3 1.4.1 的 `DefaultHttpDataSource` 会**内部跟随同协议 302**，
  此时包装器拿不到跳转、沿用原 `DataSpec` 头（`docs/ARCHITECTURE.md:500`、技术债 `:575`）。
  即：若 CCTV 的 `auth-key` 以**请求头**形式发给 VDN 域名，而 VDN 同协议 302 到 CDN，
  头可能被带到 CDN —— 这正是 P3 必须规避的泄漏面。

#### B7 JSON 解析与纯 JVM 测试前提

- `app/build.gradle.kts` 已声明 `testImplementation("org.json:json:20231013")`，
  注释说明 Android `android.jar` 里 `org.json.*` 是抛 `not mocked` 的 Stub。
- 现有解析器（`MgtvJsonParser.kt`）直接使用 `org.json` 并可在 `testDebugUnitTest` 运行，
  因此 **CCTV 解析器沿用同一方案即可**，无需新增依赖。

#### B8 `HttpURLConnection` 超时与取消

- `MgtvHttpClient.kt:40-75`：`connectTimeout=5000`、`readTimeout=8000`（`:85-88` 常量）、
  `instanceFollowRedirects=false`、`finally { disconnect() }`、响应体 2MB 上限。
- **取消能力**：没有跨线程取消句柄；`PlayerManager` 靠代际号丢弃结果（A3）。
  单线程解析器意味着一个慢请求会**阻塞后续切台解析**（MGTV/普通 IPTV 也会被拖慢）。
- CCTV 对齐（**必选约束**，非可选增强）：
  1. `CctvHttpClient` 采用更短超时（**connect 3s / read 5s**）；
  2. **P4 必须实现切台主动取消**：对在途 `HttpURLConnection` 触发 `disconnect()`，
     或把请求放进隔离的可取消任务；仅"代际号丢弃结果"不足以释放单线程解析器。

#### B9 错误模型映射

- 统一错误枚举：`domain/stream/StreamResolveResult.kt:6-27`
  （`UNSUPPORTED_SOURCE / HTTP_FORBIDDEN / HTTP_NOT_FOUND / HTTP_RATE_LIMITED / PARSE_ERROR /
  NETWORK_ERROR / AUTH_UNAVAILABLE`）。`AUTH_UNAVAILABLE` 已存在（`:26`），无需新增错误类型。- 播放侧：`PlayerManager.mapException()`（`:585-640`）只认 `InvalidResponseCodeException` 的
  **真实响应码** + `PlaybackErrorMapper`（`player/PlaybackErrorMapper.kt:31-62`）分类；
  `RetryPolicy.FAST_FAIL_HTTP_STATUSES = {401,403,429}`（`RetryPolicy.kt:40`），
  `media/NoRetryOnClientErrorPolicy.kt` 阻止 Media3 底层对这组状态码二次重试。
- 结论：CCTV 的 403/404/429/5xx 全部映射到上述既有枚举，**禁止新增私有错误体系**。

#### B10 TTL 缺省降级

- `ResolvedStream.kt:15-22`：`expiresAtMs: Long?`，`null` = 静态不过期；`:26-38` 提供
  `isExpiringSoon/isExpired`（提前 60s 视为临期）。
- MGTV 的做法（可作为"有显式 TTL"的参照）：`MgtvStreamResolver.kt:121-137`
  —— 响应 `urlExpireDuration` 为正则采用并钳制 7 天，否则回落 **60s 保守 TTL**
  （`:143-151` 常量）。**注意：60s 是 MGTV 自己的兜底常量，不可套用到 CCTV。**
- `PlayerManager.resolveSession()`（`:320-336`）里 `dynamic = stream.expiresAtMs != null ||
  channel.streamUrl.isBlank()`，因此 **`streamUrl` 为空的 CCTV 频道即使 `expiresAtMs=null`
  也会被当作动态会话**，403 时仍会走 `StreamRefreshCoordinator` 的"最多刷新 1 次"。
- 结论（CCTV 策略）：**有显式过期字段才写 `expiresAtMs`；没有则写 `null`，禁止臆造固定 TTL**，
  依靠 403 被动刷新（§3.5）。

### 1.3 复用映射表

| 既有能力 | 位置 | CCTV 复用方式 | 是否改动 |
|---|---|---|---|
| 路由 | `RoutingStreamResolver` | 增加 `cctv:` → CCTV Resolver 分支，`mgtv:`/fallback 不变 | 改（P4） |
| 会话模型 | `ResolvedStream` | 直接输出（url/headers/expiresAt/mimeType/allowedHeaderHosts） | 不改 |
| 错误枚举 | `StreamResolveError` | 直接复用，含 `AUTH_UNAVAILABLE` | 不改 |
| 刷新/防竞态 | `StreamRefreshCoordinator` + `SessionGenerationGate` | 直接复用 | 不改 |
| 头隔离 | `ScopedHeaderDataSourceFactory` + `HeaderHostPolicy` | 直接复用（CDN Host 白名单） | 不改（同协议 302 残留见 R2） |
| 播放桥接 | `PlayerManager` | 零改动 | 不改 |
| 目录 | `ChannelCatalogStore` | 分槽聚合协调器 + 合并器（§3.4） | 不改 store，新增 `CompositeCatalogCoordinator` / `ChannelCatalogMerger` |
| 装配 | `LiveTvApplication` | 追加 CCTV 目录协调器、聚合协调器与路由装配 | 改（P1/P4） |
| 测试设施 | `TinyHttpServer` / 脱敏 fixture / 源码守卫 | 复制同类模式到 `data/cctv` | 新增（P6） |

---

## 2. 协议验证状态表

> 判定标准：只有仓库内存在**代码、已提交样本或文档断言**并能在当前环境复现的，才算 `VERIFIED`。

| # | 项目 | 状态 | 证据 / 缺失原因 |
|---|---|---|---|
| V1 | 路由/前缀机制 | **VERIFIED** | `RoutingStreamResolver.kt:11-29` |
| V2 | 动态会话与 403 单次刷新 | **VERIFIED** | `StreamRefreshCoordinator.kt:60-79` |
| V3 | 头隔离与重定向校验 | **VERIFIED（含残留）** | `ScopedHeaderDataSourceFactory.kt:46-118`；同协议 302 残留见 `ARCHITECTURE.md:575` |
| V4 | JSON 纯 JVM 测试可行性 | **VERIFIED** | `build.gradle.kts` 的 `org.json:json` 测试依赖；`MgtvJsonParserTest` 全绿 |
| V5 | `AUTH_UNAVAILABLE` 语义 | **VERIFIED** | `StreamResolveResult.kt:26`；`MgtvStreamResolver.kt:69-72` |
| U1 | VDN v3 端点域名/路径/版本参数 | **UNVERIFIED** | 仓库无任何 vdn / auth-key / hls_cdrm 代码或样本（全仓 grep 仅命中测试里的示例频道名） |
| U2 | 请求参数集合与顺序 | **UNVERIFIED** | 无抓包 |
| U3 | `auth-key` 生成方式/来源/失效条件 | **UNVERIFIED** | 无代码、无抓包、无官方 SDK 证据；**禁止猜测** |
| U4 | 响应 JSON 字段结构（code/data/streams/cdn/expiry） | **UNVERIFIED** | 无样本 |
| U5 | 过期时间字段与单位（TTL） | **UNVERIFIED** | 无样本；不得套用 MGTV 的 60s |
| U6 | `manifest.hls_cdrm` 语义与加密方式 | **UNVERIFIED** | 无样本；见 §5 R1 |
| U7 | CDN 域名清单与所需请求头 | **UNVERIFIED** | 无样本 |
| U8 | 302/301 行为与目标域 | **UNVERIFIED** | 无抓包 |
| U9 | 频道目录来源（`cctv:cctv1` 等 ID 映射） | **UNVERIFIED** | 需人工提供权威频道表 |
| U10 | 是否存在 WASM 参与（解密/拼装） | **UNVERIFIED** | 见 `CCTV-MEDIA-PIPELINE.md` |

---

## 3. 架构设计与数据流方案

### 3.1 目标数据流

```
Channel(cctv:cctv1, streamUrl="")
  └─ RoutingStreamResolver("cctv:")                      [P4 修改]
       └─ CctvStreamResolver                             [P2]
            ├─ CctvVdnFetcher  → CctvAuth(?) → CctvHttpClient → VDN v3
            ├─ CctvJsonParser → CctvVdnResponse
            ├─ CctvManifestParser / CctvCdnPolicy        [P3，按需触发，见 §3.7]
            └─ ResolvedStream(url, headers, expiresAtMs?, mimeType, allowedHeaderHosts)
  └─ PlayerManager（零改动）→ DefaultMediaSourceFactory → Media3 HLS
```

> **起播主路径不预抓 `.m3u8`**：标准 Master Playlist 直接交给 Media3 原生 HLS 模块处理，
> 仅在 §3.7 的触发条件成立时才调用 `CctvManifestParser`。

### 3.2 与 `data/mgtv/` 的对齐

| 层 | MGTV | CCTV（建议） | 说明 |
|---|---|---|---|
| 端点 | `MgtvEndpoints` | `CctvEndpoints` | 只放公开 URL/参数名，**不含任何密钥** |
| 认证 | `MgtvSigner`（纯哈希） | `CctvAuth` | **算法必须来自实证**；无证据时返回 `AUTH_UNAVAILABLE`，不得伪造 |
| HTTP | `MgtvHttpClient` | `CctvHttpClient` | 同样的超时/禁自动重定向/上限/断开 |
| 抓取 | `HttpMgtvSourceFetcher` | `CctvVdnFetcher` | 只产出原始字符串或显式错误 |
| 解析 | `MgtvJsonParser` | `CctvJsonParser` | 纯函数 + 脱敏 fixture |
| 选源 | `MgtvSourceSelector` | `CctvCdnPolicy` / `CctvManifestParser` | 优先复用 Media3 HLS，不重复造播放器；清单解析仅在 §3.7 条件成立时触发 |
| 会话 | `MgtvStreamResolver` | `CctvStreamResolver` | 输出 `ResolvedStream`，错误映射到既有枚举 |
| 目录 | `MgtvProvider`+`MgtvCatalogCoordinator` | `CctvChannelCatalog`+`CctvCatalogCoordinator` | 静态目录，后台加载 |

### 3.3 错误映射表

| 场景 | CCTV 内部结果 | `StreamResolveError` | 播放侧表现 |
|---|---|---|---|
| 无授权能力 / `auth-key` 缺失 | `AuthUnavailable` | `AUTH_UNAVAILABLE` | Fatal，UI 走既有错误文案 |
| VDN 403 / 签名失效 | `HttpError(403)` | `HTTP_FORBIDDEN` | 动态会话 → 重解析 1 次 → 仍失败 FastFail |
| 404 | `HttpError(404)` | `HTTP_NOT_FOUND` | Fatal |
| 429 | `HttpError(429)` | `HTTP_RATE_LIMITED` | 立即 FastFail（禁止无限重试） |
| 5xx | `HttpError(5xx)` | `NETWORK_ERROR`（现状） | 交回 `RetryPolicy` 退避；如需细分需评审 |
| JSON 损坏 | `ParseError` | `PARSE_ERROR` | Fatal |
| 只有受保护流 | `Unsupported` | `UNSUPPORTED_SOURCE` | Fatal，提示不支持 |

> 注：5xx 目前没有独立枚举（`MgtvStreamResolver.kt:79-87` 把它归为 `NETWORK_ERROR`）；
> CCTV 若需要区分 5xx 文案，必须先评审是否扩展 `StreamResolveError`，**P0 不建议新增**。

### 3.4 目录合并与分槽状态（本轮审计新增的必改点）

现状 `ChannelCatalogStore` 只有一份快照（A4）。CCTV 与 MGTV 的目录由**两个独立异步协调器**加载，
若各自直接 `update()` 会互相覆盖；仅靠一个纯函数合并器也无法解决"谁先回来、谁后回来"的时序问题。
因此必须引入**分槽状态持有者 + 纯函数合并器**两层：

```
LiveTvApplication
 ├─ cctvCatalogCoordinator ──最新 CCTV 快照──┐
 ├─ mgtvCatalogCoordinator ──最新 MGTV 快照──┤→ CompositeCatalogCoordinator（按来源分槽）
 │                                            │      └─ ChannelCatalogMerger.merge()
 └─ catalogStore ←────────────────────────────┘            （纯函数）
```

- `CompositeCatalogCoordinator`（新增）：按来源**分槽**持有 `latestCctv` / `latestMgtv` 原始快照；
  任一来源更新时，在**同一同步边界**（主线程回调或 `@Synchronized`）内调用
  `ChannelCatalogMerger` 合并，再 `catalogStore.update(merged)`。
  这样 CCTV 晚于 MGTV 返回时不会覆盖 MGTV，反之亦然；任一来源失败（空列表）只更新自己的槽，
  另一槽保持不变，**不得整体清空**。
- `ChannelCatalogMerger`（纯函数，可单测）：
  - **分类 id 必须来源命名空间隔离**（CCTV 统一 `cctv:<categoryId>`、MGTV 统一 `mgtv:<categoryId>`）；
  - **禁止"按分类 id 去重丢弃后项"**——两源若复用通用分类 id（如 `news`），那样会整组吞掉频道。
    正确做法是按"来源段 + 段内顺序"拼接；确需合并同 id 时，合并 `channels` 后**按 `channel.id` 去重**；
  - 丢弃空分类；两段顺序固定（CCTV 在前、MGTV 在后）。
- `ChannelCatalogStore` **保持单快照不变**（不推翻上一轮简化）。
- 备选方案（不推荐）：给 store 恢复双段 API —— 与上一轮"去掉订阅段"的重构相冲突。

### 3.5 TTL 降级策略

1. VDN 响应若含**可验证**的过期字段 → 写入 `expiresAtMs`（单位/字段名以抓包为准，禁止猜测）；
2. 无该字段 → `expiresAtMs = null`（不做任何固定 TTL 推断）；
3. 播放中 403 → `StreamRefreshCoordinator` 允许重解析 1 次并重连；
4. 重连再次 403 / 429 → FastFail（一次 403 不放大成多次请求，`NoRetryOnClientErrorPolicy` 已兜底）；
5. 起播前若缓存会话已过期 → 先刷新再起播（复用既有 `prepareForPlayback`）。

### 3.6 301/302 请求头安全方案（针对 `auth-key`）

**原则：`auth-key` 只允许出现在"对 VDN 域名"的那一次请求上，绝不允许出现在播放/CDN 请求里。**

1. **首选**：VDN 返回的播放地址自带查询参数（URL 级令牌），播放请求**不携带任何 CCTV 专用头**；
   这样即使 CDN 302，也不会泄漏头（头根本不存在）。
2. 若播放确实需要请求头 → 只能声明**播放用途**的头（如 `Referer`/`User-Agent`），
   且 `allowedHeaderHosts` 只放行经抓包证实的 CDN 后缀；
3. 若 `auth-key` 不得已要以头形式发送到 VDN：
   - 该请求必须走 `CctvHttpClient`（`instanceFollowRedirects=false`），**不由 Media3 发包**；
   - 3xx 一律不自动跟随；若业务需要跟随，只能在 `CctvVdnFetcher` 内逐跳校验 Host 后重发，
     且**不得**把 `auth-key` 带到非 VDN 域；
   - 播放地址若是 VDN 直接 302 出来的，必须在抓取层解析出最终地址再交给 Media3，
     避免 Media3 内部同协议跳转（R2）。
4. `HeaderHostPolicy` 的白名单必须是**精确后缀**，禁止空集合"放行全部"来规避校验
   （任务 P3 明确禁止）。

> **评审拍板（2026-11-21）**：接受**分层隔离**方案 ——
> `auth-key` 严格**不出 `CctvHttpClient`**（禁用自动重定向、逐跳校验，且绝不向 Media3 透传）；
> **不**为 Media3 播放侧的同协议 302 升级 Media3、也**不**重写 `DefaultHttpDataSource`。
> 残余风险（R2）由"播放请求不携带任何敏感头"这一约束兜底，验收按 §7.1 的两层拆分执行。

### 3.7 `CctvManifestParser` 的触发边界（禁止无条件预抓清单）

**默认路径：不预抓。** VDN 返回的播放地址如果是标准 HLS Master Playlist，直接交给 Media3 原生
HLS 模块；解析链路中**不得**为了"看一眼清单"再发一次请求，原因有二：

1. 解析在**单线程**执行器里（A3/B8），预抓会把起播变成"VDN + 清单"双跳，放大切台阻塞；
2. 一次性签名的 HLS URL 可能被预抓提前消耗（或触发服务端一次性校验），
   导致 Media3 真正起播时 403。

**只有**在 E4a–E4d / E6 实证存在下列需求之一时，才在解析链路中触发 `CctvManifestParser`：

- 需要由客户端**选择清晰度变体**（VDN 只给 Master，且产品要求固定码率）；
- 清单内是**相对路径**，必须重写为绝对 URL 才能交给 Media3；
- 需要从清单/跳转中**提取实际 CDN Host** 来填充 `allowedHeaderHosts`（否则头隔离无法生效）。

触发时也只做"解析 + 改写 URL/提取 Host"，不复制 Media3 的 HLS 播放实现（P3 约束）。

---

## 4. 分阶段文件变更计划（P1–P6）

> 本轮不实施。文件名为建议值，最终以评审为准。**P1 与 P2 的编码冻结规则见 §6.2。**

| 阶段 | 新增文件 | 修改文件 | 职责 | 前置依赖 |
|---|---|---|---|---|
| **P1** 目录 | `data/cctv/CctvChannelCatalog.kt`、`data/cctv/CctvCatalogCoordinator.kt`、`data/catalog/CompositeCatalogCoordinator.kt`、`domain/repository/ChannelCatalogMerger.kt`（`assets/cctv_channels.json` 仅在 §4 末尾的落点规则判定为静态目录时新增） | `LiveTvApplication.kt`（分槽聚合装配）、`MobileMainActivity.kt`、`MainBrowseFragment.kt`、**`PlayerActivity.kt`**（`ensureMgtvCatalogLoaded()` 必须改为同时补载 CCTV，避免播放页只刷 MGTV 导致 CCTV 目录丢失） | `cctv:` 前缀目录；手机/TV 共用；不新增 UI 页面 | 权威频道表（U9）+ **G1/G2（§6.2）** |
| **P2** 解析 | `data/cctv/{CctvEndpoints,CctvAuth,CctvHttpClient,CctvVdnFetcher,CctvJsonParser,CctvModels,CctvStreamResolver}.kt` | — | VDN v3 请求/解析；认证与 HTTP 分离；`AUTH_UNAVAILABLE` fail-closed；**超时 connect 3s / read 5s** | **G1 + G2 + G3** |
| **P3** HLS/CDN | `data/cctv/{CctvManifestParser,CctvCdnPolicy}.kt` | — | **仅按 §3.7 的触发条件**解析/改写清单、提取 CDN Host；不重复造 HLS 播放器；不预抓起播清单 | G2 + G4 |
| **P4** 会话 | — | `domain/stream/RoutingStreamResolver.kt`（加 `cctv:` 分支）、`LiveTvApplication.kt`（装配 `cctvResolver`）、`CctvVdnFetcher` / `CctvHttpClient`（取消能力） | 输出 `ResolvedStream`；TTL 降级；403 有限刷新；切台防覆盖；**必须实现切台主动取消（`disconnect()` 或隔离可取消任务）** | P2/P3 + G2 |
| **P5** 媒体可行性 | 仅 `docs/CCTV-MEDIA-PIPELINE.md`（本轮已产出） | — | TS/PES/NAL/WASM/Extractor 边界与不实施范围 | — |
| **P6** 测试 | `app/src/test/java/com/livetv/android/data/cctv/{CctvAuthTest,CctvJsonParserTest,CctvManifestParserTest,CctvCdnPolicyTest,CctvStreamResolverTest}.kt`、`CctvSanitizedFixtures.kt`、`CctvProviderRoutingTest.kt` | 必要时 `RoutingStreamResolverTest` | 先红后绿；脱敏 fixture；源码守卫 | P1–P4 |

**P1 目录数据落点（评审拍板，按优先级）**：

1. **优先在线拉取 + 缓存**：若 E8 证实存在**公开、无需鉴权**的官方频道列表接口，
   走与 MGTV 相同的"后台拉取 + 主线程回传 + 缓存"路径，**不重新引入 `assets`**；
2. **否则**（E8 只能给静态表）在 **G1 + G2 通过之后**采用 `assets/cctv_channels.json`，
   且**仅存放静态元数据**（`id` / 名称 / 分类 / Logo / `epgId`），
   配套源码守卫**禁止该文件出现任何 `http://` / `https://` 播放链接**；
3. 两种方式都不得把动态播放地址写进频道数据或 `Channel.streamUrl`。
4. 在该规则判定完成前，P1 不得开工（与 §6.2 的冻结规则一致）。

---

## 5. 风险、兼容性与未解决问题

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| **R1** | `manifest.hls_cdrm` 可能是 ChinaDRM 等受保护流，Media3 原生不可播 | P1/P2 阶段即被 G2 拦截；若强行"解密"即触碰合规红线 | 见 `CCTV-MEDIA-PIPELINE.md`；**G2 未过 → 按 §6.2 终止接入** |
| **R2** | Media3 同协议 302 内部跟随，`auth-key` 头可能外带 | 凭据泄漏至 CDN | §3.6 第 3 条：VDN 请求自己发、自己跟；播放地址先解析出最终 URL |
| **R3** | `auth-key` 无实证 | 无法实现认证 | 缺证据时只返回 `AUTH_UNAVAILABLE`，不得伪造 |
| **R4** | 单线程解析器 + 无请求取消 | 慢 VDN 请求阻塞切台（连 MGTV/普通 IPTV 一起被拖慢） | **必选**：`CctvHttpClient` 用 connect 3s / read 5s；**P4 必须实现切台主动取消**（在途 `disconnect()` 或隔离可取消任务） |
| **R5** | 目录合并顺序/去重 | 两源复用通用分类 id 时，若"按 id 丢弃后项"会整组吞掉频道 | **禁止按分类 id 丢弃后项**：分类 id 强制来源命名空间（`cctv:*` / `mgtv:*`），同 id 时合并 `channels` 并按 `channel.id` 去重（`ChannelCatalogMerger` + 单测） |
| **R6** | 频道 ID/名称错配（U9） | 播放错台 | 目录来自权威表；解析器对未知频道 fail-closed |
| **R7** | 合规边界（DRM/HDCP/Cookie） | 越界即违规 | 红线写入文档与 CI 守卫；不实现任何解密/绕过 |

**兼容性**：`RoutingStreamResolver` 默认参数保证既有调用（`StaticStreamResolver`、
`MgtvStreamResolver`）不变；`PlayerManager`、`RetryPolicy`、`PlaybackReducer`、`media/` 零改动。

---

## 6. 需人工补充的抓包 / 样本清单 & P1/P2 准入闸门（Go/No-Go）

### 6.1 必须提供的证据

| 编号 | 产物 | 具体要求 | 用于 |
|---|---|---|---|
| E1 | VDN v3 请求原文 | 方法、完整 URL、参数名/值形态、请求头（含是否有 `auth-key`）；**仅用自有设备/自有账号抓取**，脱敏后提交 | U1/U2/U3 |
| E2 | VDN v3 响应样本 | 原始 JSON（脱敏：域名可用 `.invalid` 替换，令牌打码），至少 1 成功 + 1 失败 | U4/U5 |
| E3 | `auth-key` 生成证据 | 官方 SDK/网页中签名的**输入、算法、输出**三要素；或明确"由服务端下发"的字段说明 | U3 |
| E4a | Master Playlist | 原文（可脱敏域名）；需含 `#EXT-X-STREAM-INF` 的 `CODECS` 与 `BANDWIDTH` | U6 / 媒体管线 |
| E4b | Media Playlist + 密钥声明 | 原文；必须包含 `#EXT-X-KEY` / `#EXT-X-SESSION-KEY`（若有）；若为 AES-128，需给出密钥 `URI` 的 **Host 归属与协议**（**不得提供密钥内容**） | U6 / G2 / G4 |
| E4c | 分片轨道摘要 + 样本 | `ffprobe` 结构化轨道摘要（容器、Codec、Profile、加密标志、TS 加扰位 `transport_scrambling_control`）+ **首个分片前 64KB** 样本（严禁包含任何密钥） | U6 / 媒体管线 |
| E4d | 媒体 HTTP 响应头 | `Content-Type`、`Content-Length`、`Location`（若有） | U6 / U7 |
| E5 | `hls_cdrm` 语义 | 该字段取值示例、是否加密、密钥获取方式（**不得提供解密密钥**，只需说明是否标准 HLS） | U6 |
| E6 | CDN 与跳转样本 | 302 前后 Host、Location、所需请求头；若清单含 `#EXT-X-KEY`，同时给出**密钥域**的 Host/协议/请求头 | U7/U8 / G4 |
| E7 | 错误样本 | VDN 与 CDN 的 403/404/429/5xx 原始响应 | B9 |
| E8 | 频道目录 | `cctv:cctvN` → 名称/分类/Logo/EPG ID 的权威表 | U9 |
| E9 | WASM 说明 | 若官方流程包含 WASM，说明其**非解密**用途（如拼装/参数计算） | U10 |

### 6.2 准入闸门（P1/P2 冻结）

**G1（认证）**：E1+E3 齐备，且签名/鉴权参数能用**自有设备**离线复算并在线验证成功。
**G2（可播流）**：E4a–E4d / E5 证明存在**标准 HLS**（明文或 AES-128）可播地址，且：
- 清单 URL、分片 URL、以及（若为 AES-128）`#EXT-X-KEY` 的密钥 `URI` **全链路均为 `https://`**
  （Release 构建禁止明文 HTTP，`http://` 一律判 Release 不可播）；
- 明文 HLS 需同时满足 E4c 中的 `transport_scrambling_control == 0` 与 `ffprobe` 可解析出完整音视频轨
  （排除无标签的隐式私有加扰）；
- 仅有受保护流（`hls_cdrm` 判定为 DRM）→ **No-Go**。

**G3（解析）**：E2 成功/失败样本齐备，可写出纯 JVM 解析测试。
**G4（CDN 与密钥域安全）**：E6 证明跳转与头需求，能定义 `allowedHeaderHosts` 精确白名单；
若存在 `#EXT-X-KEY`，其 `URI` 的 Host 归属、HTTPS 协议、所需请求头，以及它在
`ScopedHeaderDataSourceFactory` / `allowedHeaderHosts` 下的**可访问性（不得误拦）与白名单归属
（不得放行非白名单域）**必须一并确认。
**G5（合规）**：确认无 DRM/HDCP 绕过、无 Cookie/他人会话复用、无逆向私有风控或签名。

> **冻结规则**：**G1 与 G2 全部通过前，P1 与 P2 一律冻结编码**。
> 若 G2 判为 No-Go（仅有受保护流），**直接终止 CCTV 接入**，
> 不在主分支保留「仅展示不可播目录」的降级入口。

---

## 7. 后续人工真机 / Android TV 验收清单

> 实施后逐项打勾；纯 JVM 单测无法替代。

### 7.1 手机（触控）
- [ ] 频道列表出现 CCTV 分组，数量与 E8 一致；`cctv:` 卡片可点击
- [ ] CCTV-1 起播出画；logcat 只出现 host，不出现完整 URL/令牌
- [ ] 快速切台 CCTV→MGTV→普通 IPTV 十次：无串台、无闪退、旧请求不覆盖新会话
- [ ] 播放超过会话有效期：到期前自动刷新出画；无显式 TTL 时 403 触发 1 次刷新后恢复
- [ ] 断网/弱网：给出既有错误文案，不无限重试；429 只发 1 次请求
- [ ] **VDN 调度层**：强制 302 到非白名单 Host 时，`CctvHttpClient` 必须失败，且抓包确认**未向跳转目标外带 `auth-key`**
- [ ] **Media3 播放层**：播放请求不携带任何敏感头（为空或仅普通头）；跨协议 302 被拦截；
     日志与抓包确认全链路（清单 / 分片 / 密钥 URI）无 `auth-key` 泄漏

### 7.2 Android TV
- [ ] 遥控器焦点：CCTV 分组可聚焦、卡片可点击、OK 进入播放
- [ ] 侧边选台栏：CCTV 频道可见、标「播放中」、自动居中
- [ ] 返回键层级：抽屉 → 控制器 → 退出，与现状一致
- [ ] 长时间播放（>30 分钟）无内存/焦点异常

### 7.3 回归
- [ ] MGTV 频道播放、切台、抽屉与改造前一致
- [ ] 普通静态 IPTV 频道播放不变（`allowedHeaderHosts` 为空路径）
- [ ] 无网络启动：空态正常，CCTV 失败不影响 MGTV 列表
- [ ] `lintDebug testDebugUnitTest assembleDebug` 全绿
