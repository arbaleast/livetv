package com.livetv.android.webplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [CctvWebResourceBlocker] 纯 Kotlin 单测（不依赖 Android Uri）。
 *
 * 验证「精确外围黑名单、默认放行（fail-open）」原则：
 * - 精准拦截 P2P、外围埋点、推广 iframe、非播放器配图、23 项外围 JS、重复 jQuery、9 项外围 CSS 与 EPG 接口；
 * - 核心播放链路、自举基础库、播放器图标、媒体切片与广告脚本（`cntv_Advertise.js`）绝不误拦；
 * - 空串、畸形 URL、非 http(s) 协议、大小写混合主机名与同后缀伪造域名安全处理。
 */
class CctvWebResourceBlockerTest {

    @Test
    fun `blocks p2p module and p2p runtime endpoints`() {
        assertEquals(
            CctvWebResourceBlocker.BlockResponseKind.EMPTY_JS,
            CctvWebResourceBlocker.matchUrl("https://js.player.cntv.cn/creator/hlsp2p.js"),
        )
        assertEquals(
            CctvWebResourceBlocker.BlockResponseKind.NO_CONTENT,
            CctvWebResourceBlocker.matchUrl("https://tracker-00.qvb.qcloud.com/api/tracker/v2/htbt"),
        )
        assertEquals(
            CctvWebResourceBlocker.BlockResponseKind.NO_CONTENT,
            CctvWebResourceBlocker.matchUrl("https://conf.qvb.qcloud.com/api/v1/vod/h5/test"),
        )
        assertEquals(
            CctvWebResourceBlocker.BlockResponseKind.NO_CONTENT,
            CctvWebResourceBlocker.matchUrl("https://stun-h5.qvb.qcloud.com/"),
        )
    }

    @Test
    fun `blocks promo iframes peripheral images and epg apis`() {
        assertEquals(
            CctvWebResourceBlocker.BlockResponseKind.EMPTY_HTML,
            CctvWebResourceBlocker.matchUrl("https://ydh5.yangshipin.cn/brochure/bannerH5/"),
        )
        assertEquals(
            CctvWebResourceBlocker.BlockResponseKind.EMPTY_JS,
            CctvWebResourceBlocker.matchUrl("https://ydh5.yangshipin.cn/brochure/js/app.41af6302.js"),
        )
        assertEquals(
            CctvWebResourceBlocker.BlockResponseKind.EMPTY_HTML,
            CctvWebResourceBlocker.matchUrl("https://app.cctv.com/special/yytj2023/tuijian/index.shtml"),
        )
        assertEquals(
            CctvWebResourceBlocker.BlockResponseKind.NO_CONTENT,
            CctvWebResourceBlocker.matchUrl("https://p1.img.cctvpic.com/photoAlbum/page/performance/img/2025/6/9/banner.jpg"),
        )
        assertEquals(
            CctvWebResourceBlocker.BlockResponseKind.NO_CONTENT,
            CctvWebResourceBlocker.matchUrl("https://p3.img.cctvpic.com/photoAlbum/vms/image/thumb.png"),
        )
        assertEquals(
            CctvWebResourceBlocker.BlockResponseKind.EMPTY_JSON,
            CctvWebResourceBlocker.matchUrl("https://api.cntv.cn/epg/nowepg?c=cctv1"),
        )
        assertEquals(
            CctvWebResourceBlocker.BlockResponseKind.EMPTY_JS,
            CctvWebResourceBlocker.matchUrl("https://api.cntv.cn/epg/nowepg?c=cctv1&cb=epgCb"),
        )
    }

    @Test
    fun `blocks peripheral non-playback js duplicate jquery and peripheral css`() {
        val blockedJsSamples = listOf(
            // 登录与加密套件
            "https://www.cntv.cn/Library/passport/2013passport_style/js/login_new.js",
            "https://r.img.cctvpic.com/photoAlbum/templet/common/DEPA1561603549108607/login190120home.js",
            "https://r.img.cctvpic.com/photoAlbum/templet/common/DEPA1406601454073701/md5login.js",
            "https://r.img.cctvpic.com/photoAlbum/templet/common/DEPA1406601454073701/crypto-js.min.js",
            "https://r.img.cctvpic.com/photoAlbum/templet/common/DEPA1406601454073701/jsencrypt.js",
            "https://r.img.cctvpic.com/photoAlbum/templet/common/DEPA1567070010882321/sha256.js",
            // 顶底导航与外围 UI
            "https://www.cctv.com/newcctv/2019bar/2019dlbhyjs/index.js",
            "https://www.cctv.com/newcctv/containingPages/2019whitetop/index.js",
            "https://www.cctv.com/newcctv/containingPages/bottomjs/index.js",
            "https://www.cctv.com/newcctv/containingPages/mapjs/index.js",
            "https://tv.cctv.com/common/pc_nav/index.js",
            "https://r.img.cctvpic.com/photoAlbum/templet/common/DEPA1561603549108607/top2023newindex.js",
            "https://r.img.cctvpic.com/photoAlbum/templet/common/DEPA1635928379783438/indexPC.js",
            // 分享、轮播、滚动条、收藏、二维码、埋点
            "https://p1.img.cctvpic.com/photoAlbum/templet/common/DEPA1548732472557170/swiper4.min.js",
            "https://p1.img.cctvpic.com/photoAlbum/templet/common/DEPA1504516146368634/echo.min.js",
            "https://p2.img.cctvpic.com/photoAlbum/templet/common/DEPA1561603549108607/shareindex.js",
            "https://p1.img.cctvpic.com/photoAlbum/templet/common/DEPA1486437585241505/nativeShareXY2.js",
            "https://r.img.cctvpic.com/photoAlbum/templet/js/jquery.qrcode.min.js",
            "https://js.player.cntv.cn/creator/html5player_analysis_lib.js",
            "https://js.data.cctv.com/__aplus_plugin_cctv.js,aplus_plugin_aplus_u.js",
            "https://s.yangshipin.cn/CCTVVideo/cctvh5-trace/cctvh5-trace.min.js",
            "https://o.alicdn.com/QTSDK/quicktracking-sdk/qt_web.umd.js",
        )
        blockedJsSamples.forEach { url ->
            assertEquals("应拦截外围 JS: $url", CctvWebResourceBlocker.BlockResponseKind.EMPTY_JS, CctvWebResourceBlocker.matchUrl(url))
        }

        val blockedCssSamples = listOf(
            "https://r.img.cctvpic.com/newcctv/tv/live/cctv1/m/style/style.css",
            "https://r.img.cctvpic.com/newcctv/tv/live/cctv13/m/style/style.css",
            "https://r.img.cctvpic.com/photoAlbum/templet/common/DEPA1637033749888537/style_live.css",
            "https://p1.img.cctvpic.com/photoAlbum/templet/common/DEPA1567091909687313/style_gq_20190905.css",
            "https://r.img.cctvpic.com/photoAlbum/templet/common/DEPA1635928379783438/common.css",
            "https://r.img.cctvpic.com/photoAlbum/templet/common/DEPA1635928379783438/stylePC.css",
            "https://r.img.cctvpic.com/photoAlbum/templet/common/DEPA1658908558759922/loginstyle.css",
            "https://r.img.cctvpic.com/photoAlbum/templet/common/DEPA1567091909687313/zhibo_shoucang.css",
            "https://r.img.cctvpic.com/nettv/app/special/yytj2023/livenew/style/style.css",
            "https://r.img.cctvpic.com/nettv/app/special/yytj2023/tuijian/style/style.css",
        )
        blockedCssSamples.forEach { url ->
            assertEquals("应拦截外围 CSS: $url", CctvWebResourceBlocker.BlockResponseKind.EMPTY_CSS, CctvWebResourceBlocker.matchUrl(url))
        }
    }

    @Test
    fun `never blocks core playback bootstrap icons media segments or ad script`() {
        val mustAllow = listOf(
            "https://tv.cctv.com/live/cctv1/",
            "https://tv.cctv.com/Library/H5player.json",
            "https://time.tv.cctv.com/time.php",
            "https://js.player.cntv.cn/creator/liveplayer.js",
            "https://js.player.cntv.cn/creator/liveplayer_controls.js",
            "https://js.player.cntv.cn/creator/live.worker.js",
            "https://js.player.cntv.cn/creator/swfobject.js",
            "https://vdnx.live.cntv.cn/api/v3/vdn/live",
            "https://cdn.liveplay.myqcloud.com/live/seg.ts",
            "https://player.cntv.cn/html5Player/images/icon.png",
            "https://r.img.cctvpic.com/photoAlbum/templet/js/jquery-1.7.2.min.js",
            "https://r.img.cctvpic.com/library/script/jquery-1.7.2.min.js",
            "https://r.img.cctvpic.com/photoAlbum/templet/common/DEPA1567091909687313/h5_shield.js",
            "https://js.player.cntv.cn/creator/vodplayer.js",
            "https://vdn.apps.cntv.cn/api/getHttpVideoInfo.do?pid=7b0cbb881b6640f1b2c0d7fa8b4e6215",
            "https://p5.img.cctvpic.com/photoAlbum/templet/common/DEPA1567070010882321/ptjszx_player.js",
            "https://p1.img.cctvpic.com/photoAlbum/templet/common/DEPA1559800841944426/2020_4kplay_index.js",
            "https://p1.img.cctvpic.com/photoAlbum/templet/common/DEPA1424933607444705/zepto.min.js",
            "https://p1.img.cctvpic.com/photoAlbum/templet/common/DEPA1486437585241505/configtool.js",
            "https://r.img.cctvpic.com/photoAlbum/templet/common/DEPA1486437585241505/configtoolV1.1.js",
            "https://www.cctv.com/js/cntv_Advertise.js",
        )
        mustAllow.forEach { url ->
            assertNull("核心/合规资源绝不可拦截: $url", CctvWebResourceBlocker.matchUrl(url))
        }
    }

    @Test
    fun `handles empty malformed non-http mixed-case and forged suffix urls safely`() {
        // 空串、畸形与非 http(s) 协议一律安全放行且不抛异常
        assertNull(CctvWebResourceBlocker.matchUrl(null))
        assertNull(CctvWebResourceBlocker.matchUrl(""))
        assertNull(CctvWebResourceBlocker.matchUrl("   "))
        assertNull(CctvWebResourceBlocker.matchUrl("not a valid :: url"))
        assertNull(CctvWebResourceBlocker.matchUrl("about:blank"))
        assertNull(CctvWebResourceBlocker.matchUrl("data:text/javascript,alert(1)"))
        assertNull(CctvWebResourceBlocker.matchUrl("blob:https://js.player.cntv.cn/creator/hlsp2p.js"))
        assertNull(CctvWebResourceBlocker.match(null, null, null))
        assertNull(CctvWebResourceBlocker.match("", "/creator/hlsp2p.js", null))

        // 大小写混合主机名与路径正常命中
        assertNotNull(CctvWebResourceBlocker.matchUrl("HTTPS://JS.PLAYER.CNTV.CN/Creator/HlsP2P.JS"))
        assertNotNull(CctvWebResourceBlocker.matchUrl("https://API.CNTV.CN/EPG/NowEpg?c=cctv1"))

        // 同后缀伪造域名严禁误拦
        assertNull(CctvWebResourceBlocker.matchUrl("https://evilqvb.qcloud.com/api/test"))
        assertNull(CctvWebResourceBlocker.matchUrl("https://evil-img.cctvpic.com/photoAlbum/vms/a.png"))
        assertNull(CctvWebResourceBlocker.matchUrl("https://fakeapi.cntv.cn/epg/nowepg"))
        assertNull(CctvWebResourceBlocker.matchUrl("https://my-ydh5.yangshipin.cn/brochure/bannerH5/"))
    }
}
