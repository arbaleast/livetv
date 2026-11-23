package com.livetv.android.webplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CctvWebStaticResourceCacheTest {

    @Test
    fun `allows GET core player js and wasm from official player hosts`() {
        assertTrue(
            CctvWebStaticCachePolicy.isCacheableStaticAsset(
                method = "GET",
                host = "js.player.cntv.cn",
                path = "/creator/liveplayer.js",
            ),
        )
        assertTrue(
            CctvWebStaticCachePolicy.isCacheableStaticAsset(
                method = "get",
                host = "js.player.cntv.cn",
                path = "/creator/live.worker.wasm",
            ),
        )
        assertTrue(
            CctvWebStaticCachePolicy.isCacheableStaticAsset(
                method = "GET",
                host = "r.img.cctvpic.com",
                path = "/photoAlbum/templet/common/DEPA1486437585241505/configtoolV1.1.js",
            ),
        )
        assertFalse(
            CctvWebStaticCachePolicy.isCacheableStaticAsset(
                method = "GET",
                host = "p.cctvpic.com",
                path = "/photoalbum/templet/player.css",
            ),
        )
    }

    @Test
    fun `rejects non-GET html json auth api and unknown hosts`() {
        assertFalse(
            CctvWebStaticCachePolicy.isCacheableStaticAsset(
                method = "POST",
                host = "js.player.cntv.cn",
                path = "/creator/liveplayer.js",
            ),
        )
        assertFalse(
            CctvWebStaticCachePolicy.isCacheableStaticAsset(
                method = "GET",
                host = "tv.cctv.com",
                path = "/live/cctv1/m/",
            ),
        )
        assertFalse(
            CctvWebStaticCachePolicy.isCacheableStaticAsset(
                method = "GET",
                host = "js.player.cntv.cn",
                path = "/creator/H5player.json",
            ),
        )
        assertFalse(
            CctvWebStaticCachePolicy.isCacheableStaticAsset(
                method = "GET",
                host = "vdnx.live.cntv.cn",
                path = "/api/getLiveUrl.js",
            ),
        )
        assertFalse(
            CctvWebStaticCachePolicy.isCacheableStaticAsset(
                method = "GET",
                host = "time.tv.cctv.com",
                path = "/time.js",
            ),
        )
    }

    @Test
    fun `resolves expected mime types`() {
        assertEquals("application/javascript", CctvWebStaticCachePolicy.mimeTypeForPath("/a/b.js"))
        assertEquals("application/wasm", CctvWebStaticCachePolicy.mimeTypeForPath("/a/b.wasm"))
        assertEquals("text/css", CctvWebStaticCachePolicy.mimeTypeForPath("/a/b.css"))
    }
}
