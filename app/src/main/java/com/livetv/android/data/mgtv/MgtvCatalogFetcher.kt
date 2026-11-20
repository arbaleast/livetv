package com.livetv.android.data.mgtv

/**
 * MGTV 公开频道目录抓取器（**无需 Cookie、无需签名**，纯 JVM 可测）。
 *
 * 实测结论：`mpplive.api.mgtv.com/v1/epg/turnplay/getLiveAssetCategoryList`
 * 去掉 JSONP `callback` 后仍返回 `errno=0` 的完整分类/频道树，
 * 因此频道目录属于可公开获取的数据。
 */
class MgtvCatalogFetcher(
    private val client: MgtvHttpClient = MgtvHttpClient(),
    private val catalogUrl: String = MgtvEndpoints.catalogUrl(),
) {

    sealed interface Result {
        /** 原始 JSON（已剥离 JSONP 包装前的原文，交给 [MgtvJsonParser] 处理）。 */
        data class Success(val rawBody: String) : Result
        data class HttpError(val statusCode: Int) : Result
        data class NetworkError(val message: String) : Result
        data class InvalidUrl(val message: String) : Result
        data object TooLarge : Result
    }

    fun fetchCatalog(): Result {
        val headers = mapOf(
            "Referer" to MgtvEndpoints.WEB_REFERER,
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        )
        return when (val response = client.get(catalogUrl, headers)) {
            is MgtvHttpClient.Result.Success -> Result.Success(response.body)
            is MgtvHttpClient.Result.HttpError -> Result.HttpError(response.statusCode)
            is MgtvHttpClient.Result.NetworkError -> Result.NetworkError(response.message)
            is MgtvHttpClient.Result.InvalidUrl -> Result.InvalidUrl(response.message)
            MgtvHttpClient.Result.TooLarge -> Result.TooLarge
        }
    }

    companion object {
        const val USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
