package com.livetv.android.webplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `CctvWebUserAgent` 的纯 JVM 单测：仅剔除 `; wv`，保持 UA 其余格式完好。 */
class CctvWebUserAgentTest {

    @Test
    fun `removes the wv token without leaving malformed separators`() {
        val raw = "Mozilla/5.0 (Linux; Android 16; PKG110 Build/BP2A.250905.002; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.86 Mobile Safari/537.36"

        val cleaned = CctvWebUserAgent.sanitize(raw)

        assertFalse(cleaned.contains("; wv"))
        assertFalse("不应留下 '; )'", cleaned.contains("; )"))
        assertFalse("不应有双分号", cleaned.contains(";;"))
        assertTrue(cleaned.contains("Chrome/130.0.6723.86"))
        assertTrue(cleaned.startsWith("Mozilla/5.0 ("))
    }

    @Test
    fun `keeps a plain chrome user agent untouched`() {
        val raw = "Mozilla/5.0 (Linux; Android 16; PKG110) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.6723.86 Mobile Safari/537.36"

        assertEquals(raw, CctvWebUserAgent.sanitize(raw))
    }

    @Test
    fun `handles empty and blank input`() {
        assertEquals("", CctvWebUserAgent.sanitize(""))
        assertEquals("", CctvWebUserAgent.sanitize("   "))
        assertEquals("", CctvWebUserAgent.sanitize(null))
    }

    @Test
    fun `builds desktop chrome ua without android or mobile tokens for cctv3 cctv6 cctv8`() {
        val mobile = "Mozilla/5.0 (Linux; Android 16; PKG110; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.86 Mobile Safari/537.36"

        val desktop = CctvWebUserAgent.resolveForChannel(mobile, "cctv:cctv3")
        assertFalse("桌面 UA 严禁含 Android 标记", desktop.contains("Android", ignoreCase = true))
        assertFalse("桌面 UA 严禁含 Mobile 标记", desktop.contains("Mobile", ignoreCase = true))
        assertTrue("桌面 UA 应保留真实 Chrome 版本", desktop.contains("Chrome/130.0.6723.86"))

        val mobileResolved = CctvWebUserAgent.resolveForChannel(mobile, "cctv:cctv9")
        assertTrue("移动直播台应保持移动 Chrome UA", mobileResolved.contains("Mobile"))
        assertFalse("移动 UA 应剥离 ; wv", mobileResolved.contains("; wv"))
    }
}
