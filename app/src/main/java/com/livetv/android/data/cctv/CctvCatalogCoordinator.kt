package com.livetv.android.data.cctv

import android.os.Handler
import android.os.Looper
import com.livetv.android.domain.model.ChannelCategory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CCTV 频道目录的异步加载协调器（对齐 `MgtvCatalogCoordinator`）。
 *
 * 静态 JSON 虽小，但解析放在后台线程，保证主线程不做磁盘 I/O；
 * 回调始终在主线程，失败返回空列表且不抛异常。
 */
class CctvCatalogCoordinator(
    private val catalog: CctvChannelCatalog,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "livetv-cctv-catalog").apply { isDaemon = true }
    },
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {

    /** 是否已经有成功的目录结果（避免重复加载）。 */
    @Volatile
    var loaded: Boolean = false
        private set

    fun load(callback: (List<ChannelCategory>) -> Unit) {
        executor.execute {
            val result = runCatching { catalog.load() }.getOrDefault(emptyList())
            if (result.isNotEmpty()) loaded = true
            mainHandler.post { callback(result) }
        }
    }

    /** 供测试或退出时释放线程。 */
    fun shutdown() {
        executor.shutdownNow()
    }
}
