# LiveTV

面向 **Android TV** 与 **Android 手机/平板** 的双端直播电视客户端，基于单 `app` 模块与纯原生技术栈构建（无 DI 框架、无数据库、无第三方网络/UI 重型依赖）。

仓库地址：<https://github.com/arbaleast/livetv> · 最新版本发布：[GitHub Releases](https://github.com/arbaleast/livetv/releases)

---

## 核心特性

- **双端自适应入口**（`MainActivity`）：自动识别设备形态，Android TV 进入 Leanback 横向浏览界面，手机/平板进入暗黑影院风触控首页（竖屏网格 / 横屏双栏「继续观看 + 频道墙」）。
- **双源频道目录**：整合 **CCTV 静态目录**（元数据）与 **MGTV 公开目录**（免签名抓取 + 播放地址解析），由分槽协调器合并写入全局快照。
- **原生 Media3 播放器（MGTV / IPTV）**：
  - 支持 HLS 直播流播放、亮度/音量手势、画中画（PiP）、横竖屏切换与选台抽屉；
  - 集成 **Anime4K v4 CNN（2x / 4x）OpenGL 实时超分辨率**（`media3-effect`），支持「超分 关 / AI 超分 / AI 强力」三档切换；
  - 集成 **Stats for Nerds 视频信息悬浮面板**（实时展示编码、分辨率、帧率、码率、丢帧与超分状态）；
  - 失败时按每源 2 次退避重试（`1500ms / 3000ms`）→ 自动切备用源；HTTP `401 / 403 / 429` 跳过退避立即切源。
- **手机端 CCTV 隔离播放器（`webplayer/`）**：
  - 在独立 `WebView` 容器内加载官方直播页（CCTV 1–17 及 4K 点播池），提供两阶段纯净视口聚焦与原控切换；
  - 精确外围资源黑名单拦截（P2P/埋点/非播放资源）+ 核心播放器脚本/WASM 进程内 LRU 缓存；
  - 支持 **1-Slot 频次/跳转习惯概率预热** 与 **上一台保活回切（12s TTL）**，实现常见切台与回切的瞬切体验。
- **手机端高频体验细节**：支持实时内联搜索（滑动自动收起软键盘）、本地收藏、空态/断网重试按钮与防连点保护。

---

## 技术栈

| 组成 | 选型 | 版本 |
|---|---|---|
| 语言 | Kotlin | `1.9.24` |
| 播放器与特效 | AndroidX Media3（`exoplayer` / `ui` / `exoplayer-hls` / `effect`） | `1.4.1` |
| TV UI 框架 | AndroidX Leanback | `1.0.0` |
| 视图绑定 | ViewBinding | — |
| 构建工具 | Android Gradle Plugin / Gradle Wrapper | `8.5.2` / `8.7` |
| SDK | `compileSdk` / `targetSdk` / `minSdk` | `34` / `34` / `28` |

异步与网络仅使用 JDK `HttpURLConnection`、`ExecutorService` 与主线程 `Handler`，详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

---

## 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | `app/build.gradle.kts` 使用 `jvmTarget = "17"` |
| Android SDK Platform | `android-34` | 与 `compileSdk` 一致 |
| Android SDK Build-Tools | `34.0.0` | |
| Android SDK Platform-Tools | 任意近期版本 | 真机调试需要 `adb` |
| Gradle | Wrapper 提供（`8.7`） | 禁止使用全局 `gradle` 命令 |

本机 SDK 路径通过 `local.properties` 的 `sdk.dir` 指定（不入库，模板见 `local.properties.example`）。

---

## 构建、测试与发布

所有 Gradle 命令建议附加 `--console=plain --no-daemon`（详见 [AGENTS.md](AGENTS.md)）：

```bash
# 纯 JVM 单元测试（56 个测试套件 / 440 个用例，无需模拟器）
./gradlew testDebugUnitTest --console=plain --no-daemon

# Android Lint 静态检查
./gradlew lintDebug --console=plain --no-daemon

# 构建 Debug APK（产物：app/build/outputs/apk/debug/app-debug.apk）
./gradlew assembleDebug --console=plain --no-daemon

# 交付前全量校验
./gradlew lintDebug testDebugUnitTest assembleDebug --console=plain --no-daemon
```

### Release 签名与自动发版

1. **本地签名构建**：复制 `keystore.properties.example` 为 `keystore.properties` 并配置 Keystore 路径与密码（均已加入 `.gitignore`，严禁入库），运行：
   ```bash
   ./gradlew assembleRelease --console=plain --no-daemon
   # 产物：app/build/outputs/apk/release/app-release.apk
   ```
2. **GitHub Actions 自动发版**：在仓库 `Settings -> Secrets and variables -> Actions` 配置 `RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 后，推送 `v*` 标签（如 `git tag -a v1.0.3 -m "..." && git push origin v1.0.3`）即可通过 [.github/workflows/release.yml](.github/workflows/release.yml) 自动执行 Lint、单测、签名构建、验签并发布至 GitHub Releases。

### 真机安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.livetv.android/.MainActivity
```

---

## 目录结构

```text
app/src/main/java/com/livetv/android/
├── LiveTvApplication.kt            # 全局单例：频道目录 / 协调器 / 播放源解析
├── MainActivity.kt                 # 入口分流：TV 走 Leanback，触控设备走 ui/mobile
├── data/
│   ├── cctv/                       # CCTV 静态目录加载与协调器
│   └── mgtv/                       # MGTV 公开目录抓取、签名与播放地址解析
├── domain/                         # 纯 Kotlin 领域模型、频道快照仓库与流解析抽象
├── media/                          # Anime4K CNN 超分 GLSL 特效、ScopedHeaderDataSource、错误策略
├── player/                         # 播放状态机与策略层（PlayerManager 之外均为纯 Kotlin）
├── util/                           # 设备形态判定、超分档位策略、手势与按键策略
├── webplayer/                      # 手机端 CCTV 隔离 WebView 播放器、静态资源缓存与预热池
└── ui/
    ├── MainBrowseFragment.kt       # Android TV Leanback 浏览页
    ├── PlayerActivity.kt           # 原生 Media3 播放页（TV + 手机共用）
    └── mobile/                     # 手机/平板首页、共享播放浮层、选台抽屉与加载过渡遮罩
```

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 开发红线、构建规范与按需阅读索引 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构分层、数据流向、技术债与演进说明 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 环境搭建、Gradle 构建与 ADB 调试手册 |
| [docs/TESTING.md](docs/TESTING.md) | 纯 JVM 单测策略与分层守卫测试 |
| [docs/TV-INTERACTION.md](docs/TV-INTERACTION.md) | 遥控器交互、焦点记忆与 Presenter 生命周期 |
| [docs/CCTV-INTEGRATION.md](docs/CCTV-INTEGRATION.md) | 手机端 CCTV WebView 集成方案与性能基线 |
| [docs/CCTV-MEDIA-PIPELINE.md](docs/CCTV-MEDIA-PIPELINE.md) | CCTV 媒体管线合规边界与研究归档 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 已实现能力与后续规划 |

---

## 当前边界与限制

- **双端差异**：Android TV 端保持原生 Leanback + ExoPlayer，暂不支持 CCTV WebView 播放；CCTV 1–17 仅在手机/平板端播放。
- **尚未实现**：EPG 节目单、云端同步历史与独立设置页。
- **网络安全**：Release 构建禁止明文 HTTP；仅 `debug` 构建对 `127.0.0.1` / `localhost` 放行。
- **测试覆盖**：全部 56 个测试套件（440 个用例）为纯 JVM 单元测试，UI 与真机播放链路通过真机 ADB 回归验证。
