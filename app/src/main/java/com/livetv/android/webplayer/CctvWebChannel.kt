package com.livetv.android.webplayer

/**
 * 目录里的 `cctv:` 频道 → 官方直播页（纯 Kotlin，可单测）。
 *
 * 实测（2026-11-21）`https://tv.cctv.com/live/cctv1/` … `cctv17/` 均返回 200；
 * `cctv4k` / `cctv8k` 页面 404，因此只放行 1–17，其余返回 `null`（调用方给"暂不支持"）。
 */
object CctvWebChannel {

    const val CHANNEL_PREFIX = "cctv:"

    private const val BASE_URL = "https://tv.cctv.com/live/"

    /** 已验证存在直播页的频道 key。 */
    private val SUPPORTED_KEYS: Set<String> = (1..17).mapTo(LinkedHashSet()) { "cctv$it" }

    /** 把 `cctv:cctv13` 或裸 key `cctv13` 规范成受支持的 key；不支持/非法返回 `null`。 */
    fun keyOf(channelId: String?): String? {
        val id = channelId?.trim().orEmpty()
        if (id.isEmpty()) return null
        val key = id.lowercase().removePrefix(CHANNEL_PREFIX)
        return key.takeIf { it in SUPPORTED_KEYS }
    }

    /** 频道 → 官方直播页 URL；不支持的频道返回 `null`。 */
    fun liveUrl(channelId: String?): String? = keyOf(channelId)?.let { "$BASE_URL$it/" }
}
