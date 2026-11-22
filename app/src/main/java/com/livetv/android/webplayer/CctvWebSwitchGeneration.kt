package com.livetv.android.webplayer

/**
 * CCTV 切台代次防竞态控制器（纯 Kotlin，可纯 JVM 单测）。
 *
 * 每次发起频道加载/切台/刷新或销毁 WebView 时递增代次，
 * 所有异步回调（首帧轮询、超时撤幕、错误回退、PixelCopy 尾帧捕获、4K 播完检测）
 * 均通过 [isCurrent] 校验，旧代次回调一律丢弃，彻底消除快速连点切台竞态。
 */
internal class CctvWebSwitchGeneration(initial: Long = 0L) {

    @Volatile
    private var generation: Long = initial

    val current: Long
        get() = generation

    @Synchronized
    fun nextGeneration(): Long {
        generation += 1L
        return generation
    }

    fun isCurrent(candidate: Long): Boolean = candidate > 0L && candidate == generation
}
