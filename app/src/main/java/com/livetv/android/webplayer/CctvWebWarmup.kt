package com.livetv.android.webplayer

import android.os.Looper
import android.util.Log
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 手机端首页空闲期的 CCTV 核心域名预建连协调器（纯 Kotlin，可纯 JVM 单测）。
 *
 * 单进程生命周期内最多执行一次：
 * 并发对 4 个官方核心域名执行轻量 DNS + TLS 预建连（超时 <= 2s、不拉取业务数据、静默吞掉异常），
 * 不做 WebView 底座预加载，避免首页提前映射 WebView 代码页抬高静置内存。
 */
internal class CctvWebWarmupCoordinator(
    private val hostPreconnector: (host: String, timeoutMs: Int) -> Unit,
    private val asyncRunner: (Runnable) -> Unit,
    private val hosts: List<String> = DEFAULT_PRECONNECT_HOSTS,
    private val timeoutMs: Int = PRECONNECT_TIMEOUT_MS,
) {
    private val triggered = AtomicBoolean(false)

    /** 在单进程生命周期内最多执行一次；返回 `true` 表示本次实际触发。 */
    fun triggerOnce(): Boolean {
        if (!triggered.compareAndSet(false, true)) return false
        hosts.forEach { host ->
            runCatching {
                asyncRunner(
                    Runnable {
                        runCatching { hostPreconnector(host, timeoutMs) }
                    },
                )
            }
        }
        return true
    }

    companion object {
        val DEFAULT_PRECONNECT_HOSTS: List<String> = listOf(
            "tv.cctv.com",
            "js.player.cntv.cn",
            "time.tv.cctv.com",
            "vdnx.live.cntv.cn",
        )

        /** 单域名 DNS + TCP + TLS 预建连上限（ms），严控 <= 2000ms。 */
        const val PRECONNECT_TIMEOUT_MS: Int = 1_500
    }
}

/**
 * 手机端入口空闲预建连门面：仅由 `MobileMainActivity` 在主线程空闲后调用，绝不触碰 TV 端。
 */
internal object CctvWebWarmup {

    private const val TAG = "CctvWebWarmup"
    private const val HTTPS_PORT = 443

    private val scheduled = AtomicBoolean(false)
    private val threadIndex = AtomicInteger(1)

    private val coordinator: CctvWebWarmupCoordinator by lazy {
        CctvWebWarmupCoordinator(
            hostPreconnector = { host, timeoutMs ->
                preconnectTlsHost(host, timeoutMs)
            },
            asyncRunner = { task ->
                val thread = Thread(task, "cctv-preconnect-${threadIndex.getAndIncrement()}")
                thread.isDaemon = true
                thread.start()
            },
        )
    }

    /**
     * 在主线程消息队列首次空闲时触发一次 4 核心域名并发预建连（单进程仅注册一次，不阻塞首屏渲染）。
     */
    fun scheduleOnIdle() {
        if (!scheduled.compareAndSet(false, true)) return
        val registered = runCatching {
            Looper.myQueue().addIdleHandler {
                coordinator.triggerOnce()
                false
            }
            true
        }.getOrDefault(false)
        if (!registered) {
            scheduled.set(false)
        }
    }

    private fun preconnectTlsHost(host: String, timeoutMs: Int) {
        val startNs = System.nanoTime()
        val factory = SSLSocketFactory.getDefault() as? SSLSocketFactory ?: return
        val socket = factory.createSocket() as? SSLSocket ?: return
        try {
            val params = socket.sslParameters
            params.serverNames = listOf(SNIHostName(host))
            socket.sslParameters = params
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, HTTPS_PORT), timeoutMs)
            socket.startHandshake()
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            Log.i(TAG, "preconnect ok host=$host ms=$elapsedMs")
        } finally {
            runCatching { socket.close() }
        }
    }
}
