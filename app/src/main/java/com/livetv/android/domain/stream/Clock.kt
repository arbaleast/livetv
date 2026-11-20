package com.livetv.android.domain.stream

/**
 * 可注入时钟接口，供动态流有效期校验与单元测试确定性模拟时间推进。
 */
fun interface Clock {
    fun nowMs(): Long
}

/** 默认系统墙钟实现。 */
object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** 单调时钟实现（不受系统时间回拨/NTP 跳变影响，适合相对 TTL 过期判定）。 */
object MonotonicClock : Clock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
