package com.livetv.android.util

/**
 * 播放页按键路由纯策略（无 Android 依赖，可纯 JVM 单测）。
 *
 * 常量值与 `android.view.KeyEvent` 保持一致，覆盖：
 * - TV 端正常播放态：上下切台、左键/菜单键呼出侧边选台栏、OK 呼出控制器（错误态手动重置）；
 * - TV 端抽屉打开态：方向键与确认键交还列表，右键/返回键收起抽屉；
 * - 控制器打开态：方向键交还 `PlayerView`，返回键收起控制器；
 * - 触控端锁屏态：返回键仅提示解锁，防误退。
 */
object PlayerKeyPolicy {

    const val KEYCODE_BACK = 4
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_DPAD_CENTER = 23
    const val KEYCODE_ENTER = 66
    const val KEYCODE_MENU = 82

    enum class KeyAction {
        PASS_THROUGH,
        SWITCH_PREV_CHANNEL,
        SWITCH_NEXT_CHANNEL,
        OPEN_DRAWER,
        CLOSE_DRAWER,
        OPEN_CONTROLLER,
        CLOSE_CONTROLLER,
        MANUAL_RESET,
        SHOW_LOCKED_HINT,
    }

    fun resolveDispatchKeyDown(
        keyCode: Int,
        isTouchDevice: Boolean,
        isDrawerOpen: Boolean,
        isControllerOpenedByUser: Boolean,
        isErrorState: Boolean,
        /** 触控端"锁屏"标记：锁屏后硬件方向键/确认键不得再切台或呼出控制器。 */
        isScreenLocked: Boolean = false,
    ): KeyAction {
        // 锁屏语义只对触控端生效，但策略层保持纯粹：锁屏时一律透传，由视图层决定忽略。
        if (isScreenLocked) {
            return KeyAction.PASS_THROUGH
        }
        if (isDrawerOpen) {
            return if (!isTouchDevice && keyCode == KEYCODE_DPAD_RIGHT) {
                KeyAction.CLOSE_DRAWER
            } else {
                KeyAction.PASS_THROUGH
            }
        }
        if (isControllerOpenedByUser) {
            return KeyAction.PASS_THROUGH
        }
        return when (keyCode) {
            KEYCODE_DPAD_UP -> KeyAction.SWITCH_PREV_CHANNEL
            KEYCODE_DPAD_DOWN -> KeyAction.SWITCH_NEXT_CHANNEL
            KEYCODE_DPAD_LEFT, KEYCODE_MENU ->
                if (!isTouchDevice) KeyAction.OPEN_DRAWER else KeyAction.PASS_THROUGH
            KEYCODE_DPAD_CENTER, KEYCODE_ENTER ->
                if (isErrorState) KeyAction.MANUAL_RESET else KeyAction.OPEN_CONTROLLER
            else -> KeyAction.PASS_THROUGH
        }
    }

    fun resolveBackKeyDown(
        isTouchDevice: Boolean,
        isScreenLocked: Boolean,
        isDrawerOpen: Boolean,
        isControllerVisible: Boolean,
    ): KeyAction = when {
        isTouchDevice && isScreenLocked -> KeyAction.SHOW_LOCKED_HINT
        isDrawerOpen -> KeyAction.CLOSE_DRAWER
        isControllerVisible -> KeyAction.CLOSE_CONTROLLER
        else -> KeyAction.PASS_THROUGH
    }
}
