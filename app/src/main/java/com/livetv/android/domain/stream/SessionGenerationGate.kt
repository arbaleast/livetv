package com.livetv.android.domain.stream

/**
 * 会话代际号（纯 Kotlin，可 JVM 单测）。
 *
 * 用途：播放会话解析被移到后台线程后，用户可能在中途连续切台。
 * 每次发起新的解析就 [next] 一个代际号；回调回到主线程时用 [isCurrent] 校验，
 * 代际号不匹配说明期间已经切到别的频道，**旧结果必须丢弃**，否则会串台。
 */
class SessionGenerationGate {

    private var current = 0

    /** 开新的一代（发起新解析前调用），返回新的代际号。 */
    @Synchronized
    fun next(): Int {
        current += 1
        return current
    }

    /** 该代际号是否仍是最新的一代。 */
    @Synchronized
    fun isCurrent(generation: Int): Boolean = generation == current

    /** 当前代际号（调试/测试用）。 */
    @Synchronized
    fun current(): Int = current
}
