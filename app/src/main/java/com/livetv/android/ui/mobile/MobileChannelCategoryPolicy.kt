package com.livetv.android.ui.mobile

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory

/**
 * 手机端展示层频道分类合并策略（纯 Kotlin，无 Android 依赖，绝不触碰 TV 端与底层数据源）。
 *
 * 将碎片化的多源细分分组统一收口为固定顺序的精简大类：
 * 1. 「央视」（[ID_CCTV]）：归拢全部 `cctv:` 及央视/CCTV/CGTN 频道；
 * 2. 「卫视·芒果」（[ID_SATELLITE]）：归拢全部省级卫视及 `mgtv:` / 芒果 / 湖南 / 金鹰频道；
 * 3. 「其他」（[ID_OTHER]）：兜底未命中上述两类的自定义 M3U / 其他地方台；
 *    任意大类下频道数为 0 时自动隐藏，绝不渲染空分组。
 */
object MobileChannelCategoryPolicy {

    const val ID_CCTV = "mobile:cctv"
    const val NAME_CCTV = "央视"

    const val ID_SATELLITE = "mobile:satellite"
    const val NAME_SATELLITE = "卫视·芒果"

    const val ID_OTHER = "mobile:other"
    const val NAME_OTHER = "其他"

    private enum class Bucket {
        CCTV,
        SATELLITE,
        OTHER,
    }

    /**
     * 按固定展示顺序合并原始分类列表，并按频道 id 去重、自动隐藏空大类。
     */
    fun merge(rawCategories: List<ChannelCategory>): List<ChannelCategory> {
        if (rawCategories.isEmpty()) return emptyList()

        val cctvChannels = mutableListOf<Channel>()
        val satelliteChannels = mutableListOf<Channel>()
        val otherChannels = mutableListOf<Channel>()
        val seenIds = HashSet<String>()

        for (category in rawCategories) {
            if (category.id == MobileChannelList.FAVORITE_CATEGORY_ID) continue
            for (channel in category.channels) {
                if (!seenIds.add(channel.id)) continue
                when (classify(category, channel)) {
                    Bucket.CCTV -> cctvChannels += channel
                    Bucket.SATELLITE -> satelliteChannels += channel
                    Bucket.OTHER -> otherChannels += channel
                }
            }
        }

        val result = ArrayList<ChannelCategory>(3)
        if (cctvChannels.isNotEmpty()) {
            result += ChannelCategory(id = ID_CCTV, name = NAME_CCTV, channels = cctvChannels)
        }
        if (satelliteChannels.isNotEmpty()) {
            result += ChannelCategory(id = ID_SATELLITE, name = NAME_SATELLITE, channels = satelliteChannels)
        }
        if (otherChannels.isNotEmpty()) {
            result += ChannelCategory(id = ID_OTHER, name = NAME_OTHER, channels = otherChannels)
        }
        return result
    }

    private fun classify(category: ChannelCategory, channel: Channel): Bucket {
        val chId = channel.id.trim().lowercase()
        val catId = category.id.trim().lowercase()
        val chName = channel.name.trim()
        val catName = category.name.trim()

        if (isCctv(chId, catId, chName, catName)) return Bucket.CCTV
        if (isSatelliteOrMango(chId, catId, chName, catName)) return Bucket.SATELLITE
        return Bucket.OTHER
    }

    private fun isCctv(chId: String, catId: String, chName: String, catName: String): Boolean =
        chId.startsWith("cctv:") ||
            catId.startsWith("cctv:") ||
            catId == ID_CCTV ||
            chName.contains("cctv", ignoreCase = true) ||
            chName.contains("cgtn", ignoreCase = true) ||
            chName.contains("央视") ||
            chName.contains("中央") ||
            catName.contains("央视") ||
            catName.contains("cctv", ignoreCase = true)

    private fun isSatelliteOrMango(chId: String, catId: String, chName: String, catName: String): Boolean =
        chId.startsWith("mgtv:") ||
            catId.startsWith("mgtv:") ||
            catId == ID_SATELLITE ||
            chName.contains("卫视") ||
            chName.contains("芒果") ||
            chName.contains("湖南") ||
            chName.contains("金鹰") ||
            catName.contains("卫视") ||
            catName.contains("芒果")
}
