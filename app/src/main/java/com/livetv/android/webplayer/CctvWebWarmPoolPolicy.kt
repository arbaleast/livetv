package com.livetv.android.webplayer

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory

/**
 * CCTV 1-Slot 静音相邻台预热池策略（纯 Kotlin，可纯 JVM 单测）。
 */
internal object CctvWebWarmPoolPolicy {

    /**
     * 概率命中 + 相邻兜底的 1-Slot 预热选台策略：
     * 1. **强跳转习惯**（`from>to` 历史跳转次数 >= 2）：优先预热从当前台最常跳去的频道；
     * 2. **高频常看台**：其次预热全局切换频率最高的直播频道（排除当前台）；
     * 3. **收藏首台**：若尚无历史统计但有收藏直播台，预热首个收藏台；
     * 4. **相邻台兜底**：按最近切台方向（`+1` / `-1`）回绕选择相邻台。
     */
    fun pickNextChannel(
        currentChannelId: String?,
        categories: List<ChannelCategory>,
        direction: Int,
        frequencies: Map<String, Int> = emptyMap(),
        transitions: Map<String, Int> = emptyMap(),
        favorites: Set<String> = emptySet(),
    ): Channel? {
        val currentKey = CctvWebChannel.keyOf(currentChannelId) ?: return null
        if (CctvWebChannel.isVodPoolChannel(currentChannelId)) return null

        val liveChannels = CctvWebSwitchPolicy.resolveCategories(categories)
            .asSequence()
            .flatMap { it.channels.asSequence() }
            .filter { ch ->
                CctvWebChannel.keyOf(ch.id) != null && !CctvWebChannel.isVodPoolChannel(ch.id)
            }
            .distinctBy { CctvWebChannel.keyOf(it.id) }
            .toList()

        if (liveChannels.size <= 1) return null
        val currentIndex = liveChannels.indexOfFirst { CctvWebChannel.keyOf(it.id) == currentKey }
        if (currentIndex < 0) return null

        val candidates = liveChannels.filter { CctvWebChannel.keyOf(it.id) != currentKey }
        val favoriteKeys = favorites.mapNotNull { CctvWebChannel.keyOf(it) }.toSet()

        // 1. 强跳转习惯（from>to >= 2）
        val bestTransition = candidates.maxWithOrNull(
            compareBy<Channel>(
                { transitions["$currentKey>${CctvWebChannel.keyOf(it.id)}"] ?: 0 },
                { frequencies[CctvWebChannel.keyOf(it.id)] ?: 0 },
                { if (CctvWebChannel.keyOf(it.id) in favoriteKeys) 1 else 0 },
            ),
        )
        val bestTransitionCount = bestTransition?.let {
            transitions["$currentKey>${CctvWebChannel.keyOf(it.id)}"] ?: 0
        } ?: 0
        if (bestTransition != null && bestTransitionCount >= MIN_TRANSITION_CONFIDENCE) {
            return bestTransition
        }

        // 2. 高频常看台（freq >= 1）
        val bestFreq = candidates.maxWithOrNull(
            compareBy<Channel>(
                { frequencies[CctvWebChannel.keyOf(it.id)] ?: 0 },
                { if (CctvWebChannel.keyOf(it.id) in favoriteKeys) 1 else 0 },
            ),
        )
        val bestFreqCount = bestFreq?.let { frequencies[CctvWebChannel.keyOf(it.id)] ?: 0 } ?: 0
        if (bestFreq != null && bestFreqCount >= 1) {
            return bestFreq
        }

        // 3. 收藏台
        candidates.firstOrNull { CctvWebChannel.keyOf(it.id) in favoriteKeys }?.let { return it }

        // 4. 相邻台兜底
        val step = if (direction < 0) -1 else 1
        val nextIndex = (currentIndex + step).mod(liveChannels.size)
        val candidate = liveChannels[nextIndex]
        return candidate.takeIf { CctvWebChannel.keyOf(it.id) != currentKey }
    }

    private const val MIN_TRANSITION_CONFIDENCE = 2

    /** 仅在直播台前台稳定播放且非 PiP / 非休眠 / 非切源时允许启动 1-Slot 预热。 */
    fun shouldWarm(
        isPlaying: Boolean,
        revealed: Boolean,
        isInPictureInPictureMode: Boolean,
        suspendedInBackground: Boolean,
        isFinishingOrDestroyed: Boolean,
        isSwitchingEngine: Boolean,
        currentChannelId: String?,
    ): Boolean {
        if (!isPlaying || !revealed) return false
        if (isInPictureInPictureMode || suspendedInBackground || isFinishingOrDestroyed || isSwitchingEngine) {
            return false
        }
        return CctvWebChannel.keyOf(currentChannelId) != null &&
            !CctvWebChannel.isVodPoolChannel(currentChannelId)
    }
}
