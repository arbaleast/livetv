package com.livetv.android.webplayer

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory

/**
 * CCTV WebView 选台抽屉路由决策器（纯 Kotlin，可纯 JVM 单测）。
 *
 * - 同台 → `NoOp`；
 * - 受支持 CCTV 1–17 → `ReloadInPlace`（WebView 原地重载，不重建 Activity）；
 * - 4K/8K 或不在支持范围内的 CCTV → `UnsupportedToast`；
 * - MGTV / 普通 IPTV → `SwitchToNativePlayer`（由 Activity 销毁 WebView 后拉起 `PlayerActivity`）。
 */
sealed interface CctvWebSwitchAction {
    data object NoOp : CctvWebSwitchAction
    data class ReloadInPlace(
        val channelId: String,
        val channelName: String,
        val url: String,
    ) : CctvWebSwitchAction
    data object UnsupportedToast : CctvWebSwitchAction
    data class SwitchToNativePlayer(val channel: Channel) : CctvWebSwitchAction
}

object CctvWebSwitchPolicy {

    fun decide(
        currentChannelId: String?,
        targetChannel: Channel,
    ): CctvWebSwitchAction {
        val targetKey = CctvWebChannel.keyOf(targetChannel.id)
        if (targetKey == null) {
            val normalizedId = targetChannel.id.trim().lowercase()
            val isUnsupportedCctv = normalizedId.startsWith(CctvWebChannel.CHANNEL_PREFIX) ||
                (normalizedId.startsWith("cctv") && targetChannel.streamUrl.isBlank())
            return if (isUnsupportedCctv) {
                CctvWebSwitchAction.UnsupportedToast
            } else {
                CctvWebSwitchAction.SwitchToNativePlayer(targetChannel)
            }
        }

        val url = CctvWebChannel.liveUrl(targetKey).orEmpty()
        return if (CctvWebChannel.keyOf(currentChannelId) == targetKey) {
            CctvWebSwitchAction.NoOp
        } else {
            CctvWebSwitchAction.ReloadInPlace(
                channelId = "${CctvWebChannel.CHANNEL_PREFIX}$targetKey",
                channelName = targetChannel.name,
                url = url,
            )
        }
    }

    /** 目录为空（冷启动/加载失败）时回退到受支持的 CCTV 1–17，保证抽屉可用。 */
    fun resolveCategories(
        storeCategories: List<ChannelCategory>,
    ): List<ChannelCategory> {
        if (storeCategories.isNotEmpty()) return storeCategories
        val channels = (1..17).map { index ->
            Channel(
                id = "${CctvWebChannel.CHANNEL_PREFIX}cctv$index",
                name = "CCTV-$index",
                description = "",
                streamUrl = "",
            )
        }
        return listOf(
            ChannelCategory(
                id = "${CctvWebChannel.CHANNEL_PREFIX}all",
                name = "央视直播",
                channels = channels,
            ),
        )
    }

    /** 按频道 id 查名称；目录里找不到时退回非空调用方兜底名或 `CCTV-N`。 */
    fun resolveChannelName(
        channelId: String?,
        categories: List<ChannelCategory>,
        fallbackName: String? = null,
    ): String {
        if (!channelId.isNullOrBlank()) {
            categories.asSequence()
                .flatMap { it.channels.asSequence() }
                .firstOrNull { it.id == channelId }
                ?.let { return it.name }
        }
        val key = CctvWebChannel.keyOf(channelId)
        val trimmedFallback = fallbackName?.trim()?.takeIf { it.isNotEmpty() }
        return trimmedFallback ?: key?.let { "CCTV-${it.removePrefix("cctv")}" } ?: "CCTV"
    }
}
