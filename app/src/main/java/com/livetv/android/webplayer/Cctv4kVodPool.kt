package com.livetv.android.webplayer

import org.json.JSONArray
import kotlin.random.Random

/** CCTV-4K 轮播节目池单条官方点播专辑元数据。 */
data class Cctv4kVodItem(
    val title: String,
    val url: String,
)

/**
 * CCTV-4K 官方专辑随机轮播池解析与抽取器（纯 Kotlin，可纯 JVM 单测）。
 *
 * - 解析 `assets/cctv_4k_vod_pool.json`，仅放行符合 [CctvWebNavigationPolicy] 的官方 `tv.cctv.com` URL；
 * - 损坏 JSON 或非法条目 fail-closed 忽略，不抛异常；
 * - 池内条数 > 1 时抽取下一期自动避开当前播放 URL，条数 == 1 时直接返回该条。
 */
object Cctv4kVodPool {

    const val ASSET_FILE_NAME = "cctv_4k_vod_pool.json"

    fun parse(rawJson: String?): List<Cctv4kVodItem> {
        val text = rawJson?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()

        val seenUrls = LinkedHashSet<String>()
        val result = ArrayList<Cctv4kVodItem>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val title = obj.optString("title", "").trim()
            val url = obj.optString("url", "").trim()
            if (title.isEmpty() || url.isEmpty()) continue
            if (!CctvWebNavigationPolicy.isAllowed(url)) continue
            if (seenUrls.add(url)) {
                result += Cctv4kVodItem(title = title, url = url)
            }
        }
        return result
    }

    fun pickNext(
        items: List<Cctv4kVodItem>,
        currentUrl: String? = null,
        random: Random = Random.Default,
    ): Cctv4kVodItem? {
        if (items.isEmpty()) return null
        if (items.size == 1) return items.first()
        val current = currentUrl?.trim().orEmpty()
        val candidates = if (current.isEmpty()) {
            items
        } else {
            items.filter { it.url != current }.ifEmpty { items }
        }
        return candidates[random.nextInt(candidates.size)]
    }
}
