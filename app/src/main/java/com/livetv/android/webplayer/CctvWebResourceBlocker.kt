package com.livetv.android.webplayer

import java.net.URI

/**
 * CCTV 直播页子资源拦截判定（纯 Kotlin，不依赖 Android Uri，便于独立单测）。
 *
 * 严格遵循「精确外围黑名单、默认放行（fail-open）」与零核心侵入边界：
 * - **绝不拦截**官方直播主文档（`tv.cctv.com/live/`）、官方广告脚本、
 *   核心播放器 4 脚本（`liveplayer.js`、`liveplayer_controls.js`、`live.worker.js`、`swfobject.js`、`.wasm`）、
 *   基础自举库（`zepto.min.js`、`configtool*.js`、主路径 `jquery-1.7.2.min.js`）、
 *   时间/配置/鉴权接口（`H5player.json`、`time.tv.cctv.com`、`vdnx.live.cntv.cn`）、
 *   播放器自身图标（`player.cntv.cn`）与视频媒体 CDN 切片。
 * - 仅拦截已观测确认的 P2P 代理模块、埋点统计、推广 iframe、非播放器配图、EPG 列表与外围页面 JS/CSS。
 */
internal object CctvWebResourceBlocker {

    enum class BlockResponseKind(val mimeType: String, val statusCode: Int, val body: ByteArray) {
        EMPTY_JS("application/javascript", 200, ByteArray(0)),
        EMPTY_CSS("text/css", 200, ByteArray(0)),
        EMPTY_HTML("text/html", 200, ByteArray(0)),
        EMPTY_JSON("application/json", 200, "{}".toByteArray(Charsets.UTF_8)),
        NO_CONTENT("text/plain", 204, ByteArray(0)),
    }

    /** CT 扫描实证的外围非播放 JS（登录/加密/导航/分享/轮播/滚动条/二维码/重复 jQuery）。 */
    private val PERIPHERAL_CCTV_JS_PATHS = setOf(
        "/library/passport/2013passport_style/js/login_new.js",
        "/newcctv/2019bar/2019dlbhyjs/index.js",
        "/newcctv/containingpages/2019whitetop/index.js",
        "/newcctv/containingpages/bottomjs/index.js",
        "/newcctv/containingpages/mapjs/index.js",
        "/common/pc_nav/index.js",
    )

    private val PERIPHERAL_IMG_JS_PATHS = setOf(
        "/photoalbum/templet/common/depa1561603549108607/login190120home.js",
        "/photoalbum/templet/common/depa1406601454073701/md5login.js",
        "/photoalbum/templet/common/depa1406601454073701/crypto-js.min.js",
        "/photoalbum/templet/common/depa1406601454073701/jsencrypt.js",
        "/photoalbum/templet/common/depa1567070010882321/sha256.js",
        "/photoalbum/templet/common/depa1561603549108607/top2023newindex.js",
        "/photoalbum/templet/common/depa1635928379783438/indexpc.js",
        "/photoalbum/templet/common/depa1567091909687313/zhibo_shoucang.js",
        "/photoalbum/templet/common/depa1548732472557170/swiper4.min.js",
        "/photoalbum/templet/common/depa1504516146368634/echo.min.js",
        "/photoalbum/templet/common/depa1567091909687313/cctvnew-jquery.tinyscrollbar.js",
        "/photoalbum/templet/common/depa1561603549108607/jquery.tinyscrollbar.js",
        "/photoalbum/templet/js/jquery.qrcode.min.js",
        "/photoalbum/templet/common/depa1561603549108607/shareindex.js",
        "/photoalbum/templet/common/depa1486437585241505/nativesharexy2.js",
        "/photoalbum/templet/common/depa1437029649871629/pubtoolmethod.js",
        // 注意：CCTV-3/6/8 PC 直播页依赖 /library/script/jquery-1.7.2.min.js 与 h5_shield.js 自举播放器，严禁拦截
        // 上述导航/登录脚本通过 document.write 动态注入的子脚本
        "/photoalbum/templet/common/depa1658908558759922/mylogin.js",
        "/photoalbum/templet/common/depa1628231077516717/daohang211126.js",
        "/photoalbum/templet/common/depa1628231077516717/gray20221130.js",
    )

    /** CT 扫描实证的外围页面/PC/登录/推荐 CSS（全部 9 个）。 */
    private val PERIPHERAL_IMG_CSS_PATHS = setOf(
        "/photoalbum/templet/common/depa1637033749888537/style_live.css",
        "/photoalbum/templet/common/depa1567091909687313/style_gq_20190905.css",
        "/photoalbum/templet/common/depa1635928379783438/common.css",
        "/photoalbum/templet/common/depa1635928379783438/stylepc.css",
        "/photoalbum/templet/common/depa1658908558759922/loginstyle.css",
        "/photoalbum/templet/common/depa1567091909687313/zhibo_shoucang.css",
        "/nettv/app/special/yytj2023/livenew/style/style.css",
        "/nettv/app/special/yytj2023/tuijian/style/style.css",
    )

    /**
     * 判断给定完整 URL 是否应被拦截；空串、畸形或非 http(s) URL 安全放行（返回 `null`）。
     */
    fun matchUrl(url: String?): BlockResponseKind? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parsed = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return null
        return match(parsed.host, parsed.rawPath, parsed.rawQuery)
    }

    /**
     * 判断给定子资源请求是否应被拦截；返回 `null` 表示放行。
     */
    fun match(host: String?, path: String?, query: String?): BlockResponseKind? {
        val h = host?.trim()?.lowercase().orEmpty()
        val p = path?.trim()?.lowercase().orEmpty()
        val q = query?.trim()?.lowercase().orEmpty()
        if (h.isEmpty()) return null

        // 1. P2P 模块：阻断后官方播放器平滑回退纯 CDN 拉流，消除 WebRTC/STUN 报错与开销
        if (p.endsWith("/hlsp2p.js")) return BlockResponseKind.EMPTY_JS
        if (h == "qvb.qcloud.com" || h.endsWith(".qvb.qcloud.com")) return BlockResponseKind.NO_CONTENT

        // 2. 埋点、统计与分享/唤端 SDK（注意：严禁拦截官方广告脚本）
        if (p.endsWith("/html5player_analysis_lib.js") || isPeripheralSocialOrNavScript(p)) {
            return BlockResponseKind.EMPTY_JS
        }
        if (h == "js.data.cctv.com" ||
            h == "s.yangshipin.cn" ||
            h == "o.alicdn.com" ||
            h == "res.wx.qq.com" ||
            h == "res2.wx.qq.com" ||
            h == "g.alicdn.com"
        ) {
            return BlockResponseKind.EMPTY_JS
        }
        if (h == "mediadata.xuexi.cn") {
            return if (p.endsWith(".js")) BlockResponseKind.EMPTY_JS else BlockResponseKind.NO_CONTENT
        }
        if (h == "p.data.cctv.com" ||
            h == "btrace.yangshipin.cn" ||
            h == "api4.ipify.org"
        ) {
            return BlockResponseKind.NO_CONTENT
        }

        // 3. 推广与推荐子页面 iframe（其内部还会加载 Vue 与第三方统计 SDK）
        if (h == "ydh5.yangshipin.cn") {
            return if (p.endsWith(".js")) BlockResponseKind.EMPTY_JS else BlockResponseKind.EMPTY_HTML
        }
        if (h == "app.cctv.com" && p.startsWith("/special/")) {
            return BlockResponseKind.EMPTY_HTML
        }

        // 4. 外围页面非播放 JS（登录/导航/加密/轮播/滚动条/分享/重复 jQuery）
        if ((h == "www.cntv.cn" || h == "www.cctv.com" || h == "tv.cctv.com") &&
            p in PERIPHERAL_CCTV_JS_PATHS
        ) {
            return BlockResponseKind.EMPTY_JS
        }
        if (h.endsWith(".img.cctvpic.com") && p in PERIPHERAL_IMG_JS_PATHS) {
            return BlockResponseKind.EMPTY_JS
        }

        // 5. 外围页面 CSS（含移动频道页壳子样式 /newcctv/tv/live/<ch>/m/style/style.css）
        if (h.endsWith(".img.cctvpic.com") && isNonEssentialPageCss(p)) {
            return BlockResponseKind.EMPTY_CSS
        }

        // 6. 外围页面配图（严格避开 player.cntv.cn 控件图标与自举脚本）
        if (h.endsWith(".img.cctvpic.com") && isNonEssentialPageImage(p)) {
            return BlockResponseKind.NO_CONTENT
        }

        // 7. EPG 节目单、推荐列表与评论/点赞外围接口（兼容 JSONP 与普通 JSON）
        val isPeripheralApi = (h == "api.cntv.cn" && (p.startsWith("/epg/") || p.startsWith("/list/"))) ||
            (h == "cbox.cntv.cn" && p.startsWith("/epg/")) ||
            (h == "common.itv.cntv.cn" && p.startsWith("/praise/")) ||
            h == "comment.yangshipin.cn" ||
            h == "piccpndks.v.kcdnvip.com"
        if (isPeripheralApi) {
            val isJsonp = q.contains("cb=") || q.contains("callback=")
            return if (isJsonp) BlockResponseKind.EMPTY_JS else BlockResponseKind.EMPTY_JSON
        }

        return null
    }

    /**
     * 精确匹配纯外围社交分享、二维码、滚动条、收藏与 PC/白顶导航脚本文件名/路径后缀，
     * 避免跨 DEPA 目录号漏网，同时绝不误伤核心、反爬自举（`sbu_hc.js`）或合规广告脚本。
     */
    private fun isPeripheralSocialOrNavScript(lowerPath: String): Boolean {
        if (!lowerPath.endsWith(".js")) return false
        val fileName = lowerPath.substringAfterLast('/')
        if (fileName.startsWith("jweixin-") || fileName.contains("tinyscrollbar")) return true
        if (fileName in PERIPHERAL_EXACT_JS_FILENAMES) return true
        return lowerPath.endsWith("/pc_nav/index.js") || lowerPath.endsWith("whitetop/index.js")
    }

    private val PERIPHERAL_EXACT_JS_FILENAMES = setOf(
        "dingtalk.js",
        "jquery.qrcode.min.js",
        "shareindex.js",
        "zhibo_shoucang.js",
    )

    private fun isNonEssentialPageCss(lowerPath: String): Boolean {
        if (!lowerPath.endsWith(".css")) return false
        if (lowerPath in PERIPHERAL_IMG_CSS_PATHS) return true
        return lowerPath.startsWith("/newcctv/tv/live/") && lowerPath.endsWith("/m/style/style.css")
    }

    private fun isNonEssentialPageImage(lowerPath: String): Boolean {
        val isImageExt = lowerPath.endsWith(".png") ||
            lowerPath.endsWith(".jpg") ||
            lowerPath.endsWith(".jpeg") ||
            lowerPath.endsWith(".gif") ||
            lowerPath.endsWith(".webp")
        if (!isImageExt) return false
        return lowerPath.startsWith("/photoalbum/page/performance/img/") ||
            lowerPath.startsWith("/photoalbum/vms/") ||
            lowerPath.startsWith("/photoalbum/templet/special/") ||
            lowerPath.startsWith("/photoalbum/templet/common/")
    }
}
