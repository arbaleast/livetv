package com.livetv.android.data.repository

import com.livetv.android.data.source.ChannelDataSource
import com.livetv.android.domain.model.ChannelCategory
import com.livetv.android.domain.repository.ChannelRepository

/**
 * 默认频道仓库：把 [ChannelDataSource] 的结果直接提供给 UI 层。
 *
 * 目前只有一个实现（本地 assets），后续接入 M3U 导入 / 网络源 / 主备源切换时，
 * 只需在此聚合多个数据源，UI 层无需改动。
 */
class DefaultChannelRepository(
    private val dataSource: ChannelDataSource,
) : ChannelRepository {

    override fun getCategories(): List<ChannelCategory> {
        return try {
            dataSource.loadCategories()
        } catch (e: Exception) {
            // 数据源实现若抛出异常，同样收敛为空列表，避免 UI 崩溃
            emptyList()
        }
    }
}
