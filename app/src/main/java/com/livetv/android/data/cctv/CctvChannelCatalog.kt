package com.livetv.android.data.cctv

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.json.JSONObject

/**
 * CCTV 频道目录（P1，**仅目录**）。
 *
 * 数据来源是 `assets/cctv_channels.json` 的静态元数据（id / 名称 / 分类 / Logo / EPG ID），
 * 解析为领域层 [ChannelCategory]：
 * - 分类 id 统一加 `cctv:` 命名空间前缀（如 `cctv:general`）；
 * - 频道 id 统一为 `cctv:<cctvId>`（如 `cctv:cctv1`）；
 * - **`streamUrl` 恒为空字符串**——播放地址由 P2 的 VDN 解析提供，绝不写进频道数据。
 *
 * 解析失败/字段缺失一律 fail-closed 返回空列表，不抛异常。
 */
class CctvChannelCatalog(
    private val catalogLoader: () -> String?,
) {

    fun load(): List<ChannelCategory> {
        val raw = runCatching { catalogLoader() }.getOrNull().orEmpty()
        return parse(raw)
    }

    private fun parse(raw: String): List<ChannelCategory> {
        if (raw.isBlank()) return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("categories") ?: return emptyList()

        val categories = mutableListOf<ChannelCategory>()
        for (i in 0 until array.length()) {
            val category = array.optJSONObject(i) ?: continue
            val categoryId = category.optString("id", "").trim()
            val categoryName = category.optString("name", "").trim()
            if (categoryId.isEmpty() || categoryName.isEmpty()) continue

            val channelArray = category.optJSONArray("channels") ?: continue
            val channels = mutableListOf<Channel>()
            for (j in 0 until channelArray.length()) {
                val item = channelArray.optJSONObject(j) ?: continue
                val channelId = item.optString("id", "").trim()
                val channelName = item.optString("name", "").trim()
                if (channelId.isEmpty() || channelName.isEmpty()) continue
                channels += Channel(
                    id = "$CHANNEL_PREFIX$channelId",
                    name = channelName,
                    description = "",
                    streamUrl = "",
                    logo = item.optString("logo", "").trim(),
                    epgId = item.optString("epgId", "").trim().ifEmpty { channelId },
                    enabled = true,
                )
            }
            if (channels.isEmpty()) continue
            categories += ChannelCategory(
                id = "$CATEGORY_PREFIX$categoryId",
                name = categoryName,
                channels = channels.distinctBy { it.id },
            )
        }
        return categories
    }

    companion object {
        /** 频道 id 前缀（与 MGTV / 静态 IPTV 隔离）。 */
        const val CHANNEL_PREFIX = "cctv:"

        /** 分类 id 前缀（命名空间隔离，避免跨来源分类 id 冲突）。 */
        const val CATEGORY_PREFIX = "cctv:"
    }
}
