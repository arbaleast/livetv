package com.livetv.android.ui.mobile

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import java.lang.ref.WeakReference

/**
 * 手机端跨引擎无感切台与单实例防双音轨协调门面（Android 层薄封装，仅作用于手机触控端）。
 *
 * - 跨引擎切台：附加 `FLAG_ACTIVITY_NO_ANIMATION` + `ActivityOptions(0, 0)` + `overrideActivityTransition(0, 0)`，
 *   并透传当前屏幕方向，消除 MGTV ↔ CCTV 切换时的系统进退场动画与横竖屏回弹；
 * - 跨引擎 PiP 单实例保护：当某一引擎悬浮在小窗中、用户从首页打开另一引擎频道时，
 *   立即清理并关闭悬浮中的旧播放页，杜绝双实例双音轨叠音。
 */
internal object MobilePlayerTransitionHelper {

    private var activePlayerRef: WeakReference<Activity>? = null
    private var activeDismissAction: (() -> Unit)? = null
    private var stashedLastFrameBitmap: Bitmap? = null

    /**
     * 跨引擎（MGTV ↔ CCTV）切台前暂存已截取的上一台纯净尾帧 Bitmap；
     * 若存在未消费的旧位图则立即回收，防止内存泄漏。
     */
    @Synchronized
    fun stashCrossEngineLastFrame(bitmap: Bitmap?) {
        val old = stashedLastFrameBitmap
        if (old != null && old !== bitmap && !old.isRecycled) {
            old.recycle()
        }
        stashedLastFrameBitmap = bitmap?.takeIf { !it.isRecycled }
    }

    /**
     * 目标播放页（onCreate / onNewIntent）消费跨引擎尾帧（单次取走即置空，所有权移交目标页在撤幕时回收）。
     */
    @Synchronized
    fun consumeCrossEngineLastFrame(intent: Intent?): Bitmap? {
        val fromCross = intent?.getBooleanExtra(PlayerTransitionAndPipPolicy.EXTRA_FROM_CROSS_SWITCH, false) == true
        val bmp = stashedLastFrameBitmap
        stashedLastFrameBitmap = null
        if (!fromCross) {
            if (bmp != null && !bmp.isRecycled) bmp.recycle()
            return null
        }
        return bmp?.takeIf { !it.isRecycled }
    }

    @Synchronized
    fun clearStashedCrossEngineLastFrame() {
        stashedLastFrameBitmap?.let { if (!it.isRecycled) it.recycle() }
        stashedLastFrameBitmap = null
    }

    fun supportsPictureInPicture(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun resolveInitialOrientation(isTvDevice: Boolean, intent: Intent?): Int {
        val preferred = if (intent?.hasExtra(PlayerTransitionAndPipPolicy.EXTRA_PREFERRED_ORIENTATION) == true) {
            intent.getIntExtra(
                PlayerTransitionAndPipPolicy.EXTRA_PREFERRED_ORIENTATION,
                PlayerTransitionAndPipPolicy.ORIENTATION_UNSPECIFIED,
            )
        } else {
            null
        }
        return PlayerTransitionAndPipPolicy.resolveInitialOrientation(
            isTvDevice = isTvDevice,
            preferredOrientation = preferred,
        )
    }

    fun startSeamlessCrossEngineSwitch(
        from: Activity,
        targetIntent: Intent,
        currentRequestedOrientation: Int,
    ) {
        unregisterActiveMobilePlayer(from)
        targetIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        targetIntent.putExtra(
            PlayerTransitionAndPipPolicy.EXTRA_PREFERRED_ORIENTATION,
            PlayerTransitionAndPipPolicy.orientationForCrossSwitch(currentRequestedOrientation),
        )
        targetIntent.putExtra(PlayerTransitionAndPipPolicy.EXTRA_FROM_CROSS_SWITCH, true)

        val options = ActivityOptions.makeCustomAnimation(from, 0, 0).toBundle()
        from.startActivity(targetIntent, options)
        overrideNoTransition(from, isOpening = false)
    }

    fun applyIncomingNoTransitionIfNeeded(activity: Activity, intent: Intent?) {
        if (intent?.getBooleanExtra(PlayerTransitionAndPipPolicy.EXTRA_FROM_CROSS_SWITCH, false) == true) {
            overrideNoTransition(activity, isOpening = true)
        }
    }

    fun overrideNoTransition(activity: Activity, isOpening: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (isOpening) {
                Activity.OVERRIDE_TRANSITION_OPEN
            } else {
                Activity.OVERRIDE_TRANSITION_CLOSE
            }
            activity.overrideActivityTransition(type, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }

    /**
     * 在首页拉起目标播放页前预检：若另一引擎播放页正悬浮于 PiP 小窗，先将其释放关闭，
     * 避免同 affinity 下的 `singleTask` 复用旧 pinned Task 导致新频道被迫缩在小窗内启动。
     */
    fun dismissOtherEngineBeforeLaunch(targetActivityClass: Class<out Activity>) {
        clearStashedCrossEngineLastFrame()
        val current = activePlayerRef?.get() ?: return
        if (current::class.java == targetActivityClass) return
        if (current.isFinishing || current.isDestroyed) {
            activePlayerRef = null
            activeDismissAction = null
            return
        }
        val dismiss = activeDismissAction
        activePlayerRef = null
        activeDismissAction = null
        runCatching { dismiss?.invoke() }
    }

    /**
     * 登记当前活跃手机端播放页；若存在另一个引擎的悬浮/残留播放页，立即触发其释放与关闭，
     * 确保跨引擎同屏绝不产生双实例双音轨。
     */
    fun registerActiveMobilePlayer(activity: Activity, dismissAction: () -> Unit) {
        val previous = activePlayerRef?.get()
        if (previous != null && previous !== activity && !previous.isFinishing && !previous.isDestroyed) {
            val dismiss = activeDismissAction
            activePlayerRef = null
            activeDismissAction = null
            runCatching { dismiss?.invoke() }
        }
        activePlayerRef = WeakReference(activity)
        activeDismissAction = dismissAction
    }

    fun unregisterActiveMobilePlayer(activity: Activity) {
        if (activePlayerRef?.get() === activity) {
            activePlayerRef = null
            activeDismissAction = null
        }
    }
}
