# TESTING — 测试策略与边界

## 1. 总体策略

**默认用纯 JVM 单元测试**：不依赖模拟器、不依赖设备、秒级完成、可在 CI 无条件执行。
只有真正无法在 JVM 上表达的（焦点、渲染、播放器解码、系统交互）才下沉到仪器测试或真机验收。

| 层级 | 运行环境 | 覆盖对象 | 现状 |
|---|---|---|---|
| L1 纯 JVM 单元测试 | 本地 JVM，`testDebugUnitTest` | 数据模型、解析器、Repository、状态流转、重试策略、频道导航、设备判定、网格列数、手机端徽章/筛选/抽屉、远程订阅拉取与三级兜底、401/403/429 快速失败、**HLS 识别、播放会话刷新状态机、Host 请求头隔离、MGTV 白盒筛选与 fail-closed** | **已建立**：34 个测试类 /**319 个用例**（`app/src/test/`） |
| L2 仪器测试 | 模拟器/真机，`connectedDebugAndroidTest` | View 渲染、Leanback 焦点、Activity 生命周期 | **未建立**：无 `androidTest` 源集、无 `androidx.test` 依赖 |
| L3 真机验收 | 真实 Android TV 设备 | 遥控器手感、直播流实际解码、长期稳定性 | **手动**：无自动化 |

现有 L1 用例分布：

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `player/RetryPolicyTest` | 25 | 候选源生成与协议过滤、主源重试（1500/3000ms）、Failover 到备用源、全部耗尽、手动重置、切台重置、**401/403/429 快速失败（不重试当前源、立即切源、无备用源即 Fatal）、500/503 仍走退避** |
| `player/PlaybackReducerTest` | 15 | 合法状态路径、非法转换保持原状态、Error 态忽略后续回调、致命错误、Released |
| `player/ChannelNavigatorTest` | 11 | id/streamUrl 定位（含同 URL 取第一个）、上下切台、首尾循环、单频道、空列表、分类扁平化 |
| `player/PlaybackErrorMapperTest` | 9 | HTTP 状态码（含 code=0 边界）、超时、网络、源解析、未知原因 |
| `player/PlayerLayerPurityTest` | 3 | **架构约束**：纯策略层不得引用 `android.*` / `androidx.media3.*`（源码扫描） |
| `data/parser/ChannelJsonParserTest` | 11 | 标准解析、缺省字段容错、禁用/空分类过滤、URL 协议拦截、异常数据恢复 |
| `data/parser/M3uPlaylistParserTest` | 30 | `#EXTINF` 属性提取与映射、单/双/无引号、属性乱序、无分组兜底、非法 URL、引号不闭合、坏条目跳过、**引号内逗号切分**、**`#EXTVLCOPT`/`#KODIPROP`/行内属性 -> 请求头**、请求头不串台 |
| `data/repository/DefaultChannelRepositoryTest` | 8 | 单源透传、空结果、异常吞噬、**多源同名分类合并**、不同名分类分离、频道去重、单源失败不影响其它源 |
| `data/repository/DefaultChannelRepositoryCacheTest` | 7 | 默认开启缓存只读一次、`refresh()` 强制重算、`invalidateCache()` 作废、`enableCache=false` 每次重算 |
| `data/LocalM3uAssetTest` | 3 | 直接读 `src/main/assets/sample.m3u`，校验 2 分类 / 4 频道及 `tvg-*` 映射 |
| `domain/model/ChannelTest` | 5 | 字段默认值、值语义相等、`copy` 语义 |
| `data/repository/ChannelRepositoryResilienceTest` | 4 | assets 缺失 / IO 异常时仓库返回空列表且不抛异常 |
| `data/LocalAssetsChannelsTest` | 3 | 直接读 `src/main/assets/channels.json`，校验随包默认配置合法且 6 个频道无损 |
| `data/remote/RemoteM3uFetcherTest` | 10 | **完全离线**（本地 `ServerSocket` 起 `127.0.0.1` 随机端口）：200 原文与 UTF-8 解码、403/404/500 → `HttpError`、`file://`/`ftp://`/空串 → `InvalidUrl` 且不发请求、响应超 2MB → `SizeExceeded`、连接被拒 → `NetworkError` |
| `data/repository/ChannelRepositorySubscriptionTest` | 11 | 远端成功写缓存并 `RemoteLoaded`、失败有缓存 → `FallbackToCache` 且保留旧列表、失败无缓存 → `FallbackToBuiltin`、**HTTP 200 空内容不清空已有列表与缓存**、清 URL → `BuiltinOnly`、初始化优先有效缓存、换 URL 使旧缓存失效、`findChannelById`、URL trim |
| `domain/stream/StreamTypeDetectorTest` | 7 | `.m3u8`/`.m3u`、**无后缀 HLS（`format/type/protocol/fmt=m3u8\|hls`、`/hls/`、`hls.php`、`/m3u8/`）**、`.mp4/.mkv/.flv/.ts/.mp3/.aac/.webm` 明确不误判、显式 MIME 推断 |
| `domain/stream/HeaderHostPolicyTest` | 10 | 精确主机 / 子域匹配、**`evilmgtv.com` 与 `mgtv.com.evil.net` 后缀伪造拦截**、空白名单不限制（普通 IPTV 不变）、非法 URL |
| `domain/stream/StreamRefreshCoordinatorTest` | 10 | 缓存复用、**注入 `Clock` 模拟临期（<60s）主动刷新**、403 单次重解析、**第二次 403 快速失败不再解析**、重解析失败即失败、**429 立即快速失败**、500 委派既有策略、起播成功重置预算、重置会话 |
| `domain/stream/StaticStreamResolverTest` | 5 | **普通 IPTV 无回归**：url/headers/MIME 保留、`expiresAtMs=null`、无 Host 限制、渐进式 MIME 为空、空/非 http URL 拒绝 |
| `domain/stream/RoutingStreamResolverTest` | 3 | `mgtv:` 前缀路由到 MGTV 解析器、普通频道走静态解析器 |
| `data/mgtv/MgtvJsonParserTest` | 9 | 脱敏 JSONP/纯 JSON 目录解析、播放源字段与单源 `isp2p`/`hdcp` 覆写、业务错误码、坏 JSON 返回空/null |
| `data/mgtv/MgtvSourceSelectorTest` | 9 | **白盒过滤**：放行 `.m3u8`+`isp2p=0`+`hdcp=0`+https，拦截 P2P/HDCP/HTTP/非 m3u8；清晰度优先匹配；未命中按分辨率确定性回退；无合规源返回 null |
| `data/mgtv/MgtvSignerTest` | 8 | **签名算法黄金向量**（期望值由独立 Node 实现预先算出）：排序拼接、空串参与、参数顺序无关、`_t` 变化即变、输出 32 位大写十六进制 |
| `data/mgtv/MgtvEndpointsTest` | 7 | 目录/播放源参数构造、`sourceParams`→`sourceUrl` 拼接顺序（`_support`/`sign` 在末尾）、`init_definition` 优先、**数据层源码守卫：无 32 位十六进制 sign、无 IP、无 `ftc=`/`sftc=`/Cookie** |
| `data/mgtv/MgtvHttpClientTest` | 8 | 本地回环服务：200 原文、请求头发送、403/404、非法 scheme/空串、超限、连接拒绝 |
| `data/mgtv/HttpMgtvSourceFetcherTest` | 8 | 本地回环：200 → `RawJson`、请求携带 32 位大写 `sign`、签名与 `MgtvSigner` 结果一致、`did == deviceId`、不同 `_t` 签名不同、Referer 上线、403 映射、网络错误映射 |
| `data/mgtv/MgtvStreamResolverTest` | 10 | 成功流 + 过期时间计算、`AUTH_UNAVAILABLE`/403/404/429/网络/解析错误映射、业务错误码、无可用源、Host 白名单可配置、cameraId 提取 |

## 2. 命令

```bash
./gradlew testDebugUnitTest --console=plain --no-daemon          # L1
./gradlew testDebugUnitTest --tests "*ChannelTest" --console=plain --no-daemon   # 只跑某个类
./gradlew connectedDebugAndroidTest --console=plain --no-daemon  # L2（需先 adb devices 有设备）
```

报告位置：`app/build/reports/tests/testDebugUnitTest/index.html`，
机器可读结果：`app/build/test-results/testDebugUnitTest/*.xml`。

### 2.1 云端 CI

`.github/workflows/android-ci.yml` 在每次 push / PR 上依次跑
`compileDebugKotlin` → `lintDebug` → `testDebugUnitTest` → `assembleDebug`。

- **Maven 源分流**：`settings.gradle.kts` 读 `CI` 环境变量。GitHub Actions 自带 `CI=true`，
  此时只声明官方源（`gradlePluginPortal` / `google()` / `mavenCentral()`），不声明阿里云镜像，
  避免 runner 海外节点访问国内镜像返回 `502 Bad Gateway` 导致依赖解析失败；本地构建仍保留
  阿里云镜像优先。
- **单测计数**：Gradle 成功时不打印用例数，因此 `Unit tests (JVM)` 之后有一步
  `Summarize JVM unit test results`（`if: always()`），用 POSIX `sh` + `awk` 汇总
  `app/build/test-results/testDebugUnitTest/TEST-*.xml` 的 tests/failures/errors/skipped，
  打印 `JVM Unit Test Summary: N suites, M tests (...)`、追加到 `$GITHUB_STEP_SUMMARY`，
  并在没找到 XML 或用例数为 0 时以非零退出码失败，防止测试被静默跳过。

## 3. 什么该写成 L1 测试

| 目标 | 示例 |
|---|---|
| 数据解析 | `m3u` / `m3u8` / JSON 文本 → `List<ChannelCategory>`（纯函数，输入输出可断言） |
| Repository | 给定假数据源，断言返回的频道列表顺序、字段映射、空结果处理、**多源合并** |
| 状态流转 | `PlaybackReducer.reduce(state, event)` 的合法路径与非法转换 |
| 重试 / Failover | `RetryPolicy.onFailure(state, error)` 返回 `Retry(delay)` / `SwitchSource(index)` / `Fatal` |
| 频道导航 | `ChannelNavigator` 的定位、循环切台、空列表行为 |
| 错误分类 | `PlaybackErrorMapper.map(responseCode, causeKind)` |
| 模型 | `Channel` 的默认值、相等性、拷贝语义 |
| 请求头映射 | `M3uPlaylistParser` 把 `#EXTVLCOPT` / 行内属性映射为 `Channel.headers` |
| 缓存语义 | `DefaultChannelRepository` 的缓存命中 / `refresh()` / `invalidateCache()` |

L1 测试的前提是**被测逻辑不碰 Android framework**：一旦用到 `Context`、`View`、`Uri`、
`Handler`，要么改写为纯函数/注入接口，要么升级到 L2。

> **阶段三的分层约定**：`app/src/main/java/com/livetv/android/player/` 下除 `PlayerManager.kt`
> 外全部是**纯策略层**，禁止引用 `android.*` 与 `androidx.media3.*`。
> 这条约定由 `PlayerLayerPurityTest` 扫描源码强制执行——新增文件时若违反会直接测试失败。

## 4. L1 测试写法规则（强制）

1. **期望值硬编码字面量**：断言里的期望结果直接写字面量（`assertEquals(3, channels.size)`），
   不得调用被测逻辑再算一遍——那样断言恒真，测的是实现自己。
2. **强断言**：禁止 `assertNotNull` / `assertTrue(x)` / `assertTrue(x >= 0)` 这类弱断言，
   要断言**具体值**（状态、字段、顺序）。
3. **mock 只挡外部依赖**：网络、文件系统、时钟可以 mock；**被测单元的纯逻辑不许 mock**。
4. **先红后绿**：新测试必须先在"空实现/旧实现"上跑出**失败**，再让实现通过。
   一次就绿的新测试通常没测到东西。
5. **不改旧断言凑绿**：旧用例失败先怀疑实现或口径；确属口径变更才改，并在提交说明里写清原因。

### 4.1 `org.json` 在纯 JVM 单测里的坑（必读）

Android 的 `android.jar` 只是编译期桩，`org.json.JSONObject` / `JSONArray` 的方法体全部是
`throw RuntimeException("Method ... not mocked.")`。**纯 JVM 单测直接调用会崩**。

因此凡是用 `org.json` 实现解析器，必须在 `app/build.gradle.kts` 显式引入真实实现：

```kotlin
testImplementation("org.json:json:20231013")
```

这**只是测试期依赖**（`testImplementation`），不进 APK，不影响运行期体积；
它让 `ChannelJsonParser` 的真实解析逻辑能在 JVM 上跑，无需模拟器。

替代方案（未采用）：自己手写 JSON 解析器——成本高、易出解析 bug，且与 Android 运行期行为不一致。

> 提示：`org.json` 的 `optString(key, default)` 在**键存在但值为 null** 时返回默认值，
> 在**键存在且值为 JSON null** 时返回字符串 `"null"`——写解析器时要留意这两种语义。

## 5. L2 仪器测试的边界

当前仓库**没有** `app/src/androidTest` 源集，也没有 `androidx.test` / `espresso` 依赖。
若将来需要引入，属于**新增依赖**，必须在交付说明里写明"为什么必须加、替代方案为何不可行"。

适合 L2 的场景：

- Leanback 焦点在 `ListRow` 之间的移动与记忆；
- `ChannelCardPresenter` 的 `onBindViewHolder` / `onUnbindViewHolder` 是否被正确回调；
- `PlayerActivity` 在 `onStart`/`onStop` 之间是否正确创建/释放播放器（可用计数 fake 验证）。

不适合 L2 的场景（应当下沉到 L1）：解析、映射、状态机、排序、过滤。

## 6. L3 真机验收清单（手动）

### 6.0 本机实测记录

| 项 | 值 |
|---|---|
| 设备 | OnePlus **PKG110**（手机，非 Android TV 盒子） |
| 系统 | **Android 16 / API 36** |
| 物理分辨率 | 1264×2780 @ 560dpi（竖屏手机） |
| 测试显示参数 | 临时 `wm size 1920x1080` + `wm density 240`（800×450dp，TV 比例），测试后已 `reset` 还原 |
| 连接方式 | 无线 ADB（`adb pair` + `adb connect`） |
| 网络 | **无外网**（DNS 解析超时，`UnknownHostException (no network)`），故真实 HLS 出画无法验证 |
| 测试时间 | 2026-11-19 |

> 注意：本机是**手机**而非 Android TV。Leanback UI 与 D-pad 按键通过 `input keyevent` 模拟即可完整验证，
> 但"真实遥控器手感/红外码值"这类硬件相关项不适用。

### 6.1 基础验收项

| # | 验收项 | 期望 | 实测 |
|---|---|---|---|
| 1 | 冷启动 | 显示"电视节目"标题、分类行、焦点落在首个分类 | ✅ 通过：标题 + 3 行（新闻/体育/卫视），焦点在「新闻」 |
| 2 | 方向键 | 上/下在行间移动，左/右在行内移动卡片，焦点样式可见 | ✅ 通过：header ↔ 卡片行切换正常，卡片激活后抬高 |
| 3 | OK 键 | 跳转到 `PlayerActivity`，左上角显示频道名 | ✅ 通过：进入播放页，左上角显示「综合新闻」 |
| 4 | Back 键 | 从播放页返回浏览页，焦点位置与进入前一致 | ✅ 通过：焦点恢复到进入前的首张卡片 |
| 5 | 播放 | HLS 直播流实际出画 | ⚠️ **无法验证**：设备无外网，DNS 解析失败，所有源均进入 Error 态 |
| 6 | 屏幕常亮 | 播放期间屏幕不自动熄灭 | ✅ 代码生效（`FLAG_KEEP_SCREEN_ON`）；实测期间屏幕未熄灭（曾因系统休眠进入 Dozing，已用 `svc power stayon` 保持） |
| 7 | 错误流 | 无效 URL 时显示错误说明，不崩溃 | ✅ 通过：显示「网络连接失败 / 按确认键重试 · 按上下键切换频道」，无崩溃 |
| 8 | 稳定性 | 连续进出播放页无内存持续增长 | ✅ 通过：连续进出 3 次后 `dumpsys activity` 中 PlayerActivity 实例数 = 0，Native Heap 18.4MB / Dalvik Heap 3.6MB，无堆积 |

### 6.2 遥控器交互验收项

| # | 验收项 | 期望 | 实测 |
|---|---|---|---|
| 9 | 播放页上/下键 | 切换频道，短暂显示"已切换到：xxx" 并在 3 秒后自动消失 | ✅ 通过（**修复后**）：切台生效，提示可见，3.5s 后已隐藏 |
| 10 | 切台循环 | 末台按下键回首台；首台按上键回末台 | ✅ 通过：从「CCTV-13 新闻」连按 5 次上键回绕到「浙江卫视」 |
| 11 | 控制器焦点 | OK 呼出控制器后，上下键在控制器内移动，**不切台** | ✅ 通过：呼出控制器后按下键，频道保持不变 |
| 12 | Back 关闭控制器 | 控制器显示时 Back 只隐藏控制器，再按才退出 | ✅ 通过：第一次 Back 仍在 `PlayerActivity`，第二次回到 `MainActivity` |
| 13 | OK 手动重试 | 错误态按 OK 回主源重新连接，提示消失 | ✅ 通过：错误文案消失并显示「缓冲中…」，随后重新进入重试 |
| 14 | 主备源切换 | 主源重试耗尽后切到备用源 | ⚠️ **未覆盖**：默认频道 `backupUrls` 为空，无备用源可切；重试计数（1/2、2/2）已验证 |
| 15 | 全部源失效 | 所有候选源耗尽后显示错误分类说明，等待手动重置 | ✅ 通过：显示「网络连接失败」+ 操作指引，按 OK 可重新开始 |
| 16 | 切台不重建播放器 | ExoPlayer 实例在切台/切源/重试中未被重建 | ⚠️ **未直接观测**：代码层由 `PlayerManager` 复用同一实例（只重建 `MediaSource`），但无日志可佐证 |

### 6.3 真机实测发现的缺陷（已修复）

| # | 缺陷 | 现象 | 修复 |
|---|---|---|---|
| D1 | **上下键切台完全失效** | Media3 `PlayerView.dispatchKeyEvent` 抢先消费方向键（`maybeShowController`）后返回 true，`Activity.onKeyDown` 收不到事件 | 改为在 `PlayerActivity.dispatchKeyEvent` 中于视图树分发前拦截 |
| D2 | **Error 态 OK 重试失效** | 同上，OK 键也被 `PlayerView` 消费 | 同上，OK/ENTER 一并前移到 `dispatchKeyEvent` |
| D3 | **"已切换到"提示一帧都看不到** | `showChannelSwitchedHint()` 在 `playerManager.switchChannel()` 之前调用，后者同步派发 Loading 覆盖 `statusText` | 调换调用顺序 |
| D4 | **连续换台只切一台** | 切换媒体源后 Media3 异步弹起控制器，后续方向键被控制器吞掉 | `controllerAutoShow = false` + 切台后不依赖控制器状态，改用"用户是否主动呼出控制器"标记 |
| D5 | **网络故障显示成"HTTP 2001"** | `HTTP_STATUS_RANGE = 2000..2999` 把 Media3 的 IO 错误码段当成 HTTP 状态码（2001 = 网络连接失败） | 删除该区间兜底，只从 cause 链的 `InvalidResponseCodeException` 取真实响应码 |

## 7. 现状与下一步

> **2026-11-21 变更**：M3U/内置/订阅管线已移除，相关用例（`RemoteM3uFetcherTest`、`ChannelRepository*`、
> `DefaultChannelRepository*`、`M3uPlaylistParserTest`、`ChannelJsonParserTest`）随代码一并删除；
> 下方历史段落只记录当时情况。

- 阶段一建立 L1 链路；阶段二补齐**数据层**的 L1 覆盖；阶段三补齐**播放器层**的 L1 覆盖
  （重试策略 / 状态归约 / 错误映射 / 频道导航 + 纯度约束）；阶段 3.5 追加 2 个错误映射边界用例；
  阶段四补齐 **M3U 解析与多源合并**的 L1 覆盖；阶段五补齐 **M3U 请求头与仓库缓存**的 L1 覆盖；
  阶段 5.5 完成**真机实测**（见 §6），阶段六新增设备判定与网格列数用例，阶段七新增手机卡片徽章、分类筛选、副标题兜底、抽屉模型用例，
  阶段八新增**远程订阅拉取、三级兜底与 401/403/429 快速失败**用例；
  阶段九新增**HLS 识别、播放会话刷新状态机、Host 请求头隔离、MGTV 白盒筛选与签名黄金向量**用例，
  合计 **306 个用例 / 35 个测试类**。

  阶段六新增：`DeviceUtilsTest`（6 例，TV 判定两种信号）、`MobileChannelListTest`（11 例，行序列组装、2/3/4 列阈值、**px→dp 换算**）。
  阶段七新增：`MobileChannelListTest` 扩到 24 例（徽章简标、分类筛选、副标题三级兜底、序号补零、分类配色稳定性），
  以及 `ChannelDrawerModelTest`（7 例，抽屉分组、空分类跳过、当前频道标记与行/频道下标）。
  阶段八新增：`RemoteM3uFetcherTest`（10 例）、`ChannelRepositorySubscriptionTest`（11 例）、
  `RetryPolicyTest` 快筛 10 例（该类共 25 例）。
  px→dp 换算的实测值：1264px @3.5x = 361dp（竖屏 2 列）、2780px @3.5x = 794dp（横屏 4 列）。

  **阶段八的网络测试完全离线**：AGP 单测编译不暴露 `jdk.httpserver`
  （`com.sun.net.httpserver.HttpServer` 会报 `Unresolved reference: sun`），
  因此改用 `java.net.ServerSocket` 自建本地 `127.0.0.1` 随机端口服务，不访问公网。
  先红后绿证据：清空 `FAST_FAIL_HTTP_STATUSES` → `192 tests completed, 7 failed`；
  把缓存读取强制为 `null` → `192 tests completed, 2 failed`；两次破坏均已还原。

  **阶段九的 MGTV 测试全部离线且只用脱敏样本**：`TinyHttpServer` 用
  `java.net.ServerSocket` 起 `127.0.0.1` 随机端口；JSON fixture 位于
  `app/src/test/java/com/livetv/android/data/mgtv/MgtvSanitizedFixtures.kt`，
  域名统一用 `.invalid`，`sid`/`deviceId` 全部是人造占位串；`MgtvSignerTest` 的黄金向量是对**人造参数**的 MD5 结果，不是抓包签名。
  `MgtvEndpointsTest` 另有一条**源码守卫**用例，扫描 `data/mgtv/` 下所有 `.kt`，
  断言不出现 32 位十六进制字面量（疑似 sign）、不出现 IP、不出现 `ftc=`/`sftc=`/`Cookie:`/`macid=`/`uip=`。
  由于 JDK 的 `HttpURLConnection` 会静默丢弃受限的 `Origin` 头，`HttpMgtvSourceFetcherTest`
  改为断言 `sourceRequestHeaders()` 映射内容 + 只在链路上验证 `Referer`。
  先红后绿证据：两处初始失败（`Origin` 受限头、`ftp://` 的 host 提取预期错误）在修正测试与抽出纯函数后转绿。
- **L1 单测无法覆盖的层次已被真机暴露**：D1/D2/D3/D4 都属于 Activity 与 Media3 之间的事件/时序问题，
  纯 JVM 用例在结构上不可能发现。后续再改 `PlayerActivity` 的按键或状态渲染时，必须做真机回归。
  阶段七同理：触控浮层与抽屉的真实布局、`isControllerOpenedByUser` 与抽屉的 BACK 优先级、
  以及"离开错误态后重试按钮残留"问题，全部是真机截图才发现的——**改 `ui/mobile/` 或触控浮层后必须真机验收**。
- 仍未建立 L2（`androidTest` 源集与 `androidx.test` 依赖均不存在）。
- 后续随浏览页状态流转（P1-4）、M3U 解析器（P1-2）、多码率（P1-8）落地，
  继续按"L1 优先"补齐对应纯 JVM 用例（详见 `docs/ROADMAP.md`）。

### 7.1 双端 UI 布局修复轮次（Stage 1–4）

本轮把可纯 JVM 判定的 UI 策略全部抽成无 Android 依赖的纯函数，避免"只能靠截图验证"：

| 用例类 | 覆盖点 | 对应缺陷 |
|---|---|---|
| `HeaderAutoHidePolicyTest`（7 例） | 顶栏收起的有效方向累加、**方向反转立即清零**、"下滑 2000px 后上滑 30px 即刻唤回"、阈值抖动不误触发 | 手机端顶栏滚动隐藏累加器死锁 |
| `FocusRestorePolicyTest`（6 例） | 刷新后按分类名恢复行、分类被删时退回有界行号、越界安全返回 `null` | TV 端刷新后焦点错位 |
| `MobileChannelListTest`（新增窄屏/边界 3 例） | `< 360dp` 降 1 列、359/360 硬边界、600/900/1280 列数封顶 | 360dp 竖屏卡片挤压崩坏 |
| `ChannelNavigatorTest`（新增 1 例） | 聚合目录"订阅分段在前 + MGTV 分段在后"的定位与跨分组循环切台 | 播放页看不到 MGTV 频道 / 无法在 MGTV 间切台 |
| `ChannelCatalogStoreTest`（5 例） | 单快照替换、空分类过滤、顺序保持 | 目录快照语义 |
| `PlayerKeyPolicyTest`（新增 2 例） | 锁屏态下方向键/确认键一律透传（不再切台、不呼出控制器），Error 态行为保持不变 | 锁屏后硬件键仍可切台 |

档位边界：`gridSpanCount` 在 359dp→1 列、360dp→2 列、600dp→3 列、900dp→4 列、1280dp 及以上→5 列（上限）。
`ChannelCatalogStore` 去重以分类 id 为准，订阅分段优先；补充分段整体追加在后，保证抽屉分组顺序稳定。

需要真机/模拟器目视验证、**无法由 L1 单测替代**的条目：
360dp 竖屏首屏（卡片可读、顶栏不挤压）、挖孔屏顶部与三键导航底部遮挡、横竖屏旋转后的分类选中态与滚动位置、
TV 遥控器在设置的订阅弹窗里 D-Pad 可达、刷新卡片"刷新中…"的可见性、TV 桌面 320×180 横幅的实际显示效果。

### 7.2 手机端实测记录（2026-11-21，PKG110 / Android 16 / 1264×2780 / 560dpi ≈ 361dp）

已在真机覆盖安装 debug APK 并截图核对（截图不入库，仅记录结论）：

| 检查点 | 实测结果 |
|---|---|
| 竖屏 361dp 顶栏 | 「Live TV / 共 12 台 / 搜索 / 刷新 / 订阅源」单行完整显示，无挤压、无裁切 |
| 竖屏分类条 | 横向胶囊条正常滚动，选中项为深蓝底 + 高亮描边（截图取色 `#1E3A8A`） |
| 竖屏网格 | 可用宽度 361-20=341dp < 360dp → **单列**，频道名完整可读（旧版 118dp 左栏 + 2 列时约 102dp/张） |
| 竖屏底部安全区 | 滚到末尾后第 12 张卡片完整露出，位于导航手势条上方（`navBar.bottom` + 18dp padding 生效） |
| 挖孔屏顶部 | 系统上报 `DisplayCutout insets=(0,140,0,0)`（40dp 居中挖孔）；竖屏顶栏整体下移 40dp，未进入挖孔区；横屏左栏同步避让 |
| 横屏顶栏收起 | 向下滚动约 2100px 后顶栏与 16dp 遮罩一起收起，无残留色带、无 48dp 空白死区 |
| 横屏顶栏唤回 | 收起态下反向轻扫约 60px 即唤回（旧实现需反向滑回同等 2100px） |
| 旋转状态保持 | 竖屏滚到中下部后转横屏，选中分类「全部」保持，滚动锚点保持（未回到首行） |
| 崩溃检查 | `logcat -b crash` 无 `FATAL EXCEPTION` |

仍未验证（需 Android TV 设备 / 电视模拟器）：TV 遥控器在订阅弹窗内的 D-Pad 可达性与暗色可读性、
刷新卡片"刷新中…"的焦点保持、320×180 TV 桌面横幅在启动器中的实际显示、分屏（split-screen）缩放路径。

### 7.4 CCTV P1 目录与多源聚合（2026-11-21）

| 用例类 | 覆盖点 |
|---|---|
| `CctvChannelCatalogTest`（6 例） | JSON → 领域映射：`cctv:` 频道/分类前缀、`streamUrl` 恒空、名称/Logo/EPG ID；损坏 JSON、缺字段、空数组 fail-closed |
| `ChannelCatalogMergerTest`（6 例） | CCTV 在前 / MGTV 在后、空分类丢弃、同分类 id 合并频道并按 `channel.id` 去重（不吞频道）、单侧为空保留另一侧 |
| `CompositeCatalogCoordinatorTest`（7 例） | 两源乱序到达不互相覆盖、单源失败（空列表）保留另一槽位、单源二次刷新保留另一源、回调仅在两侧完成后触发一次且此时 store 已更新 |
| `CctvCatalogSourceGuardTest`（2 例） | `data/cctv/*.kt` 无播放地址/签名/凭据/IP/硬编码频道表；`assets/cctv_channels.json` 只含静态元数据（无 `http(s)://`、无 `streamUrl`） |

> P1 只做目录：`cctv:` 频道的 `streamUrl` 为空，点击会走既有「无可用播放地址」提示。
> 播放能力（VDN 解析）属于 P2–P4，尚未实施。
