package com.livetv.android.player

import com.livetv.android.domain.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RetryPolicy` 的纯 JVM 单元测试。
 *
 * 覆盖：主源重试与恢复、Failover 到备用源、全部耗尽、手动重置、切台重置。
 */
class RetryPolicyTest {

    private fun channel(
        main: String = "https://main.example.com/live.m3u8",
        backups: List<String> = emptyList(),
    ) = Channel(
        id = "c1",
        name = "频道",
        description = "",
        streamUrl = main,
        backupUrls = backups,
    )

    // ---------- 候选源生成 ----------

    @Test
    fun `candidate urls put main source first then backups`() {
        val candidates = RetryPolicy.candidateUrls(
            channel(backups = listOf("https://b1.example.com/a.m3u8", "https://b2.example.com/b.m3u8"))
        )

        assertEquals(
            listOf(
                "https://main.example.com/live.m3u8",
                "https://b1.example.com/a.m3u8",
                "https://b2.example.com/b.m3u8",
            ),
            candidates,
        )
    }

    @Test
    fun `candidate urls drop illegal protocols`() {
        val candidates = RetryPolicy.candidateUrls(
            channel(
                main = "file:///etc/passwd",
                backups = listOf("javascript:alert(1)", "rtmp://example.com/live", "http://ok.example.com/a.m3u8"),
            )
        )

        assertEquals(listOf("http://ok.example.com/a.m3u8"), candidates)
    }

    @Test
    fun `candidate urls are empty when no valid url exists`() {
        assertEquals(0, RetryPolicy.candidateUrls(channel(main = "", backups = listOf("file:///x"))).size)
    }

    // ---------- 主源重试与恢复 ----------

    @Test
    fun `first failure schedules retry after 1500ms`() {
        val state = RetryPolicy.RetryState(sourceIndex = 0, retryCount = 0, candidateCount = 2)

        val outcome = RetryPolicy.onFailure(state, PlaybackError.Network)

        assertEquals(RetryPolicy.RetryOutcome.Retry(1500L), outcome)
    }

    @Test
    fun `second failure schedules retry after 3000ms`() {
        val state = RetryPolicy.RetryState(sourceIndex = 0, retryCount = 1, candidateCount = 2)

        val outcome = RetryPolicy.onFailure(state, PlaybackError.Network)

        assertEquals(RetryPolicy.RetryOutcome.Retry(3000L), outcome)
    }

    @Test
    fun `success resets retry count and keeps current source`() {
        val state = RetryPolicy.RetryState(sourceIndex = 1, retryCount = 2, candidateCount = 3)

        val after = RetryPolicy.onSuccess(state)

        assertEquals(0, after.retryCount)
        assertEquals(1, after.sourceIndex)
        assertEquals(3, after.candidateCount)
    }

    // ---------- Failover ----------

    @Test
    fun `exhausted main source switches to first backup`() {
        val state = RetryPolicy.RetryState(sourceIndex = 0, retryCount = 2, candidateCount = 3)

        val outcome = RetryPolicy.onFailure(state, PlaybackError.Timeout)

        assertEquals(RetryPolicy.RetryOutcome.SwitchSource(1), outcome)
    }

    @Test
    fun `exhausted first backup switches to second backup`() {
        val state = RetryPolicy.RetryState(sourceIndex = 1, retryCount = 2, candidateCount = 3)

        val outcome = RetryPolicy.onFailure(state, PlaybackError.Timeout)

        assertEquals(RetryPolicy.RetryOutcome.SwitchSource(2), outcome)
    }

    @Test
    fun `all sources exhausted yields fatal`() {
        val state = RetryPolicy.RetryState(sourceIndex = 2, retryCount = 2, candidateCount = 3)

        val outcome = RetryPolicy.onFailure(state, PlaybackError.Timeout)

        assertEquals(RetryPolicy.RetryOutcome.Fatal, outcome)
    }

    @Test
    fun `single source without backup becomes fatal after max retries`() {
        val state = RetryPolicy.RetryState(sourceIndex = 0, retryCount = 2, candidateCount = 1)

        val outcome = RetryPolicy.onFailure(state, PlaybackError.Network)

        assertEquals(RetryPolicy.RetryOutcome.Fatal, outcome)
    }

    @Test
    fun `max retries per source is two meaning three attempts in total`() {
        var state = RetryPolicy.RetryState(sourceIndex = 0, retryCount = 0, candidateCount = 1)

        val first = RetryPolicy.onFailure(state, PlaybackError.Network)
        assertEquals(RetryPolicy.RetryOutcome.Retry(1500L), first)

        state = state.copy(retryCount = 1)
        val second = RetryPolicy.onFailure(state, PlaybackError.Network)
        assertEquals(RetryPolicy.RetryOutcome.Retry(3000L), second)

        state = state.copy(retryCount = 2)
        val third = RetryPolicy.onFailure(state, PlaybackError.Network)
        assertEquals(RetryPolicy.RetryOutcome.Fatal, third)
    }

    // ---------- 手动重置 ----------

    @Test
    fun `manual reset returns to main source with cleared counter`() {
        val fatal = RetryPolicy.RetryState(sourceIndex = 2, retryCount = 2, candidateCount = 3)

        val after = RetryPolicy.onManualReset(fatal)

        assertEquals(0, after.sourceIndex)
        assertEquals(0, after.retryCount)
        assertEquals(3, after.candidateCount)
    }

    @Test
    fun `after manual reset the channel can be retried again`() {
        val fatal = RetryPolicy.RetryState(sourceIndex = 2, retryCount = 2, candidateCount = 3)
        val reset = RetryPolicy.onManualReset(fatal)

        val outcome = RetryPolicy.onFailure(reset, PlaybackError.Network)

        assertEquals(RetryPolicy.RetryOutcome.Retry(1500L), outcome)
    }

    // ---------- 切台 ----------

    @Test
    fun `channel switch discards previous retry state`() {
        val after = RetryPolicy.onChannelSwitched(candidateCount = 2)

        assertEquals(0, after.sourceIndex)
        assertEquals(0, after.retryCount)
        assertEquals(2, after.candidateCount)
    }

    @Test
    fun `retry state knows whether a next source exists`() {
        assertTrue(RetryPolicy.RetryState(sourceIndex = 0, candidateCount = 2).hasNextSource)
        assertTrue(!RetryPolicy.RetryState(sourceIndex = 1, candidateCount = 2).hasNextSource)
    }

    // ---------- 快速失败（401 / 403 / 429） ----------

    @Test
    fun `fast fail statuses are exactly 401 403 and 429`() {
        assertTrue(RetryPolicy.isFastFailHttpStatus(401))
        assertTrue(RetryPolicy.isFastFailHttpStatus(403))
        assertTrue(RetryPolicy.isFastFailHttpStatus(429))
        assertTrue(!RetryPolicy.isFastFailHttpStatus(400))
        assertTrue(!RetryPolicy.isFastFailHttpStatus(404))
        assertTrue(!RetryPolicy.isFastFailHttpStatus(500))
        assertTrue(!RetryPolicy.isFastFailHttpStatus(503))
    }

    @Test
    fun `http 401 does not retry the current source`() {
        val state = RetryPolicy.RetryState(sourceIndex = 0, retryCount = 0, candidateCount = 2)

        val outcome = RetryPolicy.onFailure(state, PlaybackError.HttpStatus(401))

        assertEquals(RetryPolicy.RetryOutcome.SwitchSource(1), outcome)
    }

    @Test
    fun `http 403 does not retry the current source`() {
        val state = RetryPolicy.RetryState(sourceIndex = 0, retryCount = 0, candidateCount = 2)

        val outcome = RetryPolicy.onFailure(state, PlaybackError.HttpStatus(403))

        assertEquals(RetryPolicy.RetryOutcome.SwitchSource(1), outcome)
    }

    @Test
    fun `http 429 does not retry the current source`() {
        val state = RetryPolicy.RetryState(sourceIndex = 0, retryCount = 0, candidateCount = 2)

        val outcome = RetryPolicy.onFailure(state, PlaybackError.HttpStatus(429))

        assertEquals(RetryPolicy.RetryOutcome.SwitchSource(1), outcome)
    }

    @Test
    fun `fast fail switches to the backup even when retries are already exhausted`() {
        val state = RetryPolicy.RetryState(sourceIndex = 0, retryCount = 2, candidateCount = 2)

        val outcome = RetryPolicy.onFailure(state, PlaybackError.HttpStatus(403))

        assertEquals(RetryPolicy.RetryOutcome.SwitchSource(1), outcome)
    }

    @Test
    fun `fast fail without a backup source becomes fatal immediately`() {
        val state = RetryPolicy.RetryState(sourceIndex = 0, retryCount = 0, candidateCount = 1)

        val outcome = RetryPolicy.onFailure(state, PlaybackError.HttpStatus(429))

        assertEquals(RetryPolicy.RetryOutcome.Fatal, outcome)
    }

    @Test
    fun `fast fail does not consume the retry budget of the next source`() {
        val state = RetryPolicy.RetryState(sourceIndex = 0, retryCount = 0, candidateCount = 2)

        val outcome = RetryPolicy.onFailure(state, PlaybackError.HttpStatus(403))

        val switched = RetryPolicy.RetryState(
            sourceIndex = (outcome as RetryPolicy.RetryOutcome.SwitchSource).index,
            retryCount = 0,
            candidateCount = state.candidateCount,
        )
        assertEquals(RetryPolicy.RetryOutcome.Retry(1500L), RetryPolicy.onFailure(switched, PlaybackError.Network))
    }

    @Test
    fun `http 500 keeps the normal backoff retry`() {
        val state = RetryPolicy.RetryState(sourceIndex = 0, retryCount = 0, candidateCount = 2)

        val outcome = RetryPolicy.onFailure(state, PlaybackError.HttpStatus(500))

        assertEquals(RetryPolicy.RetryOutcome.Retry(1500L), outcome)
    }

    @Test
    fun `http 503 keeps the normal backoff retry`() {
        val state = RetryPolicy.RetryState(sourceIndex = 0, retryCount = 1, candidateCount = 2)

        val outcome = RetryPolicy.onFailure(state, PlaybackError.HttpStatus(503))

        assertEquals(RetryPolicy.RetryOutcome.Retry(3000L), outcome)
    }

    @Test
    fun `network and timeout are not fast fail errors`() {
        assertTrue(!RetryPolicy.isFastFail(PlaybackError.Network))
        assertTrue(!RetryPolicy.isFastFail(PlaybackError.Timeout))
        assertTrue(!RetryPolicy.isFastFail(PlaybackError.SourceParsing))
        assertTrue(RetryPolicy.isFastFail(PlaybackError.HttpStatus(403)))
    }
}
