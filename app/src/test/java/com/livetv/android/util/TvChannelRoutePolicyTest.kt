package com.livetv.android.util

import com.livetv.android.domain.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [TvChannelRoutePolicy] 纯 JVM 单元测试：
 * 覆盖 TV 端拦截所有 `cctv:` 频道、保留 MGTV 与普通 IPTV 原生播放路由，以及 TV 端零 WebView 守卫。
 */
class TvChannelRoutePolicyTest {

    @Test
    fun `tv mode intercepts all cctv channels as unsupported`() {
        listOf(
            Channel(id = "cctv:cctv1", name = "CCTV-1 综合", description = "", streamUrl = ""),
            Channel(id = "cctv:cctv13", name = "CCTV-13 新闻", description = "", streamUrl = ""),
            Channel(id = "cctv:cctv17", name = "CCTV-17 农业农村", description = "", streamUrl = ""),
            Channel(id = "cctv:cctv4k", name = "CCTV-4K 超高清", description = "", streamUrl = ""),
            Channel(id = "cctv:cctv8k", name = "CCTV-8K 超高清", description = "", streamUrl = ""),
            Channel(id = "  CCTV:CCTV5  ", name = "CCTV-5 体育", description = "", streamUrl = ""),
        ).forEach { channel ->
            assertTrue("应识别为 CCTV 频道: ${channel.id}", TvChannelRoutePolicy.isCctvChannel(channel.id))
            assertEquals(
                "TV 端应拦截 ${channel.id}",
                TvChannelRouteAction.UnsupportedCctv,
                TvChannelRoutePolicy.decide(channel),
            )
        }
    }

    @Test
    fun `tv mode routes mgtv dynamic channels to native player`() {
        val mgtv = Channel(id = "mgtv:287", name = "金鹰卡通", description = "", streamUrl = "")

        assertFalse(TvChannelRoutePolicy.isCctvChannel(mgtv.id))
        assertEquals(TvChannelRouteAction.PlayNative, TvChannelRoutePolicy.decide(mgtv))
    }

    @Test
    fun `tv mode routes static iptv channels with streamUrl to native player`() {
        val iptv = Channel(
            id = "iptv:1",
            name = "测试频道",
            description = "",
            streamUrl = "https://example.invalid/live.m3u8",
        )

        assertFalse(TvChannelRoutePolicy.isCctvChannel(iptv.id))
        assertEquals(TvChannelRouteAction.PlayNative, TvChannelRoutePolicy.decide(iptv))
    }

    @Test
    fun `tv mode returns NoStreamUrl for non-cctv non-mgtv channels without url`() {
        val empty = Channel(id = "custom:1", name = "空源频道", description = "", streamUrl = "   ")

        assertFalse(TvChannelRoutePolicy.isCctvChannel(empty.id))
        assertEquals(TvChannelRouteAction.NoStreamUrl, TvChannelRoutePolicy.decide(empty))
    }

    @Test
    fun `tv browse fragment never imports webplayer or webview`() {
        val tvFragment = File("src/main/java/com/livetv/android/ui/MainBrowseFragment.kt")
        assertTrue(tvFragment.isFile)
        val text = tvFragment.readText(Charsets.UTF_8)
        assertFalse("TV 主页严禁引入 webplayer 包", text.contains("com.livetv.android.webplayer"))
        assertFalse("TV 主页严禁引入 android.webkit.WebView", text.contains("android.webkit.WebView"))
    }
}
