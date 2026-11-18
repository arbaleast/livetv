package com.livetv.android.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `PlaybackReducer` 的纯 JVM 单元测试。
 *
 * 覆盖合法状态路径与非法转换（尤其是 Error 态忽略后续回调）。
 */
class PlaybackReducerTest {

    @Test
    fun `idle to loading on prepare`() {
        val state = PlaybackReducer.reduce(PlaybackState.Idle, PlaybackEvent.Prepare)

        assertEquals(PlaybackState.Loading, state)
    }

    @Test
    fun `loading to buffering on buffer event`() {
        val state = PlaybackReducer.reduce(PlaybackState.Loading, PlaybackEvent.Buffering)

        assertEquals(PlaybackState.Buffering, state)
    }

    @Test
    fun `buffering to playing`() {
        val state = PlaybackReducer.reduce(PlaybackState.Buffering, PlaybackEvent.Playing)

        assertEquals(PlaybackState.Playing, state)
    }

    @Test
    fun `playing to paused`() {
        val state = PlaybackReducer.reduce(PlaybackState.Playing, PlaybackEvent.Paused)

        assertEquals(PlaybackState.Paused, state)
    }

    @Test
    fun `playing to buffering when data runs out`() {
        val state = PlaybackReducer.reduce(PlaybackState.Playing, PlaybackEvent.Buffering)

        assertEquals(PlaybackState.Buffering, state)
    }

    @Test
    fun `idle ignores playing event`() {
        val state = PlaybackReducer.reduce(PlaybackState.Idle, PlaybackEvent.Playing)

        assertEquals(PlaybackState.Idle, state)
    }

    @Test
    fun `idle ignores buffering event`() {
        val state = PlaybackReducer.reduce(PlaybackState.Idle, PlaybackEvent.Buffering)

        assertEquals(PlaybackState.Idle, state)
    }

    @Test
    fun `error state ignores buffering event`() {
        val error = PlaybackState.Error(PlaybackError.Network, fatal = false)

        val state = PlaybackReducer.reduce(error, PlaybackEvent.Buffering)

        assertEquals(error, state)
    }

    @Test
    fun `error state ignores playing event`() {
        val error = PlaybackState.Error(PlaybackError.Network, fatal = false)

        val state = PlaybackReducer.reduce(error, PlaybackEvent.Playing)

        assertEquals(error, state)
    }

    @Test
    fun `failure produces non fatal error state`() {
        val state = PlaybackReducer.reduce(PlaybackState.Playing, PlaybackEvent.Failed(PlaybackError.Timeout))

        assertEquals(PlaybackState.Error(PlaybackError.Timeout, fatal = false), state)
    }

    @Test
    fun `fatal event produces fatal error state`() {
        val state = PlaybackReducer.reduce(PlaybackState.Playing, PlaybackEvent.Fatal(PlaybackError.SourceParsing))

        assertEquals(PlaybackState.Error(PlaybackError.SourceParsing, fatal = true), state)
    }

    @Test
    fun `error state keeps the first error instead of being overwritten`() {
        val first = PlaybackReducer.reduce(PlaybackState.Playing, PlaybackEvent.Failed(PlaybackError.Network))

        val second = PlaybackReducer.reduce(first, PlaybackEvent.Failed(PlaybackError.Timeout))

        assertEquals(first, second)
        assertEquals(PlaybackState.Error(PlaybackError.Network, fatal = false), second)
    }

    @Test
    fun `manual reset turns error into loading`() {
        val error = PlaybackState.Error(PlaybackError.Network, fatal = true)

        val state = PlaybackReducer.reduce(error, PlaybackEvent.ManualReset)

        assertEquals(PlaybackState.Loading, state)
    }

    @Test
    fun `manual reset on playing state keeps state unchanged`() {
        val state = PlaybackReducer.reduce(PlaybackState.Playing, PlaybackEvent.ManualReset)

        assertEquals(PlaybackState.Playing, state)
    }

    @Test
    fun `released returns to idle`() {
        val state = PlaybackReducer.reduce(PlaybackState.Playing, PlaybackEvent.Released)

        assertEquals(PlaybackState.Idle, state)
    }
}
