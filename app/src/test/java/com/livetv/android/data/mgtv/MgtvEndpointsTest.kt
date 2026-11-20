package com.livetv.android.data.mgtv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MgtvEndpoints] 查询参数构造 + 合规"无硬编码凭据"守卫测试。
 */
class MgtvEndpointsTest {

    /** 明显人造的占位设备标识，仅用于验证参数传递链路，绝非任何真实设备的 UUID。 */
    private val sanitizedDeviceId = "00000000-0000-0000-0000-000000000000"

    @Test
    fun `catalog url carries only public parameters`() {
        val url = MgtvEndpoints.catalogUrl()

        assertTrue(url.startsWith("https://mpplive.api.mgtv.com/v1/epg/turnplay/getLiveAssetCategoryList?"))
        assertTrue(url.contains("version=PCweb_1.0"))
        assertTrue(url.contains("platform=4"))
        assertTrue(url.contains("media_asset_id=TVStationAll"))
        assertTrue(url.contains("buss_id=2000001"))
        assertTrue(url.contains("_support=10000000"))
        assertTrue(url.contains("callback=livecallback"))
        assertFalse("目录接口不应携带签名", url.contains("sign="))
        assertFalse("目录接口不应携带设备标识", url.contains("did="))
    }

    @Test
    fun `catalog url can drop the jsonp callback`() {
        val url = MgtvEndpoints.catalogUrl(jsonp = false)

        assertFalse(url.contains("callback="))
        assertTrue(url.contains("media_asset_id=TVStationAll"))
    }

    @Test
    fun `source params contain every signed field`() {
        val params = MgtvEndpoints.sourceParams(
            cameraId = "287",
            deviceId = sanitizedDeviceId,
            timestampMs = 1700000000000L,
        )

        assertEquals("287", params["cameraId"])
        assertEquals("", params["activityId"])
        assertEquals("4", params["platform"])
        assertEquals("imgotv-pch5-1.2.3", params["appVersion"])
        assertEquals("pcweb", params["clientKey"])
        assertEquals("1", params["auth_mode"])
        assertEquals("", params["local_definition"])
        assertEquals("2", params["init_definition"])
        assertEquals("", params["uid"])
        assertEquals("", params["token"])
        assertEquals("h264", params["hdts"])
        assertEquals("1", params["lls"])
        assertEquals("1", params["supportFlv"])
        assertEquals(sanitizedDeviceId, params["did"])
        assertEquals(sanitizedDeviceId, params["deviceId"])
        assertEquals("1700000000000", params["_t"])
        // 签名前不得包含这两个键
        assertFalse(params.containsKey("sign"))
        assertFalse(params.containsKey("_support"))
    }

    @Test
    fun `source params honour preferred definition`() {
        val params = MgtvEndpoints.sourceParams("287", sanitizedDeviceId, 1L, preferredDefinition = "3")

        assertEquals("3", params["init_definition"])
    }

    @Test
    fun `source url appends support and sign after signed params`() {
        val params = MgtvEndpoints.sourceParams("287", sanitizedDeviceId, 1700000000000L)
        val url = MgtvEndpoints.sourceUrl(params, sign = "SANITIZED_SIGN_PLACEHOLDER")

        assertTrue(url.startsWith("https://pwlp.bz.mgtv.com/v1/live/source?"))
        assertTrue(url.contains("cameraId=287"))
        assertTrue(url.contains("did=$sanitizedDeviceId"))
        assertTrue(url.contains("deviceId=$sanitizedDeviceId"))
        assertTrue(url.contains("_t=1700000000000"))
        assertTrue(url.contains("_support=10000000"))
        assertTrue(url.contains("sign=SANITIZED_SIGN_PLACEHOLDER"))
        // _support 与 sign 必须排在所有参与签名的参数之后
        assertTrue(
            url.indexOf("_support=10000000") > url.indexOf("_t="),
        )
    }

    @Test
    fun `mgtv production sources contain no hardcoded credential or signed url`() {
        val dir = File("src/main/java/com/livetv/android/data/mgtv")
        assertTrue("MGTV 数据层目录应存在: ${dir.absolutePath}", dir.exists())

        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".kt") }.orEmpty()
        assertTrue("应至少存在 MGTV 数据层源文件", files.isNotEmpty())

        val signLike = Regex("(?<![0-9A-Za-z])[0-9a-f]{32}(?![0-9A-Za-z])")
        val ipLike = Regex("(?<![0-9.])\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?![0-9.])")
        val forbiddenTokens = listOf("ftc=", "sftc=", "Cookie:", "macid=", "uip=")

        files.forEach { file ->
            val source = file.readText(Charsets.UTF_8)
            assertFalse(
                "${file.name} 不应硬编码疑似 sign（32 位十六进制字面量）",
                signLike.containsMatchIn(source),
            )
            assertFalse(
                "${file.name} 不应硬编码 IP 地址",
                ipLike.containsMatchIn(source),
            )
            forbiddenTokens.forEach { token ->
                assertFalse(
                    "${file.name} 不应出现敏感 token：$token",
                    source.contains(token),
                )
            }
        }
    }
}
