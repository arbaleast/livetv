package com.livetv.android.data.source

import android.content.res.AssetManager
import com.livetv.android.data.parser.M3uPlaylistParser
import com.livetv.android.domain.model.ChannelCategory

/**
 * 从 APK assets 读取 M3U 播放列表的数据源。
 *
 * 默认读取 `assets/sample.m3u`。读取或解析失败时返回空列表，**不抛异常**，
 * 以免因配置损坏导致首页崩溃。
 */
class LocalM3uChannelSource(
    private val assets: AssetManager,
    private val assetPath: String = DEFAULT_ASSET_PATH,
) : ChannelDataSource {

    override fun loadCategories(): List<ChannelCategory> {
        val content = readAsset() ?: return emptyList()
        return M3uPlaylistParser.parse(content)
    }

    private fun readAsset(): String? = try {
        assets.open(assetPath).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    } catch (e: Exception) {
        // assets 缺失或读取失败 -> 交给上层按"无内容"处理
        null
    }

    companion object {
        const val DEFAULT_ASSET_PATH = "sample.m3u"
    }
}
