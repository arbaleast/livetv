package com.livetv.android.data.source

import com.livetv.android.domain.model.ChannelCategory

/**
 * 频道数据源接口。
 *
 * 数据源负责"把原始内容取出来"，解析与校验交给 `ChannelJsonParser`，
 * 仓库负责"把结果聚合给 UI"。
 */
interface ChannelDataSource {

    /**
     * 读取分类频道。
     *
     * 契约：**不抛异常**；读取失败或内容非法时返回空列表。
     */
    fun loadCategories(): List<ChannelCategory>
}
