package com.livetv.android.data.repository

import com.livetv.android.data.remote.FetchResult
import com.livetv.android.data.remote.RemotePlaylistFetcher
import com.livetv.android.data.source.ChannelDataSource
import com.livetv.android.data.subscription.SubscriptionStore
import com.livetv.android.domain.model.Channel
import com.livetv.android.domain.model.ChannelCategory
import com.livetv.android.domain.model.RefreshResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SubscriptionChannelRepository] 的纯 JVM 测试：验证「远程订阅 → 本地缓存 → 内置 assets」三级兜底。
 *
 * 网络与持久化全部用内存假实现，100% 离线、确定性可复现。
 */
class ChannelRepositorySubscriptionTest {

    @Test
    fun `successful remote fetch writes cache and switches snapshot`() {
        val store = FakeStore(storedUrl = "http://example.com/list.m3u")
        val fetcher = FakeFetcher(FetchResult.Success(REMOTE_PLAYLIST))
        val repository = repository(store, fetcher)

        val result = repository.refreshSubscription()

        assertEquals(RefreshResult.RemoteLoaded(2), result)
        assertEquals(1, store.writeCount)
        assertEquals(REMOTE_PLAYLIST, store.playlist)
        assertEquals(listOf("远端"), repository.getCategories().map { it.name })
        assertEquals(2, repository.getCategories().sumOf { it.channels.size })
    }

    @Test
    fun `remote failure falls back to cache and keeps the previous list`() {
        val store = FakeStore(storedUrl = "http://example.com/list.m3u")
        val fetcher = FakeFetcher(FetchResult.Success(REMOTE_PLAYLIST))
        val repository = repository(store, fetcher)
        repository.refreshSubscription()

        fetcher.result = FetchResult.HttpError(403)
        val result = repository.refreshSubscription()

        assertTrue("expected cache fallback but was $result", result is RefreshResult.FallbackToCache)
        assertEquals(2, (result as RefreshResult.FallbackToCache).channelCount)
        assertEquals("HTTP 403", result.reason)
        assertEquals(listOf("远端"), repository.getCategories().map { it.name })
        assertEquals(2, repository.getCategories().sumOf { it.channels.size })
    }

    @Test
    fun `remote failure without cache falls back to builtin`() {
        val store = FakeStore(storedUrl = "http://example.com/list.m3u")
        val fetcher = FakeFetcher(FetchResult.NetworkError("connection reset"))
        val repository = repository(store, fetcher)

        val result = repository.refreshSubscription()

        assertTrue("expected builtin fallback but was $result", result is RefreshResult.FallbackToBuiltin)
        assertEquals(3, (result as RefreshResult.FallbackToBuiltin).channelCount)
        assertEquals("connection reset", result.reason)
        assertEquals(listOf("内置"), repository.getCategories().map { it.name })
    }

    @Test
    fun `empty remote content does not clear the existing list`() {
        val store = FakeStore(storedUrl = "http://example.com/list.m3u")
        val fetcher = FakeFetcher(FetchResult.Success(REMOTE_PLAYLIST))
        val repository = repository(store, fetcher)
        repository.refreshSubscription()
        val writesAfterSuccess = store.writeCount

        fetcher.result = FetchResult.Success("")
        val result = repository.refreshSubscription()

        assertTrue("expected cache fallback but was $result", result is RefreshResult.FallbackToCache)
        assertEquals("remote playlist has no usable channel", (result as RefreshResult.FallbackToCache).reason)
        assertEquals(writesAfterSuccess, store.writeCount)
        assertEquals(listOf("远端"), repository.getCategories().map { it.name })
    }

    @Test
    fun `clearing the url removes the cache and switches back to builtin`() {
        val store = FakeStore(storedUrl = "http://example.com/list.m3u")
        val fetcher = FakeFetcher(FetchResult.Success(REMOTE_PLAYLIST))
        val repository = repository(store, fetcher)
        repository.refreshSubscription()
        assertNotNull(store.playlist)

        val requestsBeforeClear = fetcher.requestedUrls.size
        repository.updateSubscriptionUrl(null)

        assertNull(store.storedUrl)
        assertNull(store.playlist)
        assertEquals(listOf("内置"), repository.getCategories().map { it.name })

        val result = repository.refreshSubscription()

        assertEquals(RefreshResult.BuiltinOnly(3), result)
        assertEquals("clearing the url must stop any remote request", requestsBeforeClear, fetcher.requestedUrls.size)
    }

    @Test
    fun `blank url is treated as clearing the subscription`() {
        val store = FakeStore(storedUrl = "http://example.com/list.m3u", playlist = REMOTE_PLAYLIST)
        val repository = repository(store, FakeFetcher(FetchResult.Success(REMOTE_PLAYLIST)))

        repository.updateSubscriptionUrl("   ")

        assertNull(store.storedUrl)
        assertNull(store.playlist)
        assertEquals(listOf("内置"), repository.getCategories().map { it.name })
    }

    @Test
    fun `initial snapshot prefers a valid local cache`() {
        val store = FakeStore(storedUrl = "http://example.com/list.m3u", playlist = REMOTE_PLAYLIST)
        val repository = repository(store, FakeFetcher(FetchResult.Success(REMOTE_PLAYLIST)))

        assertEquals(listOf("远端"), repository.getCategories().map { it.name })
        assertEquals(2, repository.getCategories().sumOf { it.channels.size })
    }

    @Test
    fun `initial snapshot falls back to builtin without a cache`() {
        val store = FakeStore(storedUrl = "http://example.com/list.m3u")
        val repository = repository(store, FakeFetcher(FetchResult.Success(REMOTE_PLAYLIST)))

        assertEquals(listOf("内置"), repository.getCategories().map { it.name })
    }

    @Test
    fun `changing the url invalidates the previous cache`() {
        val store = FakeStore(storedUrl = "http://example.com/list.m3u", playlist = REMOTE_PLAYLIST)
        val repository = repository(store, FakeFetcher(FetchResult.Success(REMOTE_PLAYLIST)))

        repository.updateSubscriptionUrl("http://other.example.com/list.m3u")

        assertEquals("http://other.example.com/list.m3u", store.storedUrl)
        assertNull(store.playlist)
    }

    @Test
    fun `channel can be found by id from the active snapshot`() {
        val store = FakeStore(storedUrl = "http://example.com/list.m3u")
        val repository = repository(store, FakeFetcher(FetchResult.Success(REMOTE_PLAYLIST)))
        repository.refreshSubscription()

        val channel = repository.findChannelById("r1")

        assertNotNull(channel)
        assertEquals("远端频道一", channel?.name)
        assertEquals("http://example.com/remote1.ts", channel?.streamUrl)
        assertNull(repository.findChannelById("missing"))
    }

    @Test
    fun `subscription url is exposed trimmed`() {
        val store = FakeStore(storedUrl = "  http://example.com/list.m3u  ")
        val repository = repository(store, FakeFetcher(FetchResult.Success(REMOTE_PLAYLIST)))

        assertEquals("http://example.com/list.m3u", repository.getSubscriptionUrl())
    }

    private fun repository(store: SubscriptionStore, fetcher: RemotePlaylistFetcher) =
        SubscriptionChannelRepository(
            builtinSources = listOf(BuiltinSource()),
            fetcher = fetcher,
            store = store,
        )

    private class FakeFetcher(var result: FetchResult) : RemotePlaylistFetcher {
        val requestedUrls = mutableListOf<String>()

        override fun fetch(url: String): FetchResult {
            requestedUrls += url
            return result
        }
    }

    private class FakeStore(
        var storedUrl: String? = null,
        var playlist: String? = null,
    ) : SubscriptionStore {
        var writeCount = 0

        override fun getUrl(): String? = storedUrl

        override fun saveUrl(url: String?) {
            this.storedUrl = url
        }

        override fun readPlaylist(): String? = playlist

        override fun writePlaylist(content: String) {
            playlist = content
            writeCount++
        }

        override fun clearPlaylist() {
            playlist = null
        }
    }

    private class BuiltinSource : ChannelDataSource {
        override fun loadCategories(): List<ChannelCategory> = listOf(
            ChannelCategory(
                id = "builtin",
                name = "内置",
                channels = listOf(
                    Channel(id = "b1", name = "内置一", description = "", streamUrl = "http://example.com/b1.ts"),
                    Channel(id = "b2", name = "内置二", description = "", streamUrl = "http://example.com/b2.ts"),
                    Channel(id = "b3", name = "内置三", description = "", streamUrl = "http://example.com/b3.ts"),
                ),
            ),
        )
    }

    private companion object {
        val REMOTE_PLAYLIST = """
            #EXTM3U
            #EXTINF:-1 tvg-id="r1" group-title="远端",远端频道一
            http://example.com/remote1.ts
            #EXTINF:-1 tvg-id="r2" group-title="远端",远端频道二
            http://example.com/remote2.ts
        """.trimIndent()
    }
}
