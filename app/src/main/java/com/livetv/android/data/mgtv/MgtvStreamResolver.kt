package com.livetv.android.data.mgtv

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.stream.Clock
import com.livetv.android.domain.stream.MonotonicClock
import com.livetv.android.domain.stream.ResolvedStream
import com.livetv.android.domain.stream.StreamResolveError
import com.livetv.android.domain.stream.StreamResolveResult
import com.livetv.android.domain.stream.StreamResolver
import com.livetv.android.domain.stream.StreamTypeDetector

/**
 * MGTV 播放源获取抽象（便于测试注入脱敏 fixture，并在无合规授权时显式熔断返回 `AuthUnavailable`）。
 */
sealed interface MgtvSourceFetchResult {
    data class RawJson(val body: String) : MgtvSourceFetchResult
    data class HttpError(val code: Int, val message: String = "") : MgtvSourceFetchResult
    data class NetworkError(val message: String = "") : MgtvSourceFetchResult
    data class AuthUnavailable(val reason: String) : MgtvSourceFetchResult
}

fun interface MgtvSourceFetcher {
    /**
     * @param npuk 上一次响应里的 `sources[].npuk`：带上它服务端会换一个 CDN 节点（官方换节点重试）。
     *             `null` 表示首次请求。
     */
    fun fetchSourceJson(
        cameraId: String,
        preferredDefinition: String?,
        npuk: String?,
    ): MgtvSourceFetchResult
}


/**
 * MGTV 动态直播流解析器。
 *
 * 职责：
 * - 通过注入的 [MgtvSourceFetcher] 获取 `/v1/live/source` 响应；
 * - 调用 [MgtvJsonParser] 与 [MgtvSourceSelector] 执行白盒筛选（仅放行 HTTPS `.m3u8` + `isp2p=0` + `hdcp=0`）；
 * - 使用可注入 [Clock] 计算动态地址过期时间戳 `expiresAtMs`；
 * - 输出带 Host 白名单隔离（`mgtv.com`）的通用 [ResolvedStream]，与 `PlayerManager` 完全解耦。
 */
class MgtvStreamResolver(
    private val sourceFetcher: MgtvSourceFetcher = HttpMgtvSourceFetcher(),
    private val clock: Clock = MonotonicClock,
    private val allowedHeaderHosts: Set<String> = DEFAULT_ALLOWED_HEADER_HOSTS,
) : StreamResolver {

    /** 每个频道上一次响应里的 npuk：用于"换节点重试"。 */
    private val lastNpuk = HashMap<String, String>()

    override fun resolve(
        channel: Channel,
        preferredDefinition: String?,
    ): StreamResolveResult {
        val cameraId = extractCameraId(channel)
        if (cameraId.isEmpty()) {
            return StreamResolveResult.Failure(
                error = StreamResolveError.UNSUPPORTED_SOURCE,
                message = "missing MGTV cameraId",
            )
        }

        // 记住上一次响应给出的 npuk：重解析时带回去，服务端才会换一个 CDN 节点。
        val previousNpuk = lastNpuk[cameraId]
        return when (val fetched = sourceFetcher.fetchSourceJson(cameraId, preferredDefinition, previousNpuk)) {

            is MgtvSourceFetchResult.AuthUnavailable -> StreamResolveResult.Failure(
                error = StreamResolveError.AUTH_UNAVAILABLE,
                message = fetched.reason,
            )

            is MgtvSourceFetchResult.NetworkError -> StreamResolveResult.Failure(
                error = StreamResolveError.NETWORK_ERROR,
                message = fetched.message,
            )

            is MgtvSourceFetchResult.HttpError -> StreamResolveResult.Failure(
                error = when (fetched.code) {
                    403 -> StreamResolveError.HTTP_FORBIDDEN
                    404 -> StreamResolveError.HTTP_NOT_FOUND
                    429 -> StreamResolveError.HTTP_RATE_LIMITED
                    else -> StreamResolveError.NETWORK_ERROR
                },
                message = "HTTP ${fetched.code}: ${fetched.message}",
            )

            is MgtvSourceFetchResult.RawJson -> parseAndSelect(fetched.body, preferredDefinition, cameraId)
        }
    }

    private fun parseAndSelect(
        rawJson: String,
        preferredDefinition: String?,
        cameraId: String,
    ): StreamResolveResult {
        val parsed = MgtvJsonParser.parseSourceResponse(rawJson)
            ?: return StreamResolveResult.Failure(
                error = StreamResolveError.PARSE_ERROR,
                message = "failed to parse MGTV source response",
            )

        // 严格 fail-closed：顶层 code 缺失或非 "0" 一律拒绝
        if (parsed.code != "0") {
            return StreamResolveResult.Failure(
                error = StreamResolveError.HTTP_FORBIDDEN,
                message = "MGTV source error code=${parsed.code}: ${parsed.message}",
            )
        }

        val selected = MgtvSourceSelector.selectBestSource(parsed, preferredDefinition)
            ?: return StreamResolveResult.Failure(
                error = StreamResolveError.UNSUPPORTED_SOURCE,
                message = "no eligible non-P2P/non-HDCP HTTPS .m3u8 source available",
            )

        // 记录本次的 npuk，供下一次"换节点重试"使用
        selected.npuk.takeIf { it.isNotBlank() }?.let { lastNpuk[cameraId] = it }

        val effectiveExpireSec = if (selected.urlExpireDurationSec <= 0L) {
            FALLBACK_EXPIRE_DURATION_SEC
        } else {
            selected.urlExpireDurationSec.coerceAtMost(MAX_EXPIRE_DURATION_SEC)
        }
        val expiresAtMs = clock.nowMs() + effectiveExpireSec * 1_000L

        return StreamResolveResult.Success(
            ResolvedStream(
                url = selected.url,
                headers = DEFAULT_PLAYBACK_HEADERS,
                expiresAtMs = expiresAtMs,
                mimeType = StreamTypeDetector.MIME_HLS,
                allowedHeaderHosts = allowedHeaderHosts,
                definitionLabel = selected.name,
            ),
        )
    }

    companion object {
        const val CHANNEL_ID_PREFIX = "mgtv:"

        /** 响应缺少或给出非正 `urlExpireDuration` 时的保守短 TTL（60 秒），杜绝动态流退化为“永不过期”。 */
        const val FALLBACK_EXPIRE_DURATION_SEC = 60L

        /** 有效期上限钳制（7 天），防止极端数值在毫秒换算时发生 `Long` 溢出。 */
        const val MAX_EXPIRE_DURATION_SEC = 7L * 24L * 3600L

        val DEFAULT_ALLOWED_HEADER_HOSTS = setOf("mgtv.com")

        val DEFAULT_PLAYBACK_HEADERS = mapOf(
            "Origin" to "https://www.mgtv.com",
            "Referer" to "https://www.mgtv.com/",
        )

        /** 从领域 [Channel] 中提取 MGTV 的 `cameraId`（兼容 `mgtv:287` 与纯 `287`）。 */
        fun extractCameraId(channel: Channel): String {
            val raw = channel.id.trim()
            return if (raw.startsWith(CHANNEL_ID_PREFIX)) {
                raw.removePrefix(CHANNEL_ID_PREFIX).trim()
            } else {
                raw
            }
        }
    }
}
