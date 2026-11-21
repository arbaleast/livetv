package com.livetv.android.domain.repository

import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory

/**
 * 多源频道目录的**纯函数合并器**（无 Android 依赖，可纯 JVM 单测）。
 *
 * 规则：
 * 1. 顺序固定：**CCTV 段在前、MGTV 段在后**；
 * 2. 丢弃频道列表为空的分类；
 * 3. **禁止"按分类 id 丢弃后项"**：同分类 id 时合并 `channels` 并按 `channel.id` 去重
 *    （保留先出现项），避免两个来源复用通用分类 id 时整组吞频道；
 * 4. 任一输入为空时完整保留另一方。
 */
object ChannelCatalogMerger {

    fun merge(
        cctv: List<ChannelCategory>,
        mgtv: List<ChannelCategory>,
    ): List<ChannelCategory> {
        val names = LinkedHashMap<String, String>()
        val buckets = LinkedHashMap<String, MutableList<Channel>>()
        val seenChannelIds = LinkedHashMap<String, MutableSet<String>>()

        appendSection(cctv, names, buckets, seenChannelIds)
        appendSection(mgtv, names, buckets, seenChannelIds)

        return buckets.map { (categoryId, channels) ->
            ChannelCategory(
                id = categoryId,
                name = names[categoryId].orEmpty(),
                channels = channels.toList(),
            )
        }
    }

    private fun appendSection(
        section: List<ChannelCategory>,
        names: MutableMap<String, String>,
        buckets: MutableMap<String, MutableList<Channel>>,
        seenChannelIds: MutableMap<String, MutableSet<String>>,
    ) {
        section.forEach { category ->
            if (category.channels.isEmpty()) return@forEach
            names.putIfAbsent(category.id, category.name)
            val bucket = buckets.getOrPut(category.id) { mutableListOf() }
            val seen = seenChannelIds.getOrPut(category.id) { mutableSetOf() }
            category.channels.forEach { channel ->
                if (seen.add(channel.id)) bucket += channel
            }
        }
    }
}
