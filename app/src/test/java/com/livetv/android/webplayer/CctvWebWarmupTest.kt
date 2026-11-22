package com.livetv.android.webplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * [CctvWebWarmupCoordinator] 与 [CctvWebWarmup] 的纯 JVM 单测与隔离守卫。
 *
 * 覆盖：
 * 1. 单进程生命周期幂等（多次/并发触发仅执行一次）；
 * 2. 仅预建连 4 个官方核心域名且超时 <= 2000ms；
 * 3. 任意域名预建连或调度器抛异常时静默吞掉，不崩溃、不阻断其余主机；
 * 4. 严格限定手机端入口，绝不触碰 Android TV 代码，且严禁在首页预加载 WebView 底座代码页。
 */
class CctvWebWarmupTest {

    @Test
    fun `triggers four official hosts preconnect only once`() {
        val connectedHosts = mutableListOf<Pair<String, Int>>()

        val coordinator = CctvWebWarmupCoordinator(
            hostPreconnector = { host, timeoutMs -> connectedHosts += host to timeoutMs },
            asyncRunner = { task -> task.run() },
        )

        assertTrue(coordinator.triggerOnce())
        assertFalse("第二次触发应直接忽略", coordinator.triggerOnce())

        assertEquals(
            listOf(
                "tv.cctv.com",
                "js.player.cntv.cn",
                "time.tv.cctv.com",
                "vdnx.live.cntv.cn",
            ),
            connectedHosts.map { it.first },
        )
        assertTrue(
            "所有预建连超时必须 <= 2000ms",
            connectedHosts.all { (_, timeoutMs) -> timeoutMs in 1..2_000 },
        )
    }

    @Test
    fun `concurrent triggerOnce executes preconnect exactly once`() {
        val hostCalls = AtomicInteger(0)
        val coordinator = CctvWebWarmupCoordinator(
            hostPreconnector = { _, _ -> hostCalls.incrementAndGet() },
            asyncRunner = { task -> task.run() },
        )

        val threadCount = 8
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)
        val winners = AtomicInteger(0)

        repeat(threadCount) {
            Thread {
                startGate.await()
                if (coordinator.triggerOnce()) {
                    winners.incrementAndGet()
                }
                doneGate.countDown()
            }.start()
        }

        startGate.countDown()
        doneGate.await()

        assertEquals(1, winners.get())
        assertEquals(4, hostCalls.get())
    }

    @Test
    fun `silently swallows exceptions from host preconnect`() {
        val attempted = Collections.synchronizedList(mutableListOf<String>())
        val coordinator = CctvWebWarmupCoordinator(
            hostPreconnector = { host, _ ->
                attempted += host
                if (host == "tv.cctv.com" || host == "vdnx.live.cntv.cn") {
                    throw RuntimeException("simulated timeout")
                }
            },
            asyncRunner = { task -> task.run() },
        )

        assertTrue(coordinator.triggerOnce())
        assertEquals(4, attempted.size)
        assertEquals(CctvWebWarmupCoordinator.DEFAULT_PRECONNECT_HOSTS, attempted)
    }

    @Test
    fun `silently swallows async runner rejection without crashing`() {
        val coordinator = CctvWebWarmupCoordinator(
            hostPreconnector = { _, _ -> },
            asyncRunner = { throw IllegalStateException("executor rejected") },
        )

        assertTrue(coordinator.triggerOnce())
        assertFalse(coordinator.triggerOnce())
    }

    @Test
    fun `warmup is strictly mobile-only and never loads WebView base on home`() {
        val warmupFile = File("src/main/java/com/livetv/android/webplayer/CctvWebWarmup.kt")
        assertTrue("应存在 CctvWebWarmup.kt", warmupFile.isFile)
        val warmupSrc = warmupFile.readText(Charsets.UTF_8)
        assertTrue("应通过 Looper.myQueue().addIdleHandler 在空闲态触发", warmupSrc.contains("addIdleHandler"))
        assertFalse("严禁在预热阶段构造常驻 WebView 实例", warmupSrc.contains("WebView("))
        assertFalse(
            "严禁调用 WebSettings.getDefaultUserAgent（避免首页提前映射 WebView 代码页）",
            warmupSrc.contains("WebSettings.getDefaultUserAgent"),
        )
        assertFalse(
            "严禁调用 CookieManager.getInstance（避免首页提前映射 WebView 代码页）",
            warmupSrc.contains("CookieManager.getInstance"),
        )
        assertFalse(
            "严禁在首页预热引入 android.webkit 依赖",
            warmupSrc.contains("android.webkit."),
        )

        val mobileMain = File("src/main/java/com/livetv/android/ui/mobile/MobileMainActivity.kt")
            .readText(Charsets.UTF_8)
        assertTrue("MobileMainActivity 应在入口注册空闲预建连", mobileMain.contains("CctvWebWarmup.scheduleOnIdle"))

        val tvFiles = listOf(
            "src/main/java/com/livetv/android/MainActivity.kt",
            "src/main/java/com/livetv/android/ui/MainBrowseFragment.kt",
            "src/main/java/com/livetv/android/ui/PlayerActivity.kt",
        )
        tvFiles.forEach { path ->
            val src = File(path).readText(Charsets.UTF_8)
            assertFalse("$path 严禁调用或引用 CctvWebWarmup", src.contains("CctvWebWarmup"))
        }
    }
}
