package com.livetv.android.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `PlaybackErrorMapper` 的纯 JVM 单元测试。
 *
 * 覆盖 HTTP 状态码、超时、网络、源解析与未知原因的映射。
 */
class PlaybackErrorMapperTest {

    @Test
    fun `http status cause maps to http status error`() {
        val error = PlaybackErrorMapper.map(404, PlaybackErrorMapper.CauseKind.HTTP_STATUS)

        assertEquals(PlaybackError.HttpStatus(404), error)
    }

    @Test
    fun `http status cause without code maps to unknown`() {
        val error = PlaybackErrorMapper.map(null, PlaybackErrorMapper.CauseKind.HTTP_STATUS)

        assertEquals(PlaybackError.Unknown(), error)
    }

    @Test
    fun `timeout cause maps to timeout`() {
        val error = PlaybackErrorMapper.map(null, PlaybackErrorMapper.CauseKind.TIMEOUT)

        assertEquals(PlaybackError.Timeout, error)
    }

    @Test
    fun `network cause maps to network error`() {
        val error = PlaybackErrorMapper.map(null, PlaybackErrorMapper.CauseKind.NETWORK)

        assertEquals(PlaybackError.Network, error)
    }

    @Test
    fun `source parsing cause maps to source parsing error`() {
        val error = PlaybackErrorMapper.map(null, PlaybackErrorMapper.CauseKind.SOURCE_PARSING)

        assertEquals(PlaybackError.SourceParsing, error)
    }

    @Test
    fun `unknown cause maps to unknown error`() {
        val error = PlaybackErrorMapper.map(null, PlaybackErrorMapper.CauseKind.UNKNOWN)

        assertEquals(PlaybackError.Unknown(), error)
    }

    @Test
    fun `cause kind decides the mapping and the code is only used for http status`() {
        // HTTP_STATUS + 有码 -> HttpStatus；其它 cause + 有码 -> 按 cause 映射（码被忽略）
        assertEquals(
            PlaybackError.Network,
            PlaybackErrorMapper.map(500, PlaybackErrorMapper.CauseKind.NETWORK),
        )
    }
}
