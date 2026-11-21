package com.livetv.android.ui.mobile

import android.content.Context

/**
 * 手机/平板与播放页共享的本地轻量 UI 偏好（上次播放频道 + 本地收藏频道集合）。
 */
object MobileUiPreferences {

    private const val PREFS_NAME = "livetv_mobile_ui"
    private const val KEY_LAST_PLAYED = "last_played_channel_id"
    private const val KEY_FAVORITES = "favorite_channel_ids"

    fun getLastPlayedChannelId(context: Context): String? =
        prefs(context).getString(KEY_LAST_PLAYED, null)

    fun setLastPlayedChannelId(context: Context, channelId: String) {
        prefs(context).edit().putString(KEY_LAST_PLAYED, channelId).apply()
    }

    fun getFavoriteChannelIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_FAVORITES, emptySet())?.toSet().orEmpty()

    /**
     * 切换收藏状态并返回切换后是否处于已收藏状态。
     */
    fun toggleFavoriteChannel(context: Context, channelId: String): Boolean {
        val current = getFavoriteChannelIds(context).toMutableSet()
        val nowFavorite = if (current.contains(channelId)) {
            current.remove(channelId)
            false
        } else {
            current.add(channelId)
            true
        }
        prefs(context).edit().putStringSet(KEY_FAVORITES, current).apply()
        return nowFavorite
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
