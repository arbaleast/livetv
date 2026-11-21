package com.livetv.android.domain.repository

import com.livetv.android.domain.model.ChannelCategory

/**
 * 应用级频道目录存储（纯 Kotlin，无 Android 依赖，线程安全）。
 *
 * 现在只有 MGTV 一个频道来源：首页（手机/TV）与播放页（切台/抽屉）共用同一份快照。
 */
class ChannelCatalogStore {

    private var categories: List<ChannelCategory> = emptyList()

    /** 用最新的目录快照整体替换（丢弃空分类）。 */
    @Synchronized
    fun update(categories: List<ChannelCategory>) {
        this.categories = categories.filter { it.channels.isNotEmpty() }
    }

    /** 当前目录快照（分类顺序与内部频道顺序保持不变）。 */
    @Synchronized
    fun getAllCategories(): List<ChannelCategory> = categories

    /** 目录是否尚未加载（或加载结果为空）。 */
    @Synchronized
    fun isEmpty(): Boolean = categories.isEmpty()
}
