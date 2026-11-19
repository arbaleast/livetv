package com.livetv.android.data

import android.os.Handler
import android.os.Looper
import com.livetv.android.domain.model.RefreshResult
import com.livetv.android.domain.repository.SubscriptionRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 订阅刷新协调器：把同步的 [SubscriptionRepository] 拉取动作放到后台线程，
 * 再把结果回传主线程给 UI。
 *
 * 设计约束（阶段八）：
 * - **不引入任何第三方依赖**：异步用 JDK 的 [ExecutorService]，主线程回调用 [Handler]；
 * - 网络 I/O 一律在后台线程，UI 线程只负责渲染；
 * - 回调只在主线程触发，UI 侧无需再切线程。
 *
 * 线程模型：单线程执行器保证刷新串行，不会出现"两个刷新同时改快照"的交错。
 */
class SubscriptionCoordinator(
    private val repository: SubscriptionRepository,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "livetv-subscription")
    },
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {

    /**
     * 后台拉取远程订阅并把结果回传主线程。
     *
     * @param callback 收到刷新结果（主线程）。
     */
    fun refresh(callback: (RefreshResult) -> Unit) {
        executor.execute {
            val result = runCatching { repository.refreshSubscription() }
                .getOrElse { RefreshResult.FallbackToBuiltin(0, it.message ?: it.javaClass.simpleName) }
            mainHandler.post { callback(result) }
        }
    }

    /**
     * 后台更新订阅 URL 并立即刷新一次。
     *
     * @param url `null` 或空白表示清除订阅（回到内置频道）。
     * @param callback 收到刷新结果（主线程）。
     */
    fun updateUrl(url: String?, callback: (RefreshResult) -> Unit) {
        executor.execute {
            // 安全网：仓库实现目前会自行吞掉持久化层的受检异常，
            // 这里仍然捕获任何逃逸出来的异常，避免把崩溃抛到后台线程，
            // 并把失败原因回传给 UI（而不是让用户以为已经保存成功）。
            val updateError = runCatching { repository.updateSubscriptionUrl(url) }.exceptionOrNull()
            val result = if (updateError != null) {
                RefreshResult.FallbackToBuiltin(0, updateError.message ?: updateError.javaClass.simpleName)
            } else {
                runCatching { repository.refreshSubscription() }
                    .getOrElse { RefreshResult.FallbackToBuiltin(0, it.message ?: it.javaClass.simpleName) }
            }
            mainHandler.post { callback(result) }
        }
    }

    /** 释放执行器（应用进程结束时调用即可）。 */
    fun shutdown() {
        executor.shutdownNow()
    }
}
