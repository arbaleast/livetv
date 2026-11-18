# LiveTV

面向 Android TV 的直播电视客户端原型。当前阶段（阶段一）目标是**工程基线**：把原型整理成
适合 AI Agent 与人类开发者长期协作、可重复构建、可验证的工程；**尚未**实现频道源配置、EPG、
搜索、收藏等业务能力。

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

无 DI 框架、无数据库、无网络层框架，单 `app` 模块。详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

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
├── MainActivity.kt                 # 宿主 Activity，装载 MainBrowseFragment
├── data/                           # 数据层
│   ├── model/ChannelConfigDto.kt   # JSON 映射 DTO
│   ├── parser/ChannelJsonParser.kt # JSON 解析与安全校验（纯函数）
│   ├── source/                     # 数据源接口 + assets 实现
│   └── repository/DefaultChannelRepository.kt
├── domain/                         # 领域层
│   ├── model/                      # Channel / ChannelCategory
│   └── repository/ChannelRepository.kt
└── ui/
    ├── MainBrowseFragment.kt       # Leanback 浏览页（通过 Repository 取分类）
    ├── ChannelCardPresenter.kt     # 频道卡片 Presenter
    └── PlayerActivity.kt           # Media3 播放器页（HLS）

app/src/main/assets/channels.json   # 默认频道配置（分类 + 频道）
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

- 频道列表来自 APK 内的 `assets/channels.json`（默认 6 个 CCTV HLS 直播源），
  改配置需要改文件后**重新打包**；暂不支持 M3U 导入、网络拉取与用户自定义源。
- 播放仅支持 HLS 直链，播放失败时在页面上显示错误文案，不重试、不换源
  （`backupUrls` 备用源字段已在模型中预留，但尚未接线）。
- 无 EPG、无搜索、无收藏、无播放历史、无设置页。
- 无仪器测试（`androidTest`）与 UI 自动化，用例全部为纯 JVM 单元测试（当前 22 个）。
