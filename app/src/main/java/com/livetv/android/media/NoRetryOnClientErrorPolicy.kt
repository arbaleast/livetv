package com.livetv.android.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.livetv.android.player.RetryPolicy

/**
 * 让 Media3 **底层** 不再对 401 / 403 / 429 重试。
 *
 * 为什么需要：`PlayerManager` 自己的 `RetryPolicy` 已经把这三类状态判成"重试也不会变好"，
 * 但 Media3 的 `DefaultLoadErrorHandlingPolicy` 仍会在 DataSource 层**再重试 3 次**。
 * 两者叠加就会把一次 403 放大成 4 次实际请求——这既是风控连坐的来源，
 * 也让真机验收里"同一拦截只应出现 1 次 DENY 403"的判据永远不成立
 * （真机实测：一次 403 被放大成 4 次请求）。
 *
 * 处理方式：命中 401/403/429 时返回 [C.TIME_UNSET]（Media3 约定的"不要重试"），
 * 其余错误（5xx、网络中断、超时）继续沿用默认的退避重试。
 */
@UnstableApi
class NoRetryOnClientErrorPolicy(
    private val delegate: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
) : LoadErrorHandlingPolicy {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val status = findHttpStatus(loadErrorInfo.exception)
        if (status != null && RetryPolicy.isFastFailHttpStatus(status)) {
            return C.TIME_UNSET
        }
        return delegate.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        delegate.getMinimumLoadableRetryCount(dataType)

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? =
        delegate.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)

    /** 从异常链里找真正的 HTTP 响应码（只认 `InvalidResponseCodeException`）。 */
    private fun findHttpStatus(error: Throwable?): Int? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is HttpDataSource.InvalidResponseCodeException) {
                return current.responseCode
            }
            current = current.cause
            depth++
        }
        return null
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 10
    }
}
