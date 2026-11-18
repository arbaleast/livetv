package com.livetv.android.domain.repository

import com.livetv.android.domain.model.ChannelCategory

/**
 * 频道仓库（领域层接口）。
 *
 * UI 层只依赖本接口，不关心数据来自 assets、网络还是用户导入的 M3U。
 * 实现见 `com.livetv.android.data.repository.DefaultChannelRepository`。
 */
interface ChannelRepository {

    /**
     * 读取全部分类频道。
     *
     * 契约：**不抛异常**。数据源损坏、解析失败或为空时返回空列表，
     * 由调用方决定如何呈现"无内容"状态。
     */
    fun getCategories(): List<ChannelCategory>
}
