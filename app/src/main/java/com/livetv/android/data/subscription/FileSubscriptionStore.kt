package com.livetv.android.data.subscription

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * 基于 [Context] 的订阅配置持久化实现。
 *
 * - 订阅 URL 存放在 [SharedPreferences]；
 * - 播放列表原文存放在 `filesDir/remote_playlist.m3u`；
 * - 写入采用「临时文件 + rename」的原子替换，避免进程被杀时留下半截文件。
 */
class FileSubscriptionStore(
    context: Context,
    private val playlistFileName: String = DEFAULT_PLAYLIST_FILE_NAME,
) : SubscriptionStore {

    private val appContext: Context = context.applicationContext

    private val prefs
        get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val playlistFile: File
        get() = File(appContext.filesDir, playlistFileName)

    override fun getUrl(): String? = prefs.getString(KEY_URL, null)?.trim()?.takeIf { it.isNotEmpty() }

    override fun saveUrl(url: String?) {
        val editor = prefs.edit()
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            editor.remove(KEY_URL)
        } else {
            editor.putString(KEY_URL, trimmed)
        }
        editor.apply()
    }

    override fun readPlaylist(): String? {
        val file = playlistFile
        if (!file.exists()) return null
        return try {
            file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (e: IOException) {
            null
        }
    }

    override fun writePlaylist(content: String) {
        val target = playlistFile
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            temp.writeText(content, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                // 少数文件系统上 rename 覆盖会失败，退化为直接覆盖写
                target.writeText(content, Charsets.UTF_8)
                temp.delete()
            }
        } catch (e: IOException) {
            temp.delete()
        }
    }

    override fun clearPlaylist() {
        playlistFile.delete()
    }

    companion object {
        private const val PREFS_NAME = "livetv_subscription"
        private const val KEY_URL = "subscription_url"
        const val DEFAULT_PLAYLIST_FILE_NAME = "remote_playlist.m3u"
    }
}
