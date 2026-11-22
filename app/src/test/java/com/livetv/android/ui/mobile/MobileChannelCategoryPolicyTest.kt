package com.livetv.android.ui.mobile

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MobileChannelCategoryPolicy] 纯 JVM 单元测试。
 *
 * 覆盖：
 * 1. 央视多细分分组归拢为「央视」，芒果/卫视多细分分组归拢为「卫视·芒果」；
 * 2. 未命中上述两类的自定义 M3U / 其他频道落入「其他」；
 * 3. 「其他」或任意大类为空时自动隐藏，不渲染空标签；
 * 4. 固定大类展示顺序（「央视」→「卫视·芒果」→「其他」）、按频道 id 去重及幂等性。
 */
class MobileChannelCategoryPolicyTest {

    private fun ch(id: String, name: String) =
        Channel(id = id, name = name, description = "", streamUrl = "")

    private fun cat(id: String, name: String, vararg channels: Channel) =
        ChannelCategory(id = id, name = name, channels = channels.toList())

    @Test
    fun `merges fragmented cctv and mgtv categories into two clean buckets and hides empty Other`() {
        val raw = listOf(
            cat("cctv:general", "综合·新闻", ch("cctv:cctv1", "CCTV-1 综合"), ch("cctv:cctv13", "CCTV-13 新闻")),
            cat("cctv:culture", "文艺·国际", ch("cctv:cctv3", "CCTV-3 综艺")),
            cat("cctv:sports", "体育·少儿", ch("cctv:cctv5", "CCTV-5 体育")),
            cat("cctv:uhd", "超高清", ch("cctv:cctv4k", "CCTV-4K 超高清")),
            cat("mgtv:2100002", "卫视台", ch("mgtv:287", "金鹰卡通"), ch("mgtv:261", "金鹰纪实")),
            cat("mgtv:2100003", "地方台", ch("mgtv:280", "湖南经视")),
            cat("mgtv:2100004", "特色频道", ch("mgtv:484", "快乐垂钓")),
            cat("mgtv:hot", "热门", ch("mgtv:344", "湖南娱乐")),
        )

        val merged = MobileChannelCategoryPolicy.merge(raw)

        assertEquals(2, merged.size)
        assertEquals(MobileChannelCategoryPolicy.ID_CCTV, merged[0].id)
        assertEquals(MobileChannelCategoryPolicy.NAME_CCTV, merged[0].name)
        assertEquals(
            listOf("cctv:cctv1", "cctv:cctv13", "cctv:cctv3", "cctv:cctv5", "cctv:cctv4k"),
            merged[0].channels.map { it.id },
        )

        assertEquals(MobileChannelCategoryPolicy.ID_SATELLITE, merged[1].id)
        assertEquals(MobileChannelCategoryPolicy.NAME_SATELLITE, merged[1].name)
        assertEquals(
            listOf("mgtv:287", "mgtv:261", "mgtv:280", "mgtv:484", "mgtv:344"),
            merged[1].channels.map { it.id },
        )
    }

    @Test
    fun `routes custom provincial satellite cctv and unknown channels into expected buckets in fixed order`() {
        val raw = listOf(
            cat("custom:local", "自定义地方台", ch("custom:bj1", "北京新闻")),
            cat("custom:sat", "省级卫视", ch("custom:zjws", "浙江卫视"), ch("custom:jsws", "江苏卫视")),
            cat("custom:cctv", "央视频道", ch("custom:c1", "CCTV-1 综合"), ch("custom:cgtn", "CGTN 英语")),
            cat("custom:other", "音乐影视", ch("custom:m1", "经典电影台")),
        )

        val merged = MobileChannelCategoryPolicy.merge(raw)

        assertEquals(
            listOf(
                MobileChannelCategoryPolicy.NAME_CCTV,
                MobileChannelCategoryPolicy.NAME_SATELLITE,
                MobileChannelCategoryPolicy.NAME_OTHER,
            ),
            merged.map { it.name },
        )
        assertEquals(listOf("custom:c1", "custom:cgtn"), merged[0].channels.map { it.id })
        assertEquals(listOf("custom:zjws", "custom:jsws"), merged[1].channels.map { it.id })
        assertEquals(listOf("custom:bj1", "custom:m1"), merged[2].channels.map { it.id })
    }

    @Test
    fun `empty categories produce empty merged list and never emit empty buckets`() {
        val raw = listOf(
            cat("empty1", "空分组1"),
            cat("empty2", "空分组2"),
        )
        assertTrue(MobileChannelCategoryPolicy.merge(raw).isEmpty())
    }

    @Test
    fun `merge deduplicates channels by id and is idempotent`() {
        val raw = listOf(
            cat("cctv:a", "综合·新闻", ch("cctv:cctv1", "CCTV-1 综合")),
            cat("cctv:b", "热门央视", ch("cctv:cctv1", "CCTV-1 综合"), ch("cctv:cctv2", "CCTV-2 财经")),
            cat("custom:x", "其他", ch("custom:u1", "城市频道")),
        )

        val once = MobileChannelCategoryPolicy.merge(raw)
        val twice = MobileChannelCategoryPolicy.merge(once)

        assertEquals(once, twice)
        assertEquals(2, once[0].channels.size)
        assertEquals(1, once[1].channels.size)
    }
}
