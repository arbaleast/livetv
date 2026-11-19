package com.livetv.android.data.subscription

/**
 * 订阅配置的持久化抽象：保存订阅 URL 与最近一次成功的远程播放列表原文。
 *
 * 纯接口，不依赖 Android，便于用内存假实现做确定性单测。
 */
interface SubscriptionStore {

    /** 当前配置的订阅 URL；未配置或为空白时返回 `null`。 */
    fun getUrl(): String?

    /**
     * 保存订阅 URL。
     *
     * @param url `null` 或空白字符串表示清除订阅。
     */
    fun saveUrl(url: String?)

    /** 最近一次成功的远程播放列表原文；不存在时返回 `null`。 */
    fun readPlaylist(): String?

    /** 覆盖写入远程播放列表原文。 */
    fun writePlaylist(content: String)

    /** 删除缓存的远程播放列表原文。 */
    fun clearPlaylist()
}
