package com.livetv.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FocusRestorePolicy] 纯 JVM 单元测试：验证刷新后按行标识恢复、行号兜底与越界安全。
 */
class FocusRestorePolicyTest {

    @Test
    fun `restores the same category by name even when row indexes shift`() {
        // 刷新前：体育在第 3 行；刷新后新增了「收藏」分组，体育被挤到第 4 行
        val newRowKeys = listOf("收藏", "央视", "新闻", "体育", "设置与订阅")
        assertEquals(3, FocusRestorePolicy.resolveRowIndex(newRowKeys, "体育", fallbackRow = 2))
    }

    @Test
    fun `removed category falls back to the bounded previous row index`() {
        val newRowKeys = listOf("央视", "新闻", "设置与订阅")
        // 原分类「体育」已被删除，原行号 2 仍有效 → 退回第 2 行（设置与订阅），而不是越界
        assertEquals(2, FocusRestorePolicy.resolveRowIndex(newRowKeys, "体育", fallbackRow = 2))
    }

    @Test
    fun `settings row is restored by its name`() {
        val newRowKeys = listOf("央视", "新闻", "设置与订阅")
        assertEquals(2, FocusRestorePolicy.resolveRowIndex(newRowKeys, "设置与订阅", fallbackRow = 0))
    }

    @Test
    fun `out of range fallback resolves to null so leanback keeps the default first row`() {
        val newRowKeys = listOf("央视")
        assertNull(FocusRestorePolicy.resolveRowIndex(newRowKeys, "体育", fallbackRow = 5))
        assertNull(FocusRestorePolicy.resolveRowIndex(newRowKeys, null, fallbackRow = -1))
    }

    @Test
    fun `empty list and blank preferred key are handled safely`() {
        assertNull(FocusRestorePolicy.resolveRowIndex(emptyList(), "体育", fallbackRow = 0))
        assertEquals(0, FocusRestorePolicy.resolveRowIndex(listOf("央视"), null, fallbackRow = 0))
    }

    @Test
    fun `null keys never match a real key`() {
        val newRowKeys = listOf(null, "央视")
        assertEquals(1, FocusRestorePolicy.resolveRowIndex(newRowKeys, "央视", fallbackRow = 0))
        assertEquals(0, FocusRestorePolicy.resolveRowIndex(newRowKeys, "体育", fallbackRow = 0))
    }
}
