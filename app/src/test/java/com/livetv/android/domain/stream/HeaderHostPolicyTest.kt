package com.livetv.android.domain.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HeaderHostPolicy] 的 Host 白名单隔离测试。
 *
 * 这是"MGTV 专用请求头不泄漏给非白名单域名"的策略层守卫。
 */
class HeaderHostPolicyTest {

    private val headers = mapOf(
        "Referer" to "https://www.mgtv.com/",
        "Origin" to "https://www.mgtv.com/",
    )

    @Test
    fun `extracts lowercase host without port`() {
        assertEquals("padal.qing.mgtv.com", HeaderHostPolicy.extractHost("https://padal.qing.mgtv.com:443/a.m3u8"))
        assertEquals("cdn.example.invalid", HeaderHostPolicy.extractHost("HTTP://CDN.Example.Invalid/x"))
        assertEquals("mgtv.com", HeaderHostPolicy.extractHost("https://mgtv.com./x"))
    }

    @Test
    fun `returns null for malformed url`() {
        assertNull(HeaderHostPolicy.extractHost(""))
        assertNull(HeaderHostPolicy.extractHost("not a url"))
        // scheme 不做限制：host 提取只关心 URI 结构，协议过滤由上层负责
        assertEquals("mgtv.com", HeaderHostPolicy.extractHost("ftp://mgtv.com/x"))
    }

    @Test
    fun `empty allowlist means unrestricted for ordinary iptv`() {
        assertTrue(HeaderHostPolicy.isHostAllowed("http://192.168.1.10:8080/live.m3u8", emptySet()))
        assertEquals(
            headers,
            HeaderHostPolicy.filterHeadersForUrl("http://192.168.1.10:8080/live.m3u8", headers, emptySet()),
        )
    }

    @Test
    fun `exact host is allowed`() {
        assertTrue(HeaderHostPolicy.isHostAllowed("https://mgtv.com/x", setOf("mgtv.com")))
    }

    @Test
    fun `subdomain is allowed`() {
        assertTrue(HeaderHostPolicy.isHostAllowed("https://padal.qing.mgtv.com/x.m3u8", setOf("mgtv.com")))
    }

    @Test
    fun `suffix spoofing is blocked`() {
        assertFalse(HeaderHostPolicy.isHostAllowed("https://evilmgtv.com/x.m3u8", setOf("mgtv.com")))
        assertFalse(HeaderHostPolicy.isHostAllowed("https://mgtv.com.evil.net/x.m3u8", setOf("mgtv.com")))
    }

    @Test
    fun `headers are dropped for non allowlisted host`() {
        val filtered = HeaderHostPolicy.filterHeadersForUrl(
            "https://evil.example.net/stream.m3u8",
            headers,
            setOf("mgtv.com"),
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `headers are kept for allowlisted host`() {
        val filtered = HeaderHostPolicy.filterHeadersForUrl(
            "https://padal.qing.mgtv.com/nn_live/x.m3u8",
            headers,
            setOf("mgtv.com"),
        )

        assertEquals(headers, filtered)
    }

    @Test
    fun `empty headers stay empty`() {
        assertTrue(
            HeaderHostPolicy.filterHeadersForUrl("https://mgtv.com/x", emptyMap(), setOf("mgtv.com")).isEmpty(),
        )
    }

    @Test
    fun `malformed url is rejected when allowlist is not empty`() {
        assertFalse(HeaderHostPolicy.isHostAllowed("not a url", setOf("mgtv.com")))
        assertTrue(
            HeaderHostPolicy.filterHeadersForUrl("not a url", headers, setOf("mgtv.com")).isEmpty(),
        )
    }
}
