package com.livetv.android.data.mgtv

/**
 * MGTV 频道目录条目（对应 `getLiveAssetCategoryList` 响应的 `channels[]`）。
 */
data class MgtvChannel(
    val cameraId: String,
    val name: String,
    val logoUrl: String = "",
    val channelNumber: String = "",
    val currentProgram: String = "",
)

/**
 * MGTV 频道分类（对应 `getLiveAssetCategoryList` 响应的 `data.category[]`）。
 */
data class MgtvCategory(
    val id: String,
    val name: String,
    val channels: List<MgtvChannel>,
)

/**
 * MGTV 播放源候选条目（对应 `/v1/live/source` 响应的 `data.sources[]`）。
 *
 * `isP2p` 与 `hdcp` 预留了未来 P2P / DRM 扩展边界，但第一阶段白盒筛选严格仅放行
 * `format == ".m3u8"`、`isP2p == 0`、`hdcp == 0` 且合法 `https://` 的标准 HLS 源。
 */
data class MgtvSourceItem(
    val url: String,
    val format: String,
    val videoFormat: String = "",
    val audioFormat: String = "",
    val definition: String = "",
    val name: String = "",
    val urlExpireDurationSec: Long = 0L,
    val sid: String = "",
    val isOtherCdn: Int = 0,
    /** 单源级 `isp2p` 覆写；为空时继承响应体 `data.isp2p`。 */
    val isP2p: Int? = null,
    /** 单源级 `hdcp` 覆写；为空时继承响应体 `data.hdcp`。 */
    val hdcp: Int? = null,
    /** 单源级 DRM 标记（显式非 0/非 false 时严格拦截）。 */
    val isDrm: Boolean = false,
    /** 服务端下发的换节点凭证：重解析时带回，服务端会调度到另一个 CDN。 */
    val npuk: String = "",
)

/**
 * MGTV `/v1/live/source` 响应模型。
 */
data class MgtvSourceResponse(
    val code: String,
    val message: String,
    val cameraId: String,
    val cameraName: String,
    val isP2p: Int,
    val hdcp: Int,
    val serverTimeSec: Long,
    val sources: List<MgtvSourceItem>,
    /** 响应级 DRM 标记（显式非 0/非 false 时严格拦截）。 */
    val isDrm: Boolean = false,
)
