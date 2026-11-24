# LiveTV 测试体系盘点、缺口分析与 L1–L4 分层方案（TEST_GAP）

> 关联主索引与源码证据：[PROJECT_AUDIT.md](PROJECT_AUDIT.md)（`AUD-04`、`AUD-15`、`AUD-16`）

---

## 1. 现有测试与 CI 盘点

### 1.1 L1 纯 JVM 单元测试（`app/src/test/java/`）
- **实际规模**：共 **58 个 `.kt` 文件**（**56 个 `*Test.kt` 测试套件 / 440 个 `@Test` 用例** + 2 个辅助类 `MgtvSanitizedFixtures.kt`、`TinyHttpServer.kt`）。
- **分层分布**：
  - `data/cctv/` & `data/mgtv/`（11 套件 / 83 用例）：覆盖目录解析、签名、选源、`TinyHttpServer` 基础 HTTP、Fail-Closed 回归与 CCTV 源码守卫。
  - `domain/`（11 套件 / 71 用例）：覆盖模型、分槽合并、代次门控、刷新协调器、Host 白名单策略。
  - `player/`（5 套件 / 71 用例）：覆盖 `RetryPolicy`、`PlaybackReducer`、`ChannelNavigator`、`PlaybackErrorMapper` 及 `PlayerLayerPurityTest`（强制无 Android/Media3 依赖）。
  - `ui/mobile/` & `util/`（16 套件 / 137 用例）：覆盖纯策略计算与 XML 文本守卫。
  - `webplayer/`（13 套件 / 75 用例）：覆盖 URL 策略、资源拦截、静态缓存、手势、预热策略与 `CctvWebPrototypeGuardTest` 源码文本守卫。

### 1.2 Android 仪器测试（`app/src/androidTest/`）与 CI
- **`app/src/androidTest/`**：**完全缺失（0 个文件）**；`app/build.gradle.kts:91-95` 仅配置 `junit:4.13.2` 与 `org.json:20231013`。
- **`.github/workflows/android-ci.yml`**：执行 `compileDebugKotlin` -> `lintDebug` -> `testDebugUnitTest` -> `assembleDebug`；**不构建 Release**，不跑仪器测试。
- **`.github/workflows/release.yml`**：Tag 触发时执行 `lintDebug testDebugUnitTest` -> `assembleRelease` -> `apksigner verify` -> 发布 Release；**无 Release 安装/启动冒烟验证**。

---

## 2. 六大测试缺口分析

| 缺口领域 | 关联编号 | 现状与盲区 |
|---|---|---|
| **1. 纯 JVM 单测覆盖盲区** | `AUD-16` | `CctvCatalogCoordinator`、`MgtvCatalogCoordinator`、`MgtvCatalogFetcher`、`MgtvDeviceId`、`PlayerManager`、`NoRetryOnClientErrorPolicy`、`ScopedHeaderDataSourceFactory` 为 **0 覆盖**；部分守卫测试仅做字符串 `contains(...)` 匹配 |
| **2. Android 仪器测试缺口** | `AUD-15` | 5 个 Activity/Fragment（含 `PlayerActivity`、`CctvWebPlayerActivity`、`MainBrowseFragment`）、Leanback D-Pad 导航、Adapter 与 PiP/旋转生命周期零运行时测试 |
| **3. 真机播放与切台回归缺口** | `AUD-15` | 历史 L3 测试多次处于无外网环境；TV 端 Leanback 焦点与遥控器连按切台缺少自动化真机脚本 |
| **4. WebView 异常与进程崩溃恢复缺口** | `AUD-15` | `CctvWebPlayerActivity.onRenderProcessGone`（`:612-645`）仅靠 `CctvWebPrototypeGuardTest.kt:88-120` 子串匹配，从未在运行时触发渲染进程崩溃测试其槽位清理与重试恢复 |
| **5. 弱网 / 异常 HTTP 状态码回归缺口** | `AUD-16` | `MgtvHttpClientTest` 仅覆盖 `200`/`403`/`404`/拒绝连接；缺少 `302` 不跟随、`401`/`429`/`500`/`503`、读超时与 `ScopedHeaderDataSourceFactory` 302 跨跳剥离 Header 的闭环测试 |
| **6. Release 构建（R8 + 资源压缩）冒烟缺口** | `AUD-04` | `proguard-rules.pro` 为空，CI 未在 PR 阶段构建 Release，Release 发布流也未验证 APK 在模拟器/真机安装启动无 `ClassNotFoundException` / 资源缺失 |

---

## 3. L1–L4 分层测试落地方案（含 7 项评估）

### 方案 A（L1）：补齐 `MgtvHttpClientTest`、`NoRetryOnClientErrorPolicy` 与 `ScopedHeaderDataSourceFactory` 单测（`AUD-16`）
1. **当前实现**：`MgtvHttpClientTest` 使用 `TinyHttpServer` 仅测 4 种基础响应；`media/` 两个核心网络策略类无测试。
2. **存在的问题**：无法在 JVM 阶段回归 302 跨协议跳转脱敏、读超时、429/5xx 响应及 `NoRetryOnClientErrorPolicy` 拦截逻辑。
3. **代码证据**：`app/src/test/java/com/livetv/android/data/mgtv/MgtvHttpClientTest.kt:48-99`；`app/src/main/java/com/livetv/android/media/ScopedHeaderDataSourceFactory.kt:46-117`；`NoRetryOnClientErrorPolicy.kt:27-56`。
4. **预期收益**：无需模拟器即可在 PR 门禁中守住安全跳转与客户端错误快速失败红线。
5. **实施成本**：1 人日 / 低复杂度（复用现有 `TinyHttpServer` 与轻量 fake `HttpDataSource`，零新依赖）。
6. **风险与回滚点**：低风险；若 `TinyHttpServer` 读超时测试受宿主网络慢影响，需合理设置 `500ms` 级别超时。
7. **是否值得实施**：**是**。

### 方案 B（L2）：建立轻量 `androidTest` 核心链路（`ActivityScenario` + D-Pad + WebView 崩溃）（`AUD-15`）
1. **当前实现**：无 `app/src/androidTest/` 目录与 `androidx.test` 依赖。
2. **存在的问题**：类似阶段 5.5 的 D1–D5（Media3 抢占方向键、状态文案覆盖）以及 WebView 崩溃恢复在 JVM 阶段结构性不可见。
3. **代码证据**：`app/build.gradle.kts:91-95`；`PlayerActivity.kt:747-794`；`CctvWebPlayerActivity.kt:612-645`。
4. **预期收益**：把最易回归的按键拦截、生命周期释放与 `onRenderProcessGone` 纳入自动化验证。
5. **实施成本**：2–3 人日 / 中复杂度（需按 `AGENTS.md` §2.3 申请引入 `androidx.test:runner` / `espresso-core` 等测试依赖，并配置本地/CI 模拟器运行）。
6. **风险与回滚点**：模拟器在 CI 运行较慢（3–5 分钟），建议仅放关键冒烟用例（5–8 个），其余继续留在 L1。
7. **是否值得实施**：**是**（作为第二阶段关键工程投入）。

### 方案 C（L3）：真机 ADB 播放与性能回归脚本
1. **当前实现**：依赖人工执行 `adb shell am start` 与人工肉眼核对 `logcat`。
2. **存在的问题**：耗时且易遗漏首帧耗时（TTFF）与切台内存上涨问题。
3. **代码证据**：`docs/TESTING.md:145-187`。
4. **预期收益**：1 条脚本即可在真机上自动校验 MGTV/CCTV 启屏、`NO_ERROR_CARD`、零 `Cleartext`/`AndroidRuntime` 报错并记录 TTFF 基线。
5. **实施成本**：1 人日 / 低复杂度（复用 `.tmp/toolchain/adb-wrapper.sh` 编写 Shell 回归脚本）。
6. **风险与回滚点**：依赖已联网真机设备，仅作本地/发布前验收，不阻塞无设备环境下的 PR CI。
7. **是否值得实施**：**是**。

### 方案 D（L4）：CI 增加 `assembleRelease` 与 Release APK 启动冒烟（`AUD-04`）
1. **当前实现**：`android-ci.yml` 只跑 Debug；`release.yml` 只跑 `assembleRelease` + `apksigner verify`。
2. **存在的问题**：R8 混淆与资源压缩问题要等到发版或人工安装真机才能暴露。
3. **代码证据**：`.github/workflows/android-ci.yml:53-114`；`.github/workflows/release.yml:83-94`。
4. **预期收益**：在 PR 合入前拦截 R8 编译与资源压缩问题，发版前自动验证 Release APK 可启动无崩溃。
5. **实施成本**：1 人日 / 低复杂度（`android-ci.yml` 加 `assembleRelease`；`release.yml` 可选接 `android-emulator-runner` 启动冒烟）。
6. **风险与回滚点**：`assembleRelease` 无签名配置时也可完成 R8 校验；模拟器步骤需注意 Runner 配额。
7. **是否值得实施**：**是**。
