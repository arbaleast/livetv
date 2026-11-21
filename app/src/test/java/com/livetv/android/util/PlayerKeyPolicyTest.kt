package com.livetv.android.util

import com.livetv.android.util.PlayerKeyPolicy.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PlayerKeyPolicy] 纯 JVM 单元测试：完整覆盖 TV 遥控器选台栏、切台、控制器与触控锁屏的按键仲裁。
 */
class PlayerKeyPolicyTest {

    @Test
    fun `tv normal playback maps up down to channel switch, left or menu to drawer, ok to controller`() {
        assertEquals(
            KeyAction.SWITCH_PREV_CHANNEL,
            PlayerKeyPolicy.resolveDispatchKeyDown(
                keyCode = PlayerKeyPolicy.KEYCODE_DPAD_UP,
                isTouchDevice = false,
                isDrawerOpen = false,
                isControllerOpenedByUser = false,
                isErrorState = false,
            ),
        )
        assertEquals(
            KeyAction.SWITCH_NEXT_CHANNEL,
            PlayerKeyPolicy.resolveDispatchKeyDown(
                keyCode = PlayerKeyPolicy.KEYCODE_DPAD_DOWN,
                isTouchDevice = false,
                isDrawerOpen = false,
                isControllerOpenedByUser = false,
                isErrorState = false,
            ),
        )
        assertEquals(
            KeyAction.OPEN_DRAWER,
            PlayerKeyPolicy.resolveDispatchKeyDown(
                keyCode = PlayerKeyPolicy.KEYCODE_DPAD_LEFT,
                isTouchDevice = false,
                isDrawerOpen = false,
                isControllerOpenedByUser = false,
                isErrorState = false,
            ),
        )
        assertEquals(
            KeyAction.OPEN_DRAWER,
            PlayerKeyPolicy.resolveDispatchKeyDown(
                keyCode = PlayerKeyPolicy.KEYCODE_MENU,
                isTouchDevice = false,
                isDrawerOpen = false,
                isControllerOpenedByUser = false,
                isErrorState = false,
            ),
        )
        assertEquals(
            KeyAction.OPEN_CONTROLLER,
            PlayerKeyPolicy.resolveDispatchKeyDown(
                keyCode = PlayerKeyPolicy.KEYCODE_DPAD_CENTER,
                isTouchDevice = false,
                isDrawerOpen = false,
                isControllerOpenedByUser = false,
                isErrorState = false,
            ),
        )
        assertEquals(
            KeyAction.PASS_THROUGH,
            PlayerKeyPolicy.resolveDispatchKeyDown(
                keyCode = PlayerKeyPolicy.KEYCODE_DPAD_RIGHT,
                isTouchDevice = false,
                isDrawerOpen = false,
                isControllerOpenedByUser = false,
                isErrorState = false,
            ),
        )
    }

    @Test
    fun `ok key in error state triggers manual reset`() {
        assertEquals(
            KeyAction.MANUAL_RESET,
            PlayerKeyPolicy.resolveDispatchKeyDown(
                keyCode = PlayerKeyPolicy.KEYCODE_DPAD_CENTER,
                isTouchDevice = false,
                isDrawerOpen = false,
                isControllerOpenedByUser = false,
                isErrorState = true,
            ),
        )
        assertEquals(
            KeyAction.MANUAL_RESET,
            PlayerKeyPolicy.resolveDispatchKeyDown(
                keyCode = PlayerKeyPolicy.KEYCODE_ENTER,
                isTouchDevice = false,
                isDrawerOpen = false,
                isControllerOpenedByUser = false,
                isErrorState = true,
            ),
        )
    }

    @Test
    fun `tv drawer open passes up down ok to list and uses right to close drawer`() {
        listOf(
            PlayerKeyPolicy.KEYCODE_DPAD_UP,
            PlayerKeyPolicy.KEYCODE_DPAD_DOWN,
            PlayerKeyPolicy.KEYCODE_DPAD_CENTER,
            PlayerKeyPolicy.KEYCODE_ENTER,
            PlayerKeyPolicy.KEYCODE_DPAD_LEFT,
        ).forEach { code ->
            assertEquals(
                KeyAction.PASS_THROUGH,
                PlayerKeyPolicy.resolveDispatchKeyDown(
                    keyCode = code,
                    isTouchDevice = false,
                    isDrawerOpen = true,
                    isControllerOpenedByUser = false,
                    isErrorState = false,
                ),
            )
        }
        assertEquals(
            KeyAction.CLOSE_DRAWER,
            PlayerKeyPolicy.resolveDispatchKeyDown(
                keyCode = PlayerKeyPolicy.KEYCODE_DPAD_RIGHT,
                isTouchDevice = false,
                isDrawerOpen = true,
                isControllerOpenedByUser = false,
                isErrorState = false,
            ),
        )
    }

    @Test
    fun `controller open passes all directional keys through to player view`() {
        listOf(
            PlayerKeyPolicy.KEYCODE_DPAD_UP,
            PlayerKeyPolicy.KEYCODE_DPAD_DOWN,
            PlayerKeyPolicy.KEYCODE_DPAD_LEFT,
            PlayerKeyPolicy.KEYCODE_DPAD_RIGHT,
            PlayerKeyPolicy.KEYCODE_DPAD_CENTER,
            PlayerKeyPolicy.KEYCODE_MENU,
        ).forEach { code ->
            assertEquals(
                KeyAction.PASS_THROUGH,
                PlayerKeyPolicy.resolveDispatchKeyDown(
                    keyCode = code,
                    isTouchDevice = false,
                    isDrawerOpen = false,
                    isControllerOpenedByUser = true,
                    isErrorState = false,
                ),
            )
        }
    }

    @Test
    fun `locked screen ignores dpad and center so hardware keys cannot switch channels`() {
        // 锁屏后：上/下/左/右/确认/菜单均不再切台、不再呼出控制器（保持锁屏语义一致）
        listOf(
            PlayerKeyPolicy.KEYCODE_DPAD_UP,
            PlayerKeyPolicy.KEYCODE_DPAD_DOWN,
            PlayerKeyPolicy.KEYCODE_DPAD_LEFT,
            PlayerKeyPolicy.KEYCODE_DPAD_RIGHT,
            PlayerKeyPolicy.KEYCODE_DPAD_CENTER,
            PlayerKeyPolicy.KEYCODE_ENTER,
            PlayerKeyPolicy.KEYCODE_MENU,
        ).forEach { keyCode ->
            assertEquals(
                "keyCode=$keyCode",
                KeyAction.PASS_THROUGH,
                PlayerKeyPolicy.resolveDispatchKeyDown(
                    keyCode = keyCode,
                    isTouchDevice = true,
                    isDrawerOpen = false,
                    isControllerOpenedByUser = false,
                    isErrorState = false,
                    isScreenLocked = true,
                ),
            )
        }
    }

    @Test
    fun `locked screen does not block unlocking via touch overlay`() {
        // 锁屏只吞按键，解锁按钮的点击链路不经过该策略，这里只固化"策略不返回额外动作"
        assertEquals(
            KeyAction.PASS_THROUGH,
            PlayerKeyPolicy.resolveDispatchKeyDown(
                keyCode = PlayerKeyPolicy.KEYCODE_DPAD_CENTER,
                isTouchDevice = true,
                isDrawerOpen = false,
                isControllerOpenedByUser = false,
                isErrorState = true,
                isScreenLocked = true,
            ),
        )
    }

    @Test
    fun `back key prioritizes touch lock, then drawer on both tv and touch, then controller, then exit`() {
        assertEquals(
            KeyAction.SHOW_LOCKED_HINT,
            PlayerKeyPolicy.resolveBackKeyDown(
                isTouchDevice = true,
                isScreenLocked = true,
                isDrawerOpen = false,
                isControllerVisible = false,
            ),
        )
        assertEquals(
            KeyAction.CLOSE_DRAWER,
            PlayerKeyPolicy.resolveBackKeyDown(
                isTouchDevice = false,
                isScreenLocked = false,
                isDrawerOpen = true,
                isControllerVisible = false,
            ),
        )
        assertEquals(
            KeyAction.CLOSE_DRAWER,
            PlayerKeyPolicy.resolveBackKeyDown(
                isTouchDevice = true,
                isScreenLocked = false,
                isDrawerOpen = true,
                isControllerVisible = true,
            ),
        )
        assertEquals(
            KeyAction.CLOSE_CONTROLLER,
            PlayerKeyPolicy.resolveBackKeyDown(
                isTouchDevice = false,
                isScreenLocked = false,
                isDrawerOpen = false,
                isControllerVisible = true,
            ),
        )
        assertEquals(
            KeyAction.PASS_THROUGH,
            PlayerKeyPolicy.resolveBackKeyDown(
                isTouchDevice = false,
                isScreenLocked = false,
                isDrawerOpen = false,
                isControllerVisible = false,
            ),
        )
    }
}
