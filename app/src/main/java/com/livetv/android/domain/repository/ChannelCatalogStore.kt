package com.livetv.android.domain.repository

import com.livetv.android.domain.model.ChannelCategory

/**
 * 应用级聚合频道目录存储（纯 Kotlin，无 Android 依赖，线程安全）。
 *
 * 解决订阅源与补充公开目录（如 MGTV）生命周期独立导致的数据割裂：
 * - 刷新或清除订阅源时，仅替换订阅分段，保留已加载的补充目录；
 * - 首页（手机/TV）与播放页（切台/抽屉）共用同一份聚合快照。
 */
class ChannelCatalogStore {

    private var subscriptionCategories: List<ChannelCategory> = emptyList()
    private var supplementalCategories: List<ChannelCategory> = emptyList()

    /** 更新自定义订阅（含本地缓存）分段，保留已加载的补充目录分段。 */
    @Synchronized
    fun updateSubscriptionCategories(categories: List<ChannelCategory>) {
        subscriptionCategories = categories.filter { it.channels.isNotEmpty() }
    }

    /** 更新补充公开目录分段（如 MGTV），保留现有订阅分段。 */
    @Synchronized
    fun updateSupplementalCategories(categories: List<ChannelCategory>) {
        supplementalCategories = categories.filter { it.channels.isNotEmpty() }
    }

    /** 返回聚合后的完整非空分类列表（订阅分段在前，补充目录分段在后，按分类 id 去重）。 */
    @Synchronized
    fun getAllCategories(): List<ChannelCategory> {
        if (subscriptionCategories.isEmpty()) return supplementalCategories
        if (supplementalCategories.isEmpty()) return subscriptionCategories

        val seenIds = HashSet<String>(subscriptionCategories.size)
        val merged = ArrayList<ChannelCategory>(subscriptionCategories.size + supplementalCategories.size)
        subscriptionCategories.forEach { category ->
            if (seenIds.add(category.id)) {
                merged += category
            }
        }
        supplementalCategories.forEach { category ->
            if (seenIds.add(category.id)) {
                merged += category
            }
        }
        return merged
    }

    /** 补充公开目录分段是否已加载且非空。 */
    @Synchronized
    fun hasSupplementalCategories(): Boolean = supplementalCategories.isNotEmpty()
}
