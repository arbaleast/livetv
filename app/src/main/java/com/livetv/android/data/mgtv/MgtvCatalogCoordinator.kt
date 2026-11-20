package com.livetv.android.data.mgtv

import android.os.Handler
import android.os.Looper
import com.livetv.android.domain.model.ChannelCategory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * MGTV 频道目录的异步加载协调器。
 *
 * 目录接口是公开的，但仍然是网络请求，**不能放在主线程**：
 * 这里用单线程 `ExecutorService` 拉取 + 主线程 `Handler` 回调，
 * 与 `SubscriptionCoordinator` 保持同一套异步模型（不引入 Coroutines）。
 */
class MgtvCatalogCoordinator(
    private val provider: MgtvProvider,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "livetv-mgtv-catalog").apply { isDaemon = true }
    },
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {

    /** 是否已经有成功的目录结果（避免重复拉取）。 */
    @Volatile
    var loaded: Boolean = false
        private set

    /**
     * 加载 MGTV 频道目录。
     *
     * 回调始终在主线程；失败时返回空列表（不抛异常、不影响内置频道）。
     */
    fun load(callback: (List<ChannelCategory>) -> Unit) {
        executor.execute {
            val result = runCatching {
                provider.loadMgtvCategories()
                provider.toDomainCategories()
            }.getOrDefault(emptyList())
            if (result.isNotEmpty()) loaded = true
            mainHandler.post { callback(result) }
        }
    }

    /** 供测试或退出时释放线程。 */
    fun shutdown() {
        executor.shutdownNow()
    }
}
