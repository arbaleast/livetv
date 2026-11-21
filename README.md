# LiveTV

面向 Android TV 的直播电视客户端原型，面向 AI Agent 与人类开发者长期协作、可重复构建、可验证。
**尚未**实现 EPG、搜索、收藏、历史与设置页等业务能力。

仓库地址：<https://github.com/arbaleast/livetv>

## 技术栈

| 组成 | 选型 | 版本 |
|---|---|---|
| 语言 | Kotlin | 1.9.24 |
| UI 框架 | AndroidX Leanback（`androidx.leanback`） | 1.0.0 |
| 播放器 | AndroidX Media3 ExoPlayer + HLS（`androidx.media3`） | 1.4.1 |
| 视图绑定 | ViewBinding | — |
| 构建 | Android Gradle Plugin / Gradle Wrapper | 8.5.2 / 8.7 |
| SDK | compileSdk / targetSdk / minSdk | 34 / 34 / 28 |

无 DI 框架、无数据库、无第三方网络/UI 框架：网络只用 JDK `HttpURLConnection`，异步只用
`ExecutorService` + 主线程 `Handler`，单 `app` 模块。详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | AGP 8.5 要求 JDK 17；`app/build.gradle.kts` 的 `jvmTarget = "17"` |
| Android SDK Platform | android-34 | 与 `compileSdk` 一致 |
| Android SDK Build-Tools | 34.0.0 | |
| Android SDK Platform-Tools | 任意近期版本 | 仅真机调试需要 `adb` |
| Gradle | 由 Wrapper 提供（8.7） | 勿用全局 `gradle` 命令 |

SDK 路径通过 `local.properties` 的 `sdk.dir` 指定（该文件不入库，模板见 `local.properties.example`）。
没有 SDK 时可运行 `scripts/install-android-sdk.sh`（需能访问 `dl.google.com`）。

## 构建与运行

所有 Gradle 命令都带 `--console=plain --no-daemon`（详见 [AGENTS.md](AGENTS.md)）：

```bash
# 单元测试（纯 JVM，无需模拟器）
./gradlew testDebugUnitTest --console=plain --no-daemon

# Lint 静态检查
./gradlew lintDebug --console=plain --no-daemon

# 构建 Debug APK（产物：app/build/outputs/apk/debug/app-debug.apk）
./gradlew assembleDebug --console=plain --no-daemon

# 交付前全量校验
./gradlew lintDebug testDebugUnitTest assembleDebug --console=plain --no-daemon
```

安装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.livetv.android/.MainActivity
```

调试与日志抓取规范见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。

## 目录结构

```
app/src/main/java/com/livetv/android/
├── LiveTvApplication.kt            # 全局单例：频道目录 / MGTV 协调器 / 播放源解析
├── MainActivity.kt                 # 入口分流：TV 走 Leanback，触控设备走 ui/mobile
├── data/mgtv/                      # 唯一频道来源：MGTV 公开目录 + 播放源
│   ├── MgtvEndpoints.kt            # 目录/播放源 URL 构造
│   ├── MgtvCatalogFetcher.kt       # 公开频道目录抓取（免签名）
│   ├── MgtvCatalogCoordinator.kt   # 后台拉目录 + 主线程回传
│   ├── MgtvJsonParser.kt           # JSONP 剥离 + 目录/播放源解析
│   ├── MgtvProvider.kt             # 目录 → 领域频道（mgtv:<cameraId>，捞回聚合独有频道）
│   ├── MgtvStreamResolver.kt       # mgtv: 频道的播放地址解析
│   └── ...                         # 签名 / HTTP 客户端 / 播放源筛选
├── domain/
│   ├── model/                      # Channel / ChannelCategory
│   ├── repository/ChannelCatalogStore.kt  # 应用级频道快照（手机/TV/播放页共用）
│   └── stream/                     # 通用播放会话解析（纯 Kotlin）
├── player/                         # 播放层（除 PlayerManager 外为纯 Kotlin 策略层）
└── ui/
    ├── MainBrowseFragment.kt       # Leanback 浏览页
    ├── ChannelCardPresenter.kt     # 频道卡片 Presenter
    ├── PlayerActivity.kt           # Media3 播放器页（HLS，TV 遥控器 + 触控双端）
    └── mobile/                     # 手机/平板触控 UI（RecyclerView 网格 + 搜索/收藏）

app/src/debug/                      # 仅 Debug 源集：放行 127.0.0.1 / localhost 明文
└── res/xml/network_security_config.xml
```

## 文档

| 文档 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | Agent 工作准则、构建命令规范、文档索引 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 真实代码结构、数据流向、现有技术债与演进方向 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 环境准备、构建/Lint/测试命令、ADB 调试 |
| [docs/TESTING.md](docs/TESTING.md) | 测试策略与分层边界 |
| [docs/TV-INTERACTION.md](docs/TV-INTERACTION.md) | 遥控器交互、焦点记忆、Presenter 生命周期规范 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 已实现能力与后续阶段规划 |

## 当前状态与限制

- 频道**只来自 MGTV 公开目录**（`data/mgtv/`，公开免签名接口）。内置 assets 与 M3U 订阅管线已移除：
  手机端顶栏保留「刷新」用于重新拉取目录，不再提供订阅源配置入口。
- 「热门」聚合分类不整段展示（避免重复卡片），但**只在该分类出现的频道会被捞回**并归入「热门」分组
  （例如 `344 湖南娱乐`），保证不丢台。
- 播放支持 HLS 直链；失败时按**每源 2 次退避重试（1500ms / 3000ms）→ 切换备用源 → 手动重置**处理。
  HTTP **401 / 403 / 429** 属于不可重试状态，会**跳过退避**、立即切备用源（无备用源则直接报错）。
- Release 包**不允许明文 HTTP**；只有 Debug 包额外放行 `127.0.0.1` / `localhost`，
  用于对接本地教学沙箱。
- 无 EPG、无播放历史、无设置页；手机端已支持频道搜索与本地收藏，TV 端提供「重新加载频道」入口。
- 无仪器测试（`androidTest`）与 UI 自动化，用例全部为纯 JVM 单元测试（当前 **285 个** / 31 个测试类）。
