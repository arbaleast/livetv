package com.livetv.android.domain.stream

/**
 * 标准化后的可播放流会话模型。
 *
 * `PlayerManager` 只消费该通用模型，不感知具体来源（普通 IPTV / M3U / MGTV 等）。
 *
 * @property url 实际播放地址。
 * @property headers 本次播放需附加的 HTTP 请求头。
 * @property expiresAtMs 动态地址过期时间戳（Unix 毫秒）；`null` 表示静态地址不过期。
 * @property mimeType 可选显式 MIME 类型（如无 `.m3u8` 后缀的 HLS 流显式传 `application/x-mpegURL`）。
 * @property allowedHeaderHosts 允许携带 [headers] 的 Host 白名单后缀集合；空集合表示不限制（兼容普通 IPTV）。
 * @property definitionLabel 可选清晰度标签（如 `480P`、`720P`）。
 */
data class ResolvedStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val expiresAtMs: Long? = null,
    val mimeType: String? = null,
    val allowedHeaderHosts: Set<String> = emptySet(),
    val definitionLabel: String = "",
) {
    /**
     * 判断动态地址是否已过期或将在 [refreshLeadMs] 毫秒内过期（默认提前 60 秒主动刷新）。
     */
    fun isExpiringSoon(
        nowMs: Long,
        refreshLeadMs: Long = DEFAULT_REFRESH_LEAD_MS,
    ): Boolean {
        val exp = expiresAtMs ?: return false
        return nowMs + refreshLeadMs >= exp
    }

    /** 判断动态地址是否已彻底过期。 */
    fun isExpired(nowMs: Long): Boolean {
        val exp = expiresAtMs ?: return false
        return nowMs >= exp
    }

    companion object {
        /** 到期前主动刷新提前量：60 秒。 */
        const val DEFAULT_REFRESH_LEAD_MS = 60_000L
    }
}
