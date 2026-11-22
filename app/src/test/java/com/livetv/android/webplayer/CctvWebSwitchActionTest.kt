package com.livetv.android.webplayer

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CctvWebSwitchPolicy] 纯 JVM 单元测试：
 * 覆盖同频道 NoOp、受支持 CCTV 同页重载、不支持的 CCTV 提示、以及跨源销毁切回 PlayerActivity。
 */
class CctvWebSwitchActionTest {

    private fun cctvChannel(id: String, name: String) = Channel(
        id = id,
        name = name,
        description = "",
        streamUrl = "",
    )

    @Test
    fun `switching to the same cctv channel returns NoOp`() {
        val target = cctvChannel("cctv:cctv1", "CCTV-1 综合")

        assertEquals(
            CctvWebSwitchAction.NoOp,
            CctvWebSwitchPolicy.decide(currentChannelId = "cctv:cctv1", targetChannel = target),
        )
        assertEquals(
            CctvWebSwitchAction.NoOp,
            CctvWebSwitchPolicy.decide(currentChannelId = "cctv1", targetChannel = target),
        )
    }

    @Test
    fun `switching to another supported cctv channel returns ReloadInPlace`() {
        val target = cctvChannel("cctv:cctv13", "CCTV-13 新闻")

        val action = CctvWebSwitchPolicy.decide(
            currentChannelId = "cctv:cctv1",
            targetChannel = target,
        )

        assertEquals(
            CctvWebSwitchAction.ReloadInPlace(
                channelId = "cctv:cctv13",
                channelName = "CCTV-13 新闻",
                url = "https://tv.cctv.com/live/cctv13/",
            ),
            action,
        )
    }

    @Test
    fun `switching to unsupported cctv channel returns UnsupportedToast`() {
        listOf(
            cctvChannel("cctv:cctv4k", "CCTV-4K 超高清"),
            cctvChannel("cctv:cctv8k", "CCTV-8K 超高清"),
            cctvChannel("cctv:cctv18", "CCTV-18"),
            cctvChannel("cctv4k", "CCTV-4K 超高清"),
        ).forEach { target ->
            assertEquals(
                "应拒绝不支持的频道 ${target.id}",
                CctvWebSwitchAction.UnsupportedToast,
                CctvWebSwitchPolicy.decide(currentChannelId = "cctv:cctv1", targetChannel = target),
            )
        }
    }

    @Test
    fun `switching to mgtv or iptv channel returns SwitchToNativePlayer`() {
        val mgtv = Channel(id = "mgtv:287", name = "金鹰卡通", description = "", streamUrl = "")
        val iptv = Channel(
            id = "iptv:1",
            name = "测试卫视",
            description = "",
            streamUrl = "https://example.invalid/live.m3u8",
        )

        assertEquals(
            CctvWebSwitchAction.SwitchToNativePlayer(mgtv),
            CctvWebSwitchPolicy.decide(currentChannelId = "cctv:cctv1", targetChannel = mgtv),
        )
        assertEquals(
            CctvWebSwitchAction.SwitchToNativePlayer(iptv),
            CctvWebSwitchPolicy.decide(currentChannelId = "cctv:cctv1", targetChannel = iptv),
        )
    }

    @Test
    fun `empty catalog store falls back to supported cctv 1 to 17 list`() {
        val fallback = CctvWebSwitchPolicy.resolveCategories(emptyList())
        assertEquals(1, fallback.size)
        val channels = fallback[0].channels
        assertEquals(17, channels.size)
        assertEquals("cctv:cctv1", channels.first().id)
        assertEquals("cctv:cctv17", channels.last().id)

        val existing = listOf(
            ChannelCategory(
                id = "cctv:general",
                name = "综合·新闻",
                channels = listOf(cctvChannel("cctv:cctv1", "CCTV-1 综合")),
            ),
        )
        assertEquals(existing, CctvWebSwitchPolicy.resolveCategories(existing))
        assertTrue(
            CctvWebSwitchPolicy.resolveChannelName("cctv:cctv1", existing) == "CCTV-1 综合",
        )
        assertEquals(
            "CCTV-13",
            CctvWebSwitchPolicy.resolveChannelName("cctv:cctv13", emptyList(), fallbackName = "  "),
        )
    }
}
