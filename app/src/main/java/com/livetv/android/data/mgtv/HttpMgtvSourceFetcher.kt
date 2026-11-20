package com.livetv.android.data.mgtv

/**
 * 基于真实 HTTP 的 MGTV 播放源抓取器。
 *
 * 流程：
 * 1. 取本地设备 UUID（[MgtvDeviceIdProvider]，同时用作 `did` / `deviceId`）；
 * 2. 组装待签名参数（[MgtvEndpoints.sourceParams]）；
 * 3. 本地计算签名（[MgtvSigner]，纯哈希、不联网）；
 * 4. 追加 `_support` 与 `sign` 后发起请求。
 *
 * 说明：签名是网页播放器公开代码里的常规参数校验，不涉及用户凭据；
 * 设备 UUID 由客户端自行生成，**不冒用任何他人会话**，也不携带 Cookie。
 */
class HttpMgtvSourceFetcher(
    private val deviceIdProvider: MgtvDeviceIdProvider = EphemeralMgtvDeviceId,
    private val client: MgtvHttpClient = MgtvHttpClient(),
    private val timestampProvider: () -> Long = { System.currentTimeMillis() },
    private val signer: (Map<String, String>) -> String = MgtvSigner::sign,
    private val urlBuilder: (Map<String, String>, String) -> String = MgtvEndpoints::sourceUrl,
) : MgtvSourceFetcher {

    override fun fetchSourceJson(
        cameraId: String,
        preferredDefinition: String?,
    ): MgtvSourceFetchResult {
        val params = MgtvEndpoints.sourceParams(
            cameraId = cameraId,
            deviceId = deviceIdProvider.deviceId(),
            timestampMs = timestampProvider(),
            preferredDefinition = preferredDefinition,
        )
        val url = urlBuilder(params, signer(params))
        return when (val response = client.get(url, sourceRequestHeaders())) {
            is MgtvHttpClient.Result.Success -> MgtvSourceFetchResult.RawJson(response.body)
            is MgtvHttpClient.Result.HttpError ->
                MgtvSourceFetchResult.HttpError(response.statusCode, response.body)

            is MgtvHttpClient.Result.NetworkError -> MgtvSourceFetchResult.NetworkError(response.message)
            is MgtvHttpClient.Result.InvalidUrl -> MgtvSourceFetchResult.NetworkError(response.message)
            MgtvHttpClient.Result.TooLarge -> MgtvSourceFetchResult.NetworkError("response too large")
        }
    }

    companion object {
        /**
         * 请求 `/v1/live/source` 时携带的浏览器同源头。
         *
         * 说明：JDK 的 `HttpURLConnection` 默认把 `Origin` 列入受限请求头并静默丢弃，
         * 而 Android 的实现没有该限制；因此这里把请求头抽成可单测的纯函数，
         * 由测试直接断言映射内容，避免依赖 JVM 的受限头行为。
         */
        internal fun sourceRequestHeaders(): Map<String, String> = mapOf(
            "Referer" to MgtvEndpoints.WEB_REFERER,
            "Origin" to MgtvEndpoints.WEB_REFERER.trimEnd('/'),
            "User-Agent" to MgtvCatalogFetcher.USER_AGENT,
        )
    }
}
