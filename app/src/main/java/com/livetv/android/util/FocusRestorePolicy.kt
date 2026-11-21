package com.livetv.android.util

/**
 * Leanback 列表刷新后的**焦点行恢复**纯策略（无 Android 依赖，可纯 JVM 单测）。
 *
 * 刷新订阅 / MGTV 目录到达后，分类可能被增删，行号会整体偏移；
 * 只按 `selectedPosition` 恢复行号必然错位。正确顺序是：
 * 1. 按行标识（分类名 / 「设置与订阅」）找回；
 * 2. 标识不存在时退回原行号（有界，避免越界）；
 * 3. 都不可用时返回 `null`，交给 Leanback 默认落回首行。
 */
object FocusRestorePolicy {

    /**
     * @param rowKeys 刷新后每一行的标识（顺序即行号），缺失标识用 `null` 占位
     * @param preferredKey 刷新前焦点行的标识
     * @param fallbackRow 刷新前的行号（无效值如 -1 表示"没有可退回的行号"）
     * @return 恢复后的行号；无法恢复时返回 `null`
     */
    fun resolveRowIndex(
        rowKeys: List<String?>,
        preferredKey: String?,
        fallbackRow: Int,
    ): Int? {
        if (rowKeys.isEmpty()) return null
        if (preferredKey != null) {
            val byKey = rowKeys.indexOf(preferredKey)
            if (byKey >= 0) return byKey
        }
        return fallbackRow.takeIf { it in rowKeys.indices }
    }
}
