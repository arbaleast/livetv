package com.livetv.android.util

import com.livetv.android.domain.model.Channel

/**
 * Android TV 端点击/选择频道的路由判定策略（纯 Kotlin，不引入 WebView）。
 *
 * - `cctv:` 前缀频道（含 `cctv1`–`cctv17`、`cctv4k`、`cctv8k` 等）→ [TvChannelRouteAction.UnsupportedCctv]；
 * - `mgtv:` 动态源或具备非空 `streamUrl` 的普通 IPTV → [TvChannelRouteAction.PlayNative]；
 * - 其余空地址频道 → [TvChannelRouteAction.NoStreamUrl]。
 */
sealed interface TvChannelRouteAction {
    data object UnsupportedCctv : TvChannelRouteAction
    data object PlayNative : TvChannelRouteAction
    data object NoStreamUrl : TvChannelRouteAction
}

object TvChannelRoutePolicy {

    const val CCTV_PREFIX = "cctv:"
    const val MGTV_PREFIX = "mgtv:"

    fun isCctvChannel(channelId: String?): Boolean =
        channelId?.trim()?.lowercase()?.startsWith(CCTV_PREFIX) == true

    fun decide(channel: Channel): TvChannelRouteAction = when {
        isCctvChannel(channel.id) -> TvChannelRouteAction.UnsupportedCctv
        channel.streamUrl.isNotBlank() ||
            channel.id.trim().lowercase().startsWith(MGTV_PREFIX) -> TvChannelRouteAction.PlayNative
        else -> TvChannelRouteAction.NoStreamUrl
    }
}
