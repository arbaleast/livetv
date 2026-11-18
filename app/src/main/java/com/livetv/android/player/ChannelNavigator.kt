package com.livetv.android.player

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory

/**
 * 频道导航（纯 Kotlin）。
 *
 * 负责在**扁平列表**上做定位与循环切台；分类扁平化由 [flatten] 提供。
 * 不引用任何 `android.*` 类型。
 */
class ChannelNavigator(channels: List<Channel>) {

    /** 扁平化后的频道列表（保持传入顺序）。 */
    val channels: List<Channel> = channels.toList()

    /** 当前频道在扁平列表中的下标；未定位到时为 -1。 */
    var currentIndex: Int = -1
        private set

    /**
     * 按 `channelId` 定位当前频道；找不到时按 `streamUrl` 匹配（多个同 URL 取第一个）。
     *
     * @return 是否定位成功。
     */
    fun locate(channelId: String?, streamUrl: String?): Boolean {
        val byId = channels.indexOfFirst { it.id == channelId }
        if (byId >= 0) {
            currentIndex = byId
            return true
        }
        val byUrl = channels.indexOfFirst { it.streamUrl == streamUrl }
        if (byUrl >= 0) {
            currentIndex = byUrl
            return true
        }
        currentIndex = -1
        return false
    }

    /** 当前频道；未定位时为 null。 */
    fun current(): Channel? = channels.getOrNull(currentIndex)

    /**
     * 下一台（循环：末 → 首）。
     *
     * @return 下一个频道；列表为空时返回 null。
     */
    fun next(): Channel? = step(1)

    /**
     * 上一台（循环：首 → 末）。
     *
     * @return 上一个频道；列表为空时返回 null。
     */
    fun previous(): Channel? = step(-1)

    private fun step(delta: Int): Channel? {
        if (channels.isEmpty()) return null
        val base = if (currentIndex < 0) {
            if (delta > 0) 0 else channels.lastIndex
        } else {
            currentIndex
        }
        val size = channels.size
        val nextIndex = ((base + delta) % size + size) % size
        currentIndex = nextIndex
        return channels[nextIndex]
    }

    companion object {

        /**
         * 把分类列表扁平化成频道列表（按分类顺序、分类内顺序）。
         */
        fun flatten(categories: List<ChannelCategory>): List<Channel> =
            categories.flatMap { it.channels }
    }
}
