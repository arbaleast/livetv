package com.livetv.android.data.mgtv

import java.net.URLEncoder

/**
 * MGTV 公开接口端点与查询参数构造（纯 Kotlin，**不含任何凭据**）。
 *
 * 参数分两类：
 * - **公开静态参数**：来自网页端公开配置，可直接使用（频道目录接口全量公开）；
 * - **动态授权参数**：`sign` / `did` / `deviceId` 等由官方客户端私钥签发，
 *   只能由外部合规授权方注入，**本项目绝不计算、猜测或伪造**。
 */
object MgtvEndpoints {

    const val CATALOG_HOST = "mpplive.api.mgtv.com"
    const val CATALOG_PATH = "/v1/epg/turnplay/getLiveAssetCategoryList"

    const val SOURCE_HOST = "pwlp.bz.mgtv.com"
    const val SOURCE_PATH = "/v1/live/source"

    /** 网页端公开配置常量。 */
    const val VERSION = "PCweb_1.0"
    const val PLATFORM = "4"
    const val MEDIA_ASSET_ID = "TVStationAll"
    const val BUSS_ID = "2000001"
    const val SUPPORT = "10000000"
    const val JSONP_CALLBACK = "livecallback"
    const val CLIENT_KEY = "pcweb"
    const val APP_VERSION = "imgotv-pch5-1.2.3"

    /** 频道目录接口的 Referer（公开页面来源，非凭据）。 */
    const val WEB_REFERER = "https://www.mgtv.com/"

    /**
     * 频道目录接口地址（公开、免签名、免 Cookie）。
     *
     * @param jsonp 是否保留 JSONP 回调包装；解析器两种都能处理。
     */
    fun catalogUrl(jsonp: Boolean = true): String {
        val query = linkedMapOf(
            "version" to VERSION,
            "platform" to PLATFORM,
            "media_asset_id" to MEDIA_ASSET_ID,
            "buss_id" to BUSS_ID,
            "_support" to SUPPORT,
        )
        if (jsonp) query["callback"] = JSONP_CALLBACK
        return buildUrl(CATALOG_HOST, CATALOG_PATH, query)
    }

    /**
     * 播放源接口的**待签名参数集**（不含 `sign` 与 `_support`）。
     *
     * 顺序无关：签名前会按参数名排序。空字符串参数（`activityId`、`uid` 等）必须保留，
     * 服务端签名校验会覆盖它们。
     *
     * @param deviceId 本地生成的设备 UUID，同时用作 `did` 与 `deviceId`。
     * @param timestampMs 毫秒时间戳参数 `_t`，每次请求都应不同。
     */
    fun sourceParams(
        cameraId: String,
        deviceId: String,
        timestampMs: Long,
        preferredDefinition: String? = null,
    ): Map<String, String> = linkedMapOf(
        "cameraId" to cameraId,
        "activityId" to "",
        "platform" to PLATFORM,
        "appVersion" to APP_VERSION,
        "clientKey" to CLIENT_KEY,
        "auth_mode" to "1",
        "local_definition" to "",
        "init_definition" to (preferredDefinition?.takeIf { it.isNotBlank() } ?: "2"),
        "uid" to "",
        "token" to "",
        "hdts" to "h264",
        "lls" to "1",
        "supportFlv" to "1",
        "did" to deviceId,
        "deviceId" to deviceId,
        "_t" to timestampMs.toString(),
    )

    /**
     * 播放源接口地址：把 [sign] 与 `_support` 追加到已签名参数之后。
     */
    fun sourceUrl(params: Map<String, String>, sign: String): String {
        val query = LinkedHashMap<String, String>(params.size + 2).apply {
            putAll(params)
            put("_support", SUPPORT)
            put("sign", sign)
        }
        return buildUrl(SOURCE_HOST, SOURCE_PATH, query)
    }

    private fun buildUrl(host: String, path: String, query: Map<String, String>): String {
        val sb = StringBuilder("https://").append(host).append(path)
        var first = true
        for ((key, value) in query) {
            sb.append(if (first) '?' else '&')
            first = false
            sb.append(encode(key)).append('=').append(encode(value))
        }
        return sb.toString()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
