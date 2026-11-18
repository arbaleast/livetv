# DEVELOPMENT — 环境、构建与调试

## 1. 环境要求

| 组件 | 版本 | 校验命令 |
|---|---|---|
| JDK | **17**（AGP 8.5 硬性要求） | `java -version` |
| Android SDK Platform | **android-34** | `sdkmanager --list_installed` |
| Android SDK Build-Tools | **34.0.0** | 同上 |
| Platform-Tools | 任意近期版本（仅真机调试需要 `adb`） | `adb version` |
| Gradle | 用仓库自带 Wrapper（8.7），**不要**装全局 gradle | `./gradlew --version` |

`app/build.gradle.kts` 里 `sourceCompatibility/targetCompatibility = VERSION_17`、`jvmTarget = "17"`，
JDK 版本不一致会在编译期直接失败。

## 2. 准备 Android SDK

三种方式任选其一：

1. **脚本（无 Android Studio 时）**：

   ```bash
   chmod +x scripts/install-android-sdk.sh
   ./scripts/install-android-sdk.sh "$(pwd)"    # 安装 SDK 并写 local.properties
   ```

   脚本用 F-Droid 版 `sdkmanager`（pip 包）从 `dl.google.com` 下载
   `platform-tools` / `platforms;android-34` / `build-tools;34.0.0`，**需要能访问 dl.google.com**。

2. **Android Studio**：SDK Manager 勾选 Platform 34 + Build-Tools 34.0.0。

3. **已有 SDK**：直接写 `local.properties`：

   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```

> `local.properties` 已被 `.gitignore` 排除，**永远不要提交**；模板见 `local.properties.example`。
> 也可用环境变量 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 代替（此时可不写 `local.properties`）。

## 3. 常用 Gradle 命令

**所有命令都加 `--console=plain --no-daemon`**（进度条会刷屏污染 Agent 上下文，见 `AGENTS.md`）。

| 目的 | 命令 |
|---|---|
| 编译检查（不打包） | `./gradlew compileDebugKotlin --console=plain --no-daemon` |
| 单元测试（纯 JVM） | `./gradlew testDebugUnitTest --console=plain --no-daemon` |
| Lint | `./gradlew lintDebug --console=plain --no-daemon` |
| 构建 Debug APK | `./gradlew assembleDebug --console=plain --no-daemon` |
| 交付前全量 | `./gradlew lintDebug testDebugUnitTest assembleDebug --console=plain --no-daemon` |
| 清理 | `./gradlew clean --console=plain --no-daemon` |

产物与报告位置：

| 产物 | 路径 |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Lint 报告 | `app/build/reports/lint-results-debug.html` |
| 单测报告 | `app/build/reports/tests/testDebugUnitTest/index.html` |
| 单测 XML | `app/build/test-results/testDebugUnitTest/*.xml` |

调试技巧：

```bash
# 只看失败与告警，避免刷屏
./gradlew lintDebug --console=plain --no-daemon 2>&1 | grep -E "FAILURE|error:|warning:|^> Task" 

# 定位单测失败原因
grep -r "<failure" app/build/test-results/testDebugUnitTest/
```

## 4. 安装与运行

```bash
adb devices                                   # 确认设备已连接
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.livetv.android/.MainActivity
adb shell am start -n com.livetv.android/.ui.PlayerActivity   # 播放页（需带 extra，单独启动会 finish）
```

应用包名：`com.livetv.android`；入口 Activity：`.MainActivity`（`LEANBACK_LAUNCHER`）。

## 5. ADB 日志抓取规范

```bash
# 1) 实时过滤（推荐：先清空，再复现）
adb logcat -c
adb logcat -v time | grep -E "livetv|AndroidRuntime|ExoPlayer|Media3|PlayerActivity"

# 2) 一次性导出到文件（不要把全量 logcat 灌进对话）
adb logcat -d -v time > .tmp/logcat.txt
grep -E "E/|W/|FATAL|AndroidRuntime" .tmp/logcat.txt | tail -200

# 3) 按进程过滤（需先 pidof）
adb shell pidof com.livetv.android
adb logcat --pid=$(adb shell pidof com.livetv.android) -v time

# 4) 崩溃栈
adb logcat -d -b crash -v time
```

抓日志约定：

- **先 `adb logcat -c` 再复现问题**，否则历史日志会淹没现场。
- 全量 logcat 必须落文件再用 `grep` 过滤，**不要**直接把几万行贴进对话或提交进仓库。
- 导出文件放 `.tmp/`（已被 `.gitignore` 排除），**不要**提交日志文件。
- 播放器问题优先看 `ExoPlayer` / `Media3` / `PlaybackException` 相关 tag。

## 6. 真机 / 模拟器调试补充

```bash
adb shell dumpsys media_session                 # 播放会话状态
adb shell dumpsys activity activities | grep -i livetv
adb shell input keyevent KEYCODE_DPAD_DOWN    # 模拟遥控器方向键
adb shell input keyevent KEYCODE_DPAD_CENTER  # 模拟 OK 键
adb shell input keyevent KEYCODE_BACK
```

> `android.hardware.touchscreen` 声明为 `required=false`，因此**手机上也能装**（但界面为遥控器布局，
> 触屏体验不是设计目标）。

## 7. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `SDK location not found` | 缺 `local.properties` / `ANDROID_HOME` | 按第 2 节配置 |
| `sdkmanager` 报 `NoSuchFileException: ~/.android/cache/...` | `$HOME` 不可写，sdkmanager 写不了缓存 | `export ANDROID_USER_HOME=<可写目录>` 后重试 |
| 依赖下载超时 | `settings.gradle.kts` 已配阿里云镜像；仍失败时检查网络/代理 | 重跑或换可用镜像 |
| `Unsupported class file major version` | JDK 版本不是 17 | 切换到 JDK 17 |
| Lint 报 `MissingTranslation` / `HardcodedText` | 新增了硬编码文案 | 改用 `strings.xml` |
| Lint 报 `UnsafeOptInUsageError` | 用了 Media3 等库标注 `@UnstableApi` 的实验 API | 在类上加 `@androidx.annotation.OptIn(UnstableApi::class)`（本仓库 `PlayerActivity` 已如此处理） |
| Gradle 卡住 / 内存不足 | 守护进程残留 | `--no-daemon`；必要时调 `gradle.properties` 的 `org.gradle.jvmargs` |
