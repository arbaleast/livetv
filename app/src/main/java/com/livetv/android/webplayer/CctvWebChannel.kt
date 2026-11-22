package com.livetv.android.webplayer

/** CCTV WebView 频道播放模式分流（3 类）。 */
enum class CctvWebPlayMode {
    /** 14 路移动直连直播：Mobile UA + `https://tv.cctv.com/live/{slug}/m/`。 */
    MOBILE_LIVE,

    /** 3 路桌面直播（CCTV-3/6/8）：Desktop Chrome UA + `https://tv.cctv.com/live/{slug}/`，规避 `/m/` 强跳央视频。 */
    DESKTOP_LIVE,

    /** 1 路 4K 专辑随机轮播（CCTV-4K）：Mobile UA + 从 `Cctv4kVodPool` 随机抽取官方点播页。 */
    VOD_4K_POOL,
}

/**
 * 目录里的 `cctv:` 频道 → 官方播放页与 UA 分流策略（纯 Kotlin，可纯 JVM 单测）。
 *
 * - 支持 18 路（CCTV 1–17 直播 + CCTV-4K 轮播），明确拒绝已移除的 `cctv8k`；
 * - 真实官网 slug 映射：`cctv9 -> cctvjilu`、`cctv14 -> cctvchild`；
 * - 14 路移动直连 `/m/` 省去一次 PC→移动跳转；3/6/8 走桌面 UA 直连 PC 直播页规避 `/m/` 导流 `m.yangshipin.cn`。
 */
object CctvWebChannel {

    const val CHANNEL_PREFIX = "cctv:"
    const val KEY_CCTV_4K = "cctv4k"

    /** 直播频道黑幕超时兜底时长（ms）。 */
    const val LIVE_FOCUS_TIMEOUT_MS = 7_000L

    /** 4K 点播页（VIDE*.shtml）黑幕超时兜底时长（ms）：覆盖 500ms 延迟自举与冷启点播切片首帧加载。 */
    const val VOD_FOCUS_TIMEOUT_MS = 15_000L

    private const val BASE_URL = "https://tv.cctv.com/live/"

    private val DESKTOP_LIVE_KEYS: Set<String> = setOf("cctv3", "cctv6", "cctv8")

    private val SLUG_OVERRIDES: Map<String, String> = mapOf(
        "cctv9" to "cctvjilu",
        "cctv14" to "cctvchild",
    )

    /** 已验证可用的 18 路频道 key（17 路直播 + 1 路 4K 轮播，不含已移除的 cctv8k）。 */
    private val SUPPORTED_KEYS: Set<String> = LinkedHashSet<String>().apply {
        for (i in 1..17) add("cctv$i")
        add(KEY_CCTV_4K)
    }

    /** 把 `cctv:cctv13` 或裸 key `cctv13` 规范成受支持的 key；不支持/非法返回 `null`。 */
    fun keyOf(channelId: String?): String? {
        val id = channelId?.trim().orEmpty()
        if (id.isEmpty()) return null
        val key = id.lowercase().removePrefix(CHANNEL_PREFIX)
        return key.takeIf { it in SUPPORTED_KEYS }
    }

    fun isSupported(channelId: String?): Boolean = keyOf(channelId) != null

    fun playMode(channelId: String?): CctvWebPlayMode? = when (keyOf(channelId)) {
        null -> null
        KEY_CCTV_4K -> CctvWebPlayMode.VOD_4K_POOL
        in DESKTOP_LIVE_KEYS -> CctvWebPlayMode.DESKTOP_LIVE
        else -> CctvWebPlayMode.MOBILE_LIVE
    }

    fun requiresDesktopUserAgent(channelId: String?): Boolean =
        playMode(channelId) == CctvWebPlayMode.DESKTOP_LIVE

    fun isVodPoolChannel(channelId: String?): Boolean =
        playMode(channelId) == CctvWebPlayMode.VOD_4K_POOL

    /** 按频道模式返回首帧对齐黑幕的最大超时兜底时长（直播 7s，4K 点播 12s）。 */
    fun focusTimeoutMs(channelId: String?): Long =
        if (isVodPoolChannel(channelId)) VOD_FOCUS_TIMEOUT_MS else LIVE_FOCUS_TIMEOUT_MS

    /** 直播频道 → 官方直播页 URL；4K 轮播台（URL 来自节目池）或不支持频道返回 `null`。 */
    fun liveUrl(channelId: String?): String? {
        val key = keyOf(channelId) ?: return null
        val slug = SLUG_OVERRIDES[key] ?: key
        return when (playMode(key)) {
            CctvWebPlayMode.MOBILE_LIVE -> "$BASE_URL$slug/m/"
            CctvWebPlayMode.DESKTOP_LIVE -> "$BASE_URL$slug/"
            CctvWebPlayMode.VOD_4K_POOL, null -> null
        }
    }
}
