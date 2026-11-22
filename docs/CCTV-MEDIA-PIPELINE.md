# CCTV-MEDIA-PIPELINE — CCTV 媒体数据处理可行性（P0 研究归档）

> **最终结论（2026-11-22 归档）**：
> - **原生 VDN + Media3 ExoPlayer 媒体管线：NO-GO**。实测官方移动端与桌面端直播页均通过 `live.worker.js` WASM DRM 及约 20 个官方外围脚本自举完成播放，无法合规直接喂给 Media3，本项目不实施任何自定义解密、WASM 移植或自定义 `Extractor`。
> - **现行落地方案（仅手机端）**：通过隔离包 `com.livetv.android.webplayer` 加载官方直播页（`cctv1`–`cctv17`），采用 `#player` 两阶段纯视频视口聚焦（仅 `opacity:0 + pointer-events:none`，不搬移 DOM、不模拟点击）、原生亮度/音量手势、双向选台与同页复用 `WebView`、`pauseTimers()`/`LOAD_DEFAULT` 缓存与 `onRenderProcessGone` 彻底销毁。
> - **已知边界与性能基线（OnePlus PKG110 / Android 16）**：
>   - `cctv4k` / `cctv8k` 官方页面 404，已提前短路提示不支持；
>   - 首次进页/切台需用户轻点官方播放键（合规不模拟点击）；
>   - Android TV 端保持「电视端暂不支持央视频道播放」兜底，不引入 WebView；
>   - 稳定播 `cctv1` 30s 基线：主进程 PSS **144.0 MB** / CPU **60.7%**，WebView 渲染进程 PSS **243.0 MB** / CPU **114.0%**；切后台 4s 降至 **7.1% / 17.8%**。
> - **合规红线**：本文件不设计、不评估、不实施任何 DRM / HDCP / 访问控制绕过；以下 §1–§8 保留为 P0 原生管线研究历史记录。

---

## 1. 结论摘要

| 场景 | 结论 | 依据 |
|---|---|---|
| 标准 HLS（明文或 AES-128，TS/fMP4 分片） | **无需自研解封装**，Media3 HLS 模块可直接播放 | 依赖已含 `media3-exoplayer-hls`；播放源由 `DefaultMediaSourceFactory` 自动选择 |
| 裸 MPEG-TS / 渐进式流 | 由 Media3 默认 Extractor 处理，**不需要自定义 `Extractor`** | `PlayerManager` 的 `DefaultMediaSourceFactory`（`PlayerManager.kt:661-668`） |
| 非标准封装 / 需要重写分片 | 需要**样本**才能评估；当前无证据 → `UNVERIFIED` | 仓库无相关代码与样本 |
| `manifest.hls_cdrm`（若为受保护流） | **Media3 原生不可播**；不得自行解密 | 合规红线；见 §5、§6 |
| WASM 调用链 | 当前 APK **没有** JS/WASM 运行时；若用于解密 → 明确不实施 | 仓库无 WebView/JS 引擎/WASM 依赖；见 §4.3 |

---

## 2. 现状核查（仓库事实）

### 2.1 播放链路

- 依赖：`app/build.gradle.kts` —— `media3-exoplayer`、`media3-ui`、`media3-exoplayer-hls`，版本 `1.4.1`。
- 播放源构建：`player/PlayerManager.kt:661-668` 使用 `DefaultMediaSourceFactory(dataSourceFactory)`
  + `NoRetryOnClientErrorPolicy`，由 Media3 依据 URL/MIME **自动选择 HLS 或渐进式**。
- 显式 MIME：`domain/stream/StreamTypeDetector.kt:16` 定义 `MIME_HLS = "application/x-mpegURL"`，
  `:59-63` 按 `Content-Type` 或 URL 后缀推断；MGTV 解析器显式写入 HLS MIME
  （`data/mgtv/MgtvStreamResolver.kt:133`）。
- DataSource 扩展点：`media/ScopedHeaderDataSourceFactory.kt`（按 Host 过滤请求头 + 自行跟随 3xx）；
  `media/NoRetryOnClientErrorPolicy.kt`（401/403/429 不重试）。

### 2.2 仓库中**不存在**的媒体处理代码（已用全仓检索确认）

| 能力 | 检索结果 | 结论 |
|---|---|---|
| 自定义 `Extractor` / `ExtractorsFactory` | `grep -rn "Extractor\|ExtractorsFactory" app/src/main` 无命中 | 目前**没有**任何自研解封装 |
| MPEG-TS / PES / NAL 解析 | 无 `TsExtractor`、`Pes`、`NAL` 相关代码 | 无相关实现 |
| WASM / JS 引擎 | 无 `WebView`、`wasm`、`quickjs` 等依赖或代码 | 无运行时 |
| DRM / MediaDrm / 许可证 | 无 `MediaDrm`、`DrmSession` 相关代码 | 无 DRM 支持 |
| 媒体探针/转封装（ffmpeg 等） | 无 | 不涉及 |

> 这些"缺失"是**现状事实**，不构成缺陷：当前产品只播 HLS。

### 2.3 与媒体相关的既有约束

- 明文 HTTP：Release 禁止，Debug 仅放行 `127.0.0.1/localhost`
  （`docs/ARCHITECTURE.md` §9 与 `app/src/debug/res/xml/network_security_config.xml`）。
- 头隔离：`ResolvedStream.allowedHeaderHosts` + `HeaderHostPolicy`（`domain/stream/HeaderHostPolicy.kt:35-60`）；
  空集合 = 不限制（仅普通 IPTV 使用）。
- 同协议 302 残留：`docs/ARCHITECTURE.md:500/575` —— Media3 内部跟随同协议跳转时会沿用原请求头。

---

## 3. 标准媒体封装：Media3 能力边界（事实 vs 假设）

### 3.1 可以确认（仓库事实 + 依赖声明）

- 项目已含 **HLS 模块**，`DefaultMediaSourceFactory` 会为 `.m3u8` / `application/x-mpegURL`
  创建 HLS 播放源；因此**标准 HLS 不需要任何自研代码**。
- `ScopedHeaderDataSourceFactory` 证明可以在 DataSource 层做请求头过滤与重定向控制，
  这是未来 CDN 鉴权 URL 的合规接入点。

### 3.2 需要样本确认（UNVERIFIED）

| 假设 | 为什么不确认 | 确认方式 |
|---|---|---|
| VDN 返回的是标准 HLS（master + media playlist） | 无响应样本 | E4a / E4b |
| 分片为 MPEG-TS | 无样本 | E4c（`ffprobe` 轨道摘要 + 前 64KB） |
| 分片为 fMP4 | 无样本 | 同上 |
| 加密方式为明文或 AES-128 | 无样本 | E4b 的 `#EXT-X-KEY` / `#EXT-X-SESSION-KEY` 行 |
| `hls_cdrm` 是"受保护流"而非普通字段 | 无字段语义证据 | E5 |
| 需要自定义 `Extractor` | 标准 HLS 不需要；只有非标封装才需要 | E4c 样本 |
| 编码为 Android 可硬解/软解的格式 | 无样本 | E4a 的 `CODECS` + E4c 的 PMT `stream_type` |
| WASM 参与链路 | 无证据 | E9 |

> **禁止**在拿到样本前写"XX 封装一定可播"或"需要 AES 解密"之类的结论。

### 3.3 若最终是标准 HLS：预期链路（设计参考，未实现）

```
ResolvedStream(url, mimeType=application/x-mpegURL)
  → PlayerManager（零改动）
  → DefaultMediaSourceFactory → HlsMediaSource
  → playlist 解析（Media3 内部）→ 分片下载（DataSource/ScopedHeaderDataSourceFactory）
  → TsExtractor / FragmentedMp4Extractor（Media3 内置）
  → 解码（MediaCodec）
```

此路径下 **P3 只需要做"清单/URL/CDN 策略"**，不需要碰解封装。

### 3.4 若最终是非标封装：评估前置

必须同时具备：

1. 至少 3 个分片样本（首片 + 中间片 + 尾片）与对应清单（E4c）；
2. **公开标准规范或官方公开接口文档**对封装/字段的说明（不得使用逆向私有实现得到的结论）；
3. 明确"标准 `Extractor` 失败的证据"（不能只凭播放器报错推断）；
4. 合规评审通过（确认不触碰 DRM/HDCP/非公开访问控制）。

在此之前，任何 `CctvTsProcessor` 类实现都属于**无依据编码**，不做。

> **统一合规判定（与 `CCTV-INTEGRATION.md` 一致）**：只允许基于**公开标准规范或官方公开接口文档**；
> 凡涉及**逆向混淆签名、私有风控/防爬算法或非公开访问控制逻辑**，
> 无论是否直接解密音视频，一律判定为 **No-Go**。

---

## 4. 扩展边界（合规）

### 4.1 自定义 `DataSource`：允许

- 用途：请求头隔离、3xx 逐跳校验、CDN 鉴权 URL 重写、限速/重试统计。
- 现有先例：`media/ScopedHeaderDataSourceFactory.kt`。
- 约束：
  - 不得把 VDN 的 `auth-key` 带到 CDN 域（见 `CCTV-INTEGRATION.md` §3.6）；
  - 不得用空白名单"放行全部"；
  - 不得记录完整签名 URL 到日志（现有 `describeUri` 只打 scheme+host+path，
    `ScopedHeaderDataSourceFactory.kt:128-130`）。

### 4.2 自定义 `Extractor`：仅限标准容器

- 允许：为**非标准但未加密**的容器/分片重封装逻辑编写 `Extractor`；
- 前提：§3.4 的样本与证据齐备；
- 禁止：在 `Extractor`/`DataSource` 中实现任何 DRM 解密、密钥协商或授权绕过。

### 4.3 WASM 调用链：默认不实施

- 当前 APK **没有任何 JS/WASM 运行时**（无 WebView、无 quickjs、无 wasm 依赖）。
- 只有在 E9 证明 WASM 仅用于**公开、非安全的参数拼装**（且该逻辑在官方公开文档中有说明）时，
  才可以评估"用 Kotlin 等价实现"；**不得**为了跑 WASM 而引入 JS 引擎依赖。
- 若 WASM 参与签名、风控、授权或解密 → 属于私有/非公开逻辑，
  按 §3.4 的统一判定 **No-Go**，不做等价实现、不做逆向。

### 4.4 DRM / HDCP

- Media3 原生只支持标准 DRM 体系（Widevine/ClearKey 等，需要 `MediaDrm` 与许可证服务）。
- ChinaDRM（若 `hls_cdrm` 指该体系）**不是** Media3 原生能力；接入需要官方 DRM SDK 与授权，
  **不在本项目范围**。
- 任何"取密钥/解密/CENC 绕过"的实现都属于红线。

---

## 5. `manifest.hls_cdrm` 的判定与后果

> 现状：仓库中**没有**该字段的任何代码或样本，语义 `UNVERIFIED`。
> **判定时机：P1 与 P2 编码之前**——它是 `CCTV-INTEGRATION.md` §6.2 里 **G1+G2 准入门槛**的一部分，
> 不是"P2 做完再说"。判定依据只能是 E4a–E4d / E5 的真实样本，**不能凭播放器报错推断**。

| 类别 | 判定条件（须同时满足） | 后果 |
|---|---|---|
| **A. 标准明文 HLS** | ① 播放列表无 `#EXT-X-KEY` 且无 `#EXT-X-SESSION-KEY`；② E4c 中 MPEG-TS 包头 `transport_scrambling_control == 0`（排除无标签的隐式私有加扰）；③ `ffprobe` 或 Media3 能解析出完整音视频轨；④ 清单与分片全链路 `https://` | Media3 原生可播，P3 按 §3.7 的触发边界按需推进 |
| **B. 标准 HLS AES-128** | ① `#EXT-X-KEY:METHOD=AES-128,URI=...`；② 密钥 `URI` 的域名归属、HTTPS 协议与所需请求头明确，且能在 `ScopedHeaderDataSourceFactory` / `allowedHeaderHosts` 下**可访问（不得误拦）且不越白名单**；③ 清单、分片、密钥 URI 全链路 `https://` | Media3 可播（密钥 URI 需可达），P3 正常 |
| **B2. `METHOD=SAMPLE-AES` / `SAMPLE-AES-CTR`** | **不得直接等同于标准整片 AES-128**：需额外确认是否绑定私有授权体系，以及 Media3 1.4.1 的原生可播性 | 未经证实前按 C 类处理（No-Go） |
| **C. 受保护 DRM（ChinaDRM/CENC 等）** | `#EXT-X-KEY` 指向 DRM 许可/`skd://`，或清单为 CENC 封装 | **Media3 原生不可播**；**终止接入**（§6），不得自行解密 |

> **Release 明文 HTTP 约束**：Release 构建禁止明文 HTTP（`docs/ARCHITECTURE.md` §9）。
> 若实测播放链路（清单 / 分片 / 密钥 URI）出现 `http://`，必须在 G2 评审时标注
> **Release 不可播**，不得以"Debug 能播"作为通过依据。

### 5.1 编解码器与设备兼容性（需样本确认）

- 编码由 E4a 的 `CODECS` 与 E4c 的 PMT `stream_type` 决定，必须在 G2 阶段核对：

| 编码 | Android `MediaCodec` 现状 | 风险 |
|---|---|---|
| H.264 / AVC + AAC | 基线能力，几乎所有设备支持 | 低 |
| HEVC / H.265 | 多数 Android 8+ 支持，但分辨率/HDR/档次差异大 | 中（需目标机型实测） |
| **AVS2 / AVS3** | 标准 Android 平台**普遍不支持**，依赖厂商扩展或软解 | **高**（可能"解封装成功但无法解码"） |
| **DRA 音频** | 非 Android 标准编解码器 | **高** |

- 结论：即使封装是标准 HLS，**AVS2/AVS3/DRA 也可能不可播**；G2 需要追加
  "目标机型可解码"的证据（实机 `MediaCodecList` 查询或播放验证），否则同样判 No-Go。

---

## 6. 不实施范围声明（红线）

以下内容**明确不属于本项目交付范围**，任何阶段都不得实现：

1. 未经授权的 DRM / HDCP / 访问控制绕过（含 ChinaDRM、Widevine 破解、CENC 解密）；
2. 从客户端提取/导出密钥、许可证或解密后的媒体；
3. 复用 Cookie、他人账号会话或抓取到的凭据；
4. 伪造设备指纹/客户端签名以规避风控；
5. 以"让播放器不报错"为目标跳过清单校验或隐藏错误。

若 P2 的证据显示唯一可播路径需要上述任一能力 → **直接终止 CCTV 接入**（No-Go），
**不在主分支保留「仅展示不可播目录」的降级入口**（与 `CCTV-INTEGRATION.md` §6.2 的冻结规则一致）。

---

## 7. 媒体侧样本清单（统一使用 `CCTV-INTEGRATION.md` §6.1 的 E 系列编号）

| 编号 | 样本 | 格式要求 |
|---|---|---|
| **E4a** | Master Playlist | 原文（可脱敏域名）；需含 `#EXT-X-STREAM-INF` 的 `CODECS`、`BANDWIDTH`、`RESOLUTION` |
| **E4b** | Media Playlist + 密钥声明 | 原文；必须包含 `#EXT-X-KEY` / `#EXT-X-SESSION-KEY`（若有）；AES-128 时给出密钥 `URI` 的 Host 与协议（**不要提供密钥内容**） |
| **E4c** | 分片轨道摘要 + 样本 | `ffprobe` 结构化摘要（容器、Codec、Profile、加密标志、TS 加扰位 `transport_scrambling_control`、PMT `stream_type`）+ **首个分片前 64KB** 样本（严禁包含任何密钥） |
| **E4d** | 媒体 HTTP 响应头 | CDN 返回的 `Content-Type`、`Content-Length`、`Location`（若有） |
| **E5** | `hls_cdrm` 语义 | 该字段取值示例、是否加密、密钥获取方式（**不要提供密钥内容**） |
| **E6** | CDN / 密钥域与跳转 | 302 前后 Host、Location、所需请求头；若存在 `#EXT-X-KEY`，一并给出密钥域的 Host/协议/请求头 |
| **E9** | WASM 证据 | 若链路含 WASM，给出调用位置、输入输出说明及其是否为公开文档描述的逻辑（不得含密钥） |

> **样本大小要求**：E4c 的"前 64KB"约 340 个 MPEG-TS 包，足以覆盖首个 PES/SPS/PPS NAL；
> 若为 fMP4，应覆盖 `moov`/`moof`。**1KB 不够**，不得再用旧规格。

---

## 8. 待人工确认的问题

1. VDN 返回的到底是 HLS 还是其它封装/协议？（E4a–E4d）
2. 是否存在可直接播放的明文 HLS 变体？（决定 G2 是否通过）
3. `hls_cdrm` 的准确语义（字段说明或官方公开文档）；（E5）
4. 若判定为受保护流（C 类 / 未证实的 B2 类），是否接受**直接终止 CCTV 接入**、
   不在主分支保留"仅展示不可播目录"的降级入口？（需产品确认）
5. 是否允许引入官方 DRM SDK（若未来要播受保护流）？—— 当前默认**不允许**；
   即使允许，也必须重新走 G1–G5 准入。
