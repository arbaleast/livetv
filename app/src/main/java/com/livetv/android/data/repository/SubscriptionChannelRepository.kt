package com.livetv.android.data.repository

import com.livetv.android.data.parser.M3uPlaylistParser
import com.livetv.android.data.remote.FetchResult
import com.livetv.android.data.remote.RemotePlaylistFetcher
import com.livetv.android.data.source.ChannelDataSource
import com.livetv.android.data.subscription.SubscriptionStore
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import com.livetv.android.domain.model.RefreshResult
import com.livetv.android.domain.repository.SubscriptionRepository

/**
 * 支持远程订阅的频道仓库（阶段八）。
 *
 * 数据来源优先级：**远程订阅 → 本地缓存 → 内置 assets**，三级兜底：
 *
 * | 场景 | 行为 | 返回 |
 * |---|---|---|
 * | 未配置订阅 URL | 使用内置 assets | [RefreshResult.BuiltinOnly] |
 * | 远程拉取成功且解析出频道 | 落盘缓存 + 原子替换快照 | [RefreshResult.RemoteLoaded] |
 * | 远程失败 / 空内容，且缓存有效 | 使用缓存列表（不清空旧列表） | [RefreshResult.FallbackToCache] |
 * | 远程失败 / 空内容，且无有效缓存 | 使用内置 assets | [RefreshResult.FallbackToBuiltin] |
 *
 * 关键不变式：
 * - **只有远程成功且解析出至少一个频道时才写缓存**，因此空响应 / 损坏内容不会破坏上一次的有效列表；
 * - [getCategories] 与 [findChannelById] 同步、线程安全（快照用 `@Volatile` 发布，写操作用锁串行化）；
 * - 内置频道的聚合规则完全复用 [DefaultChannelRepository]，不另写一套合并逻辑。
 */
class SubscriptionChannelRepository(
    builtinSources: List<ChannelDataSource>,
    private val fetcher: RemotePlaylistFetcher,
    private val store: SubscriptionStore,
    private val parsePlaylist: (String) -> List<ChannelCategory> = { M3uPlaylistParser.parse(it) },
) : SubscriptionRepository {

    /** 内置频道复用既有聚合实现（按分类名合并、去重、跳过失败源）。 */
    private val builtinRepository = DefaultChannelRepository(builtinSources)

    private val lock = Any()

    /** 当前生效的频道快照。 */
    @Volatile
    private var snapshot: List<ChannelCategory> = emptyList()

    init {
        snapshot = initialSnapshot()
    }

    override fun getCategories(): List<ChannelCategory> = snapshot

    override fun findChannelById(channelId: String): Channel? =
        snapshot.asSequence()
            .flatMap { it.channels.asSequence() }
            .firstOrNull { it.id == channelId }

    override fun refreshSubscription(): RefreshResult {
        val url = runCatching { store.getUrl() }.getOrNull()?.trim().orEmpty()
        if (url.isEmpty()) {
            val builtin = builtinCategories()
            synchronized(lock) { snapshot = builtin }
            return RefreshResult.BuiltinOnly(channelCountOf(builtin))
        }

        val result = try {
            fetcher.fetch(url)
        } catch (e: Exception) {
            FetchResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }

        return when (result) {
            is FetchResult.Success -> applyRemoteContent(result.content)
            is FetchResult.HttpError -> fallback("HTTP ${result.code}")
            is FetchResult.NetworkError -> fallback(result.reason)
            is FetchResult.InvalidUrl -> fallback(result.reason)
            FetchResult.SizeExceeded -> fallback("response too large")
        }
    }

    override fun updateSubscriptionUrl(url: String?) {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            runCatching { store.saveUrl(null) }
            runCatching { store.clearPlaylist() }
            val builtin = builtinCategories()
            synchronized(lock) { snapshot = builtin }
            return
        }

        val previous = runCatching { store.getUrl() }.getOrNull()?.trim()
        runCatching { store.saveUrl(trimmed) }
        if (previous != trimmed) {
            // 换了订阅源，旧缓存不再可信，直接作废（但不动当前快照，刷新失败时仍可用）
            runCatching { store.clearPlaylist() }
        }
    }

    override fun getSubscriptionUrl(): String? =
        runCatching { store.getUrl() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** 统计频道总数（不是分类数）。 */
    private fun channelCountOf(categories: List<ChannelCategory>): Int =
        categories.sumOf { it.channels.size }

    /** 远程内容成功：解析出频道才落盘并替换快照。 */
    private fun applyRemoteContent(content: String): RefreshResult {
        val categories = runCatching { parsePlaylist(content) }.getOrElse { emptyList() }
        if (categories.isEmpty()) {
            // 空内容 / 解析失败一律按失败处理，绝不清空当前有效列表
            return fallback("remote playlist has no usable channel")
        }
        runCatching { store.writePlaylist(content) }
        synchronized(lock) { snapshot = categories }
        return RefreshResult.RemoteLoaded(channelCountOf(categories))
    }

    /** 远程失败：优先回退缓存，其次回退内置频道。 */
    private fun fallback(reason: String): RefreshResult {
        val cached = runCatching { store.readPlaylist() }.getOrNull()
        val cachedCategories = cached?.let { runCatching { parsePlaylist(it) }.getOrNull() }.orEmpty()
        if (cachedCategories.isNotEmpty()) {
            synchronized(lock) { snapshot = cachedCategories }
            return RefreshResult.FallbackToCache(channelCountOf(cachedCategories), reason)
        }
        val builtin = builtinCategories()
        synchronized(lock) { snapshot = builtin }
        return RefreshResult.FallbackToBuiltin(channelCountOf(builtin), reason)
    }

    /** 初始化：配置了订阅且缓存可解析出频道时优先用缓存，否则用内置 assets。 */
    private fun initialSnapshot(): List<ChannelCategory> {
        val url = runCatching { store.getUrl() }.getOrNull()?.trim().orEmpty()
        if (url.isNotEmpty()) {
            val cached = runCatching { store.readPlaylist() }.getOrNull()
            val categories = cached?.let { runCatching { parsePlaylist(it) }.getOrNull() }.orEmpty()
            if (categories.isNotEmpty()) return categories
        }
        return builtinCategories()
    }

    private fun builtinCategories(): List<ChannelCategory> = builtinRepository.getCategories()
}
