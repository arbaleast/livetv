package com.livetv.android.ui.mobile

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量偏好读写后端接口（纯 Kotlin，便于纯 JVM 单元测试）。
 */
internal interface MobileUiPreferenceBackend {
    fun getString(key: String, defaultValue: String?): String?
    fun putString(key: String, value: String)
    fun getStringSet(key: String, defaultValue: Set<String>): Set<String>
    fun putStringSet(key: String, value: Set<String>)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/**
 * 手机端 UI 偏好的纯 Kotlin 存取逻辑（可独立单测）。
 */
internal class MobileUiPreferenceStore(
    private val backend: MobileUiPreferenceBackend,
) {
    fun getLastPlayedChannelId(): String? =
        backend.getString(KEY_LAST_PLAYED, null)

    fun setLastPlayedChannelId(channelId: String) {
        backend.putString(KEY_LAST_PLAYED, channelId)
    }

    fun getFavoriteChannelIds(): Set<String> =
        backend.getStringSet(KEY_FAVORITES, emptySet()).toSet()

    fun toggleFavoriteChannel(channelId: String): Boolean {
        val current = getFavoriteChannelIds().toMutableSet()
        val nowFavorite = if (current.contains(channelId)) {
            current.remove(channelId)
            false
        } else {
            current.add(channelId)
            true
        }
        backend.putStringSet(KEY_FAVORITES, current)
        return nowFavorite
    }

    /** CCTV 网页控件是否处于显示态（默认 `false`，即纯净模式）。 */
    fun isCctvWebControlsVisible(): Boolean =
        backend.getBoolean(KEY_CCTV_WEB_CONTROLS_VISIBLE, false)

    fun setCctvWebControlsVisible(visible: Boolean) {
        backend.putBoolean(KEY_CCTV_WEB_CONTROLS_VISIBLE, visible)
    }

    /** 实时画质增强档位持久化读写（默认 `off`）。 */
    fun getVideoEnhanceModeStorageValue(): String =
        backend.getString(KEY_VIDEO_ENHANCE_MODE, "off") ?: "off"

    fun setVideoEnhanceModeStorageValue(storageValue: String) {
        backend.putString(KEY_VIDEO_ENHANCE_MODE, storageValue)
    }

    /** 视频播放信息悬浮条是否显示（默认 `true`）。 */
    fun isVideoInfoVisible(): Boolean =
        backend.getBoolean(KEY_VIDEO_INFO_VISIBLE, true)

    fun setVideoInfoVisible(visible: Boolean) {
        backend.putBoolean(KEY_VIDEO_INFO_VISIBLE, visible)
    }

    /** 记录 CCTV 频道访问频次与跳转习惯（from -> to），用于概率命中预热。 */
    fun recordCctvSwitch(fromChannelId: String?, toChannelId: String) {
        val toKey = normalizeCctvKey(toChannelId) ?: return
        val freqs = parseCountMap(backend.getString(KEY_CCTV_CHANNEL_FREQ, null)).toMutableMap()
        freqs[toKey] = (freqs[toKey] ?: 0) + 1
        backend.putString(KEY_CCTV_CHANNEL_FREQ, encodeCountMap(decayIfNeeded(freqs), MAX_FREQ_ENTRIES))

        val fromKey = normalizeCctvKey(fromChannelId)
        if (fromKey != null && fromKey != toKey) {
            val trans = parseCountMap(backend.getString(KEY_CCTV_CHANNEL_TRANSITIONS, null)).toMutableMap()
            val edge = "$fromKey>$toKey"
            trans[edge] = (trans[edge] ?: 0) + 1
            backend.putString(
                KEY_CCTV_CHANNEL_TRANSITIONS,
                encodeCountMap(decayIfNeeded(trans), MAX_TRANSITION_ENTRIES),
            )
        }
    }

    fun getCctvChannelFrequencies(): Map<String, Int> =
        parseCountMap(backend.getString(KEY_CCTV_CHANNEL_FREQ, null))

    fun getCctvChannelTransitions(): Map<String, Int> =
        parseCountMap(backend.getString(KEY_CCTV_CHANNEL_TRANSITIONS, null))

    private fun normalizeCctvKey(channelId: String?): String? {
        val raw = channelId?.trim()?.lowercase()?.removePrefix("cctv:").orEmpty()
        return raw.takeIf { it.isNotEmpty() && !it.contains(',') && !it.contains('=') && !it.contains('>') }
    }

    private fun decayIfNeeded(map: MutableMap<String, Int>): Map<String, Int> {
        val maxVal = map.values.maxOrNull() ?: 0
        if (maxVal <= DECAY_THRESHOLD) return map
        return map.mapValues { (_, v) -> maxOf(1, v / 2) }
    }

    private fun parseCountMap(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        raw.split(',').forEach { entry ->
            val idx = entry.indexOf('=')
            if (idx <= 0 || idx >= entry.length - 1) return@forEach
            val key = entry.substring(0, idx).trim()
            val count = entry.substring(idx + 1).trim().toIntOrNull() ?: return@forEach
            if (key.isNotEmpty() && count > 0) {
                out[key] = count
            }
        }
        return out
    }

    private fun encodeCountMap(map: Map<String, Int>, limit: Int): String =
        map.entries
            .sortedByDescending { it.value }
            .take(limit)
            .joinToString(",") { "${it.key}=${it.value}" }

    companion object {
        const val KEY_LAST_PLAYED = "last_played_channel_id"
        const val KEY_FAVORITES = "favorite_channel_ids"
        const val KEY_CCTV_WEB_CONTROLS_VISIBLE = "cctv_web_controls_visible"
        const val KEY_VIDEO_ENHANCE_MODE = "video_enhance_mode"
        const val KEY_VIDEO_INFO_VISIBLE = "video_info_visible"
        const val KEY_CCTV_CHANNEL_FREQ = "cctv_channel_freq"
        const val KEY_CCTV_CHANNEL_TRANSITIONS = "cctv_channel_transitions"
        private const val MAX_FREQ_ENTRIES = 24
        private const val MAX_TRANSITION_ENTRIES = 32
        private const val DECAY_THRESHOLD = 500
    }
}

/**
 * 手机/平板与播放页共享的本地轻量 UI 偏好（上次播放频道 + 本地收藏 + CCTV 网页控件模式）。
 */
object MobileUiPreferences {

    private const val PREFS_NAME = "livetv_mobile_ui"

    fun getLastPlayedChannelId(context: Context): String? =
        store(context).getLastPlayedChannelId()

    fun setLastPlayedChannelId(context: Context, channelId: String) {
        store(context).setLastPlayedChannelId(channelId)
    }

    fun getFavoriteChannelIds(context: Context): Set<String> =
        store(context).getFavoriteChannelIds()

    /**
     * 切换收藏状态并返回切换后是否处于已收藏状态。
     */
    fun toggleFavoriteChannel(context: Context, channelId: String): Boolean =
        store(context).toggleFavoriteChannel(channelId)

    /** CCTV 网页控件持久化状态：默认 `false`（纯净模式）。 */
    fun isCctvWebControlsVisible(context: Context): Boolean =
        store(context).isCctvWebControlsVisible()

    /** 持久化保存 CCTV 网页控件开关状态。 */
    fun setCctvWebControlsVisible(context: Context, visible: Boolean) {
        store(context).setCctvWebControlsVisible(visible)
    }

    /** 读取画质增强档位（默认 [com.livetv.android.util.VideoEnhanceMode.OFF]）。 */
    fun getVideoEnhanceMode(context: Context): com.livetv.android.util.VideoEnhanceMode =
        com.livetv.android.util.VideoEnhanceMode.fromStorageValue(
            store(context).getVideoEnhanceModeStorageValue()
        )

    /** 持久化保存画质增强档位。 */
    fun setVideoEnhanceMode(context: Context, mode: com.livetv.android.util.VideoEnhanceMode) {
        store(context).setVideoEnhanceModeStorageValue(mode.storageValue)
    }

    /** 读取视频播放信息悬浮条是否显示（默认 `true`）。 */
    fun isVideoInfoVisible(context: Context): Boolean =
        store(context).isVideoInfoVisible()

    /** 持久化保存视频播放信息悬浮条开关。 */
    fun setVideoInfoVisible(context: Context, visible: Boolean) {
        store(context).setVideoInfoVisible(visible)
    }

    /** 记录 CCTV 频道访问频次与跳转习惯（from -> to）。 */
    fun recordCctvSwitch(context: Context, fromChannelId: String?, toChannelId: String) {
        store(context).recordCctvSwitch(fromChannelId, toChannelId)
    }

    /** 读取 CCTV 频道访问频次表（key 为规范化频道 key，如 `cctv1`）。 */
    fun getCctvChannelFrequencies(context: Context): Map<String, Int> =
        store(context).getCctvChannelFrequencies()

    /** 读取 CCTV 频道跳转频次表（key 为 `from>to`）。 */
    fun getCctvChannelTransitions(context: Context): Map<String, Int> =
        store(context).getCctvChannelTransitions()

    private fun store(context: Context): MobileUiPreferenceStore =
        MobileUiPreferenceStore(SharedPrefsBackend(prefs(context)))

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private class SharedPrefsBackend(
        private val prefs: SharedPreferences,
    ) : MobileUiPreferenceBackend {
        override fun getString(key: String, defaultValue: String?): String? =
            prefs.getString(key, defaultValue)

        override fun putString(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }

        override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> =
            prefs.getStringSet(key, defaultValue)?.toSet().orEmpty()

        override fun putStringSet(key: String, value: Set<String>) {
            prefs.edit().putStringSet(key, value).apply()
        }

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            prefs.getBoolean(key, defaultValue)

        override fun putBoolean(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }
}
