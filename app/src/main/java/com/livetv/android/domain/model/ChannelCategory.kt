package com.livetv.android.domain.model

/**
 * 频道分类（领域模型）。
 *
 * 只包含**有效**频道（[Channel.enabled] 为 true 且播放地址通过安全校验），
 * 因此 `channels` 永远非空——空分类在解析阶段即被丢弃。
 */
data class ChannelCategory(
    val id: String,
    val name: String,
    val channels: List<Channel>,
)
