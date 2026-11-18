# TESTING — 测试策略与边界

## 1. 总体策略

**默认用纯 JVM 单元测试**：不依赖模拟器、不依赖设备、秒级完成、可在 CI 无条件执行。
只有真正无法在 JVM 上表达的（焦点、渲染、播放器解码、系统交互）才下沉到仪器测试或真机验收。

| 层级 | 运行环境 | 覆盖对象 | 现状 |
|---|---|---|---|
| L1 纯 JVM 单元测试 | 本地 JVM，`testDebugUnitTest` | 数据模型、解析器、Repository、状态流转、重试策略、频道导航 | **已建立**：15 个测试类 / **124 个用例**（`app/src/test/`） |
| L2 仪器测试 | 模拟器/真机，`connectedDebugAndroidTest` | View 渲染、Leanback 焦点、Activity 生命周期 | **未建立**：无 `androidTest` 源集、无 `androidx.test` 依赖 |
| L3 真机验收 | 真实 Android TV 设备 | 遥控器手感、直播流实际解码、长期稳定性 | **手动**：无自动化 |

现有 L1 用例分布：

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `player/RetryPolicyTest` | 15 | 候选源生成与协议过滤、主源重试（1500/3000ms）、Failover 到备用源、全部耗尽、手动重置、切台重置 |
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

## 2. 命令

```bash
./gradlew testDebugUnitTest --console=plain --no-daemon          # L1
./gradlew testDebugUnitTest --tests "*ChannelTest" --console=plain --no-daemon   # 只跑某个类
./gradlew connectedDebugAndroidTest --console=plain --no-daemon  # L2（需先 adb devices 有设备）
```

报告位置：`app/build/reports/tests/testDebugUnitTest/index.html`，
机器可读结果：`app/build/test-results/testDebugUnitTest/*.xml`。

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

- 阶段一建立 L1 链路；阶段二补齐**数据层**的 L1 覆盖；阶段三补齐**播放器层**的 L1 覆盖
  （重试策略 / 状态归约 / 错误映射 / 频道导航 + 纯度约束）；阶段 3.5 追加 2 个错误映射边界用例；
  阶段四补齐 **M3U 解析与多源合并**的 L1 覆盖；阶段五补齐 **M3U 请求头与仓库缓存**的 L1 覆盖；
  阶段 5.5 完成**真机实测**（见 §6），合计 **124 个用例**。
- **L1 单测无法覆盖的层次已被真机暴露**：D1/D2/D3/D4 都属于 Activity 与 Media3 之间的事件/时序问题，
  纯 JVM 用例在结构上不可能发现。后续再改 `PlayerActivity` 的按键或状态渲染时，必须做真机回归。
- 仍未建立 L2（`androidTest` 源集与 `androidx.test` 依赖均不存在）。
- 后续随浏览页状态流转（P1-4）、M3U 解析器（P1-2）、多码率（P1-8）落地，
  继续按"L1 优先"补齐对应纯 JVM 用例（详见 `docs/ROADMAP.md`）。
