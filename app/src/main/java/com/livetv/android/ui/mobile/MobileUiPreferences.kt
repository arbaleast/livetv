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

    companion object {
        const val KEY_LAST_PLAYED = "last_played_channel_id"
        const val KEY_FAVORITES = "favorite_channel_ids"
        const val KEY_CCTV_WEB_CONTROLS_VISIBLE = "cctv_web_controls_visible"
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
