package com.livetv.android.player

import com.livetv.android.domain.model.Channel

/**
 * 重试与主备源切换策略（纯 Kotlin）。
 *
 * **不引用任何 `android.*` 或 `androidx.media3.*` 类型**，不 sleep、不持有 Handler——
 * 只做"给定状态 + 给定事件 → 给定结果"的纯函数计算，延时由调用方负责调度。
 *
 * 语义（任务书定死，不可自行更改）：
 * - `maxRetriesPerSource = 2` 表示每条源**最多重试 2 次，即最多 3 次尝试**；
 * - 退避序列为 1500ms、3000ms，分别对应第 1、2 次重试；
 * - 某条源播放成功后重试计数清零，且**保持在当前源**继续重试；
 * - 只有"手动重置"（用户按 OK）才回到主源 `streamUrl` 开启新一轮；
 * - 主源重试耗尽后切到下一条 backup 源，该源计数归零；
 * - 所有候选源耗尽 → `Fatal` / 等待手动重置。
 */
object RetryPolicy {

    /** 每条源最多重试次数（2 次重试 = 最多 3 次尝试）。 */
    const val MAX_RETRIES_PER_SOURCE = 2

    /**
     * 退避序列：第 1 次重试等待 1500ms，第 2 次重试等待 3000ms。
     * 超出该序列时回落到最后一个值。
     */
    val BACKOFF_DELAYS_MS = listOf(1500L, 3000L)

    /**
     * 不可重试的 HTTP 状态码集合。
     *
     * 这类状态代表"鉴权 / 授权 / 限流"这类**重试也不会变好**的结论：
     * - `401` 未授权：缺少凭据或凭据过期；
     * - `403` 拒绝访问 / 签名过期 / 防盗链拦截；
     * - `429` 被风控限流。
     *
     * 命中时不再消耗 1500ms / 3000ms 的退避重试，直接切备用源或进入 `Fatal`。
     */
    val FAST_FAIL_HTTP_STATUSES = setOf(401, 403, 429)

    /** 该 HTTP 状态码是否属于"不可重试、需立即失败"的一类。 */
    fun isFastFailHttpStatus(code: Int): Boolean = code in FAST_FAIL_HTTP_STATUSES

    /** 该错误是否属于"不可重试、需立即失败"的一类。 */
    fun isFastFail(error: PlaybackError): Boolean =
        error is PlaybackError.HttpStatus && isFastFailHttpStatus(error.code)

    /**
     * 生成有序候选源列表：主源在前，其后是合法的备用源。
     *
     * 过滤规则与阶段二 `ChannelJsonParser` 一致：仅保留 `http://` / `https://`（忽略大小写）。
     */
    fun candidateUrls(channel: Channel): List<String> {
        val main = channel.streamUrl.trim()
        val result = mutableListOf<String>()
        if (isValidUrl(main)) result += main
        channel.backupUrls.map { it.trim() }.forEach { url ->
            if (isValidUrl(url)) result += url
        }
        return result
    }

    /** 是否是合法的 http/https 地址。 */
    fun isValidUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        return trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
    }

    /**
     * 不可变的重试状态。
     *
     * @param sourceIndex 当前候选源在候选列表中的下标（0 = 主源）。
     * @param retryCount 当前源已重试次数（0 = 还没重试过）。
     * @param candidateCount 候选源总数（用于判断是否还有下一条源）。
     */
    data class RetryState(
        val sourceIndex: Int = 0,
        val retryCount: Int = 0,
        val candidateCount: Int = 0,
    ) {
        /** 是否还有下一条候选源可用。 */
        val hasNextSource: Boolean get() = sourceIndex + 1 < candidateCount
    }

    /** 重试结果。 */
    sealed interface RetryOutcome {
        /** 等待 [delayMs] 毫秒后重试当前源。 */
        data class Retry(val delayMs: Long) : RetryOutcome

        /** 切换到候选源列表中的第 [index] 条源，该源计数归零。 */
        data class SwitchSource(val index: Int) : RetryOutcome

        /** 所有候选源均已耗尽，等待用户手动重置。 */
        data object Fatal : RetryOutcome
    }

    /**
     * 播放失败时的处理。
     *
     * @param error 失败原因分类。`401 / 403 / 429` 走**快速失败**：不消耗退避重试，
     *   直接切下一条候选源；没有下一条源则 `Fatal`。其余错误（超时、网络中断、5xx 等）
     *   仍按每源最多 2 次、1500ms / 3000ms 退避重试。
     * @return [RetryOutcome.Retry]（当前源还能重试）、
     *   [RetryOutcome.SwitchSource]（当前源重试耗尽或快速失败，但还有下一条源）、
     *   或 [RetryOutcome.Fatal]（所有源耗尽）。
     */
    fun onFailure(state: RetryState, error: PlaybackError): RetryOutcome {
        if (isFastFail(error)) {
            // 鉴权 / 防盗链 / 限流：重试没有意义，直接换源或终结。
            return if (state.hasNextSource) {
                RetryOutcome.SwitchSource(state.sourceIndex + 1)
            } else {
                RetryOutcome.Fatal
            }
        }
        val nextRetryCount = state.retryCount + 1
        if (nextRetryCount <= MAX_RETRIES_PER_SOURCE) {
            val delay = BACKOFF_DELAYS_MS.getOrElse(nextRetryCount - 1) { BACKOFF_DELAYS_MS.last() }
            return RetryOutcome.Retry(delay)
        }
        // 当前源重试耗尽
        return if (state.hasNextSource) {
            RetryOutcome.SwitchSource(state.sourceIndex + 1)
        } else {
            RetryOutcome.Fatal
        }
    }

    /**
     * 播放成功：重试计数清零，**保持当前源**。
     */
    fun onSuccess(state: RetryState): RetryState = state.copy(retryCount = 0)

    /**
     * 手动重置（用户按 OK）：回到主源（索引 0），计数归零，开启新一轮。
     */
    fun onManualReset(state: RetryState): RetryState =
        RetryState(sourceIndex = 0, retryCount = 0, candidateCount = state.candidateCount)

    /**
     * 切台：丢弃旧频道的重试状态，新频道从主源、计数 0 重新开始。
     */
    fun onChannelSwitched(candidateCount: Int): RetryState =
        RetryState(sourceIndex = 0, retryCount = 0, candidateCount = candidateCount)
}
