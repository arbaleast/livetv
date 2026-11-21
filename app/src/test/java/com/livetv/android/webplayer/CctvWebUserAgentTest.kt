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
}
