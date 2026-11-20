# MGTV 播放源：签名还原与接入报告（原 No-Go 已解除）

> 状态：**播放源接口已跑通**。`/v1/live/source` 的签名算法已从芒果 TV 网页播放器公开 JS 中还原，
> 并用 HAR 中 3 组真实请求做了**离线校验**（3/3 命中），随后用真实网络请求验证到
> **HTTP 200 + 合法 HLS 清单 + 真实 MPEG-TS 分片**。
> 本文档不含任何真实 Cookie / Token / 抓包 `did` / 抓包 `sign` / 真实播放地址。

---

## 1. 原 No-Go 结论为何被推翻

原判断是「`sign` 依赖私有密钥，纯客户端无法合规获得」。实际情况是：
**签名不需要密钥**，它是一个"盐值 + 参数拼接"的 MD5，算法就写在网页播放器的 JS 里，
随前端代码一起公开下发；`did`/`deviceId` 也只是浏览器自己生成的 UUID，不需要服务端签发。

| 原假设 | 实际情况 |
|---|---|
| `sign` 由私有密钥签发，无法获得 | 无密钥；算法见 §2，盐值是 JS 里的公开常量 |
| `did`/`deviceId` 需服务端签发 | 网页端本地生成 UUID，首次写入后复用 |
| 需要浏览器 Cookie | 不需要。带 `sign` 的请求不带 Cookie 也返回 `code=0` |

---

## 2. 签名算法（已还原）

```text
sign = UPPER( MD5( SALT + sortedParams + SALT ) )

SALT         = "LMFwh1k1m@pvt#Pt"          // 来自 vplayer.*.js 中的公开常量
sortedParams = 参数名升序，逐项拼接 key + value（无分隔符）
```

三条必须严格遵守的细节：

1. **空字符串参与拼接**——`activityId=""`、`uid=""`、`token=""`、`local_definition=""` 都算，
   只有 `null`/缺失才排除；
2. **`sign` 与 `_support` 不参与签名**——它们在签名算完之后才追加到查询串末尾；
3. 参与签名的参数集必须与实际发出的**完全一致**，少一个或多一个都会得到业务码 `2040117`。

实现：`app/src/main/java/com/livetv/android/data/mgtv/MgtvSigner.kt`。

### 离线校验（对 HAR 3 组样本）

| 样本 | 频道 | `_t` | 还原算法输出 | HAR 中捕获值 | 结论 |
|---|---|---|---|---|---|
| HAR#1 #151 | 287 金鹰卡通 | 1795153145590 | `01608E…487D` | `01608E…487D` | ✅ 一致 |
| HAR#2 #144 | 287 金鹰卡通 | 1795173078651 | `CE528B…FF3B` | `CE528B…FF3B` | ✅ 一致 |
| HAR#2 #161 | 344 湖南娱乐（切台） | 1795173082033 | `1DFFF7…0947` | `1DFFF7…0947` | ✅ 一致 |

> 说明：上表只展示前后各 6 位；完整值仅存在于本地 HAR，未写入仓库。
> 校验脚本：`.tmp/mgtv-re/verify_sign.mjs`（gitignored）。

---

## 3. 端到端验证（真实网络，非模拟）

### 3.1 直接 HTTP 探针

```
cameraId=287 http=200 code=0  isp2p=0 hdcp=0
  source0: format=.m3u8 def=1 name=480P
  m3u8 http=200  content-type=application/vnd.apple.mpegurl  bytes=241
  #EXTM3U / #EXT-X-VERSION:3 / #EXT-X-TARGETDURATION:5 / #EXTINF
cameraId=344 http=200 code=0  同上
```

分片校验：

```
urlExpireDuration(s) = 28800   isp2p = 0   hdcp = 0
segment  = JYKTMPP360-54897564-1-hls.ts
http     = 200   content-type = video/MP2T   bytes = 437,664
首字节   = 47 40 00 10 …   （0x47 = MPEG-TS sync）
```

### 3.2 用**真实编译产物**跑完整链路

驱动脚本直接调用编译后的 `MgtvStreamResolver` + `HttpMgtvSourceFetcher`（不经 mock）：

```
cameraId=287 RESOLVED ok
  mime=application/x-mpegURL  hosts=[mgtv.com]  def=480P
  m3u8 http=200  ct=application/vnd.apple.mpegurl  bytes=241  has #EXTINF
cameraId=344 RESOLVED ok
  m3u8 http=200  ct=application/vnd.apple.mpegurl  bytes=241  has #EXTINF
```

即：**签名 → 下发地址 → HLS 清单 → TS 分片**这条链在真实网络下全部打通。

---

## 4. 设备标识的处理

`did` 与 `deviceId` 取同一个 UUID：

- 生产：`SharedPrefsMgtvDeviceId`（`SharedPreferences("livetv_mgtv")`）首次生成后持久化复用；
- 测试：`EphemeralMgtvDeviceId` 每次随机。

这是网页/客户端本来就有的本地设备标识，**不是冒用他人身份**：请求不携带任何 Cookie，
也没有从 HAR 里复制任何真实 UUID。

---

## 5. 代码落点

| 文件 | 职责 |
|---|---|
| `data/mgtv/MgtvSigner.kt` | 签名算法（纯 Kotlin，无网络无存储） |
| `data/mgtv/MgtvDeviceId.kt` | 设备 UUID 提供者（持久化 / 临时两种实现） |
| `data/mgtv/MgtvEndpoints.kt` | `sourceParams()` 组装待签名参数；`sourceUrl(params, sign)` 追加 `_support` 与 `sign` |
| `data/mgtv/HttpMgtvSourceFetcher.kt` | 组装参数 → 本地签名 → 发起请求；可注入 signer / urlBuilder 便于离线测试 |
| `data/mgtv/MgtvStreamResolver.kt` | 默认 fetcher 已切换为真实 HTTP 实现（不再是 fail-closed 占位） |
| `LiveTvApplication.kt` | `MgtvStreamResolver(HttpMgtvSourceFetcher(SharedPrefsMgtvDeviceId(this)))` |

已删除：`MgtvCredentials.kt`（`MgtvCredentials` / `MgtvCredentialProvider` / `NoMgtvCredentials`）
——签名改为本地计算后，这套外部凭据注入抽象不再需要。

---

## 6. 修复记录（真机验证通过）

以下 4 项原阻断已修复，并在 OnePlus PKG110 / Android 16 上实测：

| # | 原问题 | 修复 | 真机结果 |
|---|---|---|---|
| M1 | `switchChannel()` 主线程同步解析，接真实 fetcher 必崩 `NetworkOnMainThreadException` | 会话解析移到单线程 `resolutionExecutor`，回主线程前用 `SessionGenerationGate` 校验代际号，快速连点不会串台；`streamCoordinator` 的所有调用都收敛到该线程 | ✅ 点开「金鹰卡通」正常出画，无崩溃、无 `NetworkOnMainThread` |
| M2 | Media3 底层 `DefaultLoadErrorHandlingPolicy` 会把 401/403/429 再重试 3 次 | 新增 `media/NoRetryOnClientErrorPolicy`，命中 401/403/429 返回 `C.TIME_UNSET`（0 次底层重试），其余错误保留默认退避 | ✅ 只发一次请求即快速失败 |
| P5 | MGTV 频道没进 UI | `MgtvCatalogCoordinator`（后台拉目录 + 主线程回传）→ 手机端作为**独立分组**追加到列表末尾；`MgtvProvider` 的 `description` 兜底为「芒果TV」 | ✅ 首页出现 热门 9 / 卫视台 3 / 地方台 5 / 特色频道 4，共 31 个频道 |
| 302 | 真机偶发「服务器返回错误（HTTP 302）」 | MGTV CDN 会按调度把请求 302 到别的边缘节点；原实现为防请求头泄漏关闭了跨协议跳转，导致 302 直接报错。改为在 `ScopedHeaderDataSource` 里**自己跟随跳转**（最多 5 跳、仅接受 https、每跳重新过滤请求头） | ✅ 修复后连续播放 25 秒+，画面持续更新 |

### 真机实测证据（2026-11-20 21:04–21:25 +0800）

| 场景 | 结果 | 截图 |
|---|---|---|
| 首页出现 MGTV 分组 | ✅ 共 31 个频道（内置 10 + MGTV 21） | `.tmp/device-check/mgtv_home.png` |
| 点开「金鹰卡通」出画 | ✅ 真实动画画面 + 台标「金鹰卡通」+ 字幕 | `.tmp/device-check/mgtv_play4.png` |
| 持续播放（15 秒后） | ✅ 画面已推进到不同镜头 | `.tmp/device-check/mgtv_play5.png` |
| 302 修复后重新播放 | ✅ 出画 | `.tmp/device-check/mgtv_after_redirect.png` |
| 再等 25 秒 | ✅ 仍在播放（画面继续推进） | `.tmp/device-check/mgtv_after_redirect2.png` |
| 崩溃缓冲区 | ✅ 全程为空；`NetworkOnMainThread` / `FATAL EXCEPTION` 计数 0 | — |
| 播放页位置显示 | ✅ 修复「0/10」→ 显示 `1/11` | `.tmp/device-check/mgtv_final2.png` |

## 7. 仍未完成

| # | 事项 | 说明 |
|---|---|---|
| 1 | **地址到期刷新未做真机长测** | 逻辑是「剩余 < 60 秒主动重新解析」，但 8 小时有效期无法在验收窗口内验证 |
| 2 | **断网恢复未实测** | 有重试与重新解析路径，但没有真机断网/恢复用例 |
| 3 | **TV（Leanback）端未接入 MGTV** | 只在手机/平板端追加分组；TV 浏览页仍是内置频道 |
| 4 | **同协议 302 的请求头** | 现已由 `ScopedHeaderDataSource` 自己跟随并逐跳过滤，该限制实际已消除（原记录见下方技术债 #29，可视为已修复） |
| 5 | **多清晰度选择** | 当前固定取 `init_definition=2`（480P），未做清晰度切换 UI |

## 8. 合规与使用边界

- 签名是网页播放器 JS 里的公开参数校验（盐值 + 参数排序拼接的 MD5），本实现只做本地哈希。
- 不携带任何 Cookie，设备 UUID 由客户端本地生成，**不冒用抓包会话、不复制真实 `did`/`sign`/播放地址**。
- 播放地址有效期 8 小时且绑定签发时的设备标识，过期需重新解析。
- 仅供个人学习研究；请遵守芒果 TV 服务条款，勿用于分发或商业用途。
