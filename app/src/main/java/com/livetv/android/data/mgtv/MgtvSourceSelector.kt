package com.livetv.android.data.mgtv

import java.net.URI
import java.util.Locale

/**
 * MGTV `sources` 白盒筛选与清晰度选择策略（纯 Kotlin，确定性实现）。
 *
 * 规则：
 * 1. **硬过滤**：仅放行同时满足以下 3 项的源：
 *    - `format` 为 `.m3u8`（忽略大小写）；
 *    - `effectiveHdcp == 0`（拦截 HDCP 受限流；缺失或非整数一律视为不合规）；
 *    - `url` 为合法 `https://` 且包含有效 host。
 *
 *    **`isp2p` 不再作为过滤条件**：它只表示"该源另外支持 P2P 传输"，与本应用无关——
 *    我们始终按标准 HTTPS HLS 拉流，从不建立 P2P 连接。实测 `cameraId=221`（湖南经视）
 *    返回 `isp2p=1`，但其 `.m3u8` 与 `.ts` 均为 HTTP 200 且为标准 MPEG-TS；
 *    若按 `isp2p==0` 过滤，这个频道会永远"无可用源"。
 * 2. **清晰度选择**：
 *    - 优先匹配 `preferredDefinition`（按 `definition` 编号或 `name` 如 `"720P"` 忽略大小写匹配）；
 *    - 未命中时按确定性顺序回退至最高可用普通清晰度（先按 `<数字>P` 分辨率降序，再按 `definition` 数值降序，最后按原始顺序）。
 */
object MgtvSourceSelector {

    private val RESOLUTION_REGEX = Regex("(\\d{3,4})[pP]")

    /**
     * 执行硬过滤，返回全部合规的标准 HTTPS HLS 源。
     */
    fun filterSupportedSources(response: MgtvSourceResponse): List<MgtvSourceItem> {
        if (response.isDrm) return emptyList()
        return response.sources.filter { item ->
            val effectiveHdcp = item.hdcp ?: response.hdcp
            !item.isDrm &&
                isM3u8Format(item.format) &&
                effectiveHdcp == 0 &&
                isValidHttpsUrl(item.url)
        }
    }

    /**
     * 按白盒规则选出唯一最佳播放源；无满足条件的源时返回 `null`（调用方应映射为 `UNSUPPORTED_SOURCE`）。
     */
    fun selectBestSource(
        response: MgtvSourceResponse,
        preferredDefinition: String? = null,
    ): MgtvSourceItem? {
        val eligible = filterSupportedSources(response)
        if (eligible.isEmpty()) return null

        val pref = preferredDefinition?.trim().orEmpty()
        if (pref.isNotEmpty()) {
            val matched = eligible.firstOrNull { item ->
                item.definition.equals(pref, ignoreCase = true) ||
                    item.name.equals(pref, ignoreCase = true)
            }
            if (matched != null) return matched
        }

        // 确定性回退：按分辨率数值降序 -> definition 编号降序 -> 原始索引升序（稳定排序）
        return eligible.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<MgtvSourceItem>> { resolutionRank(it.value) }
                    .thenByDescending { definitionNumericRank(it.value.definition) }
                    .thenBy { it.index },
            )
            .first()
            .value
    }

    private fun isM3u8Format(format: String): Boolean {
        val normalized = format.trim().lowercase(Locale.US)
        return normalized == ".m3u8" || normalized == "m3u8"
    }

    private fun isValidHttpsUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null) return false
        return !uri.host.isNullOrBlank()
    }

    private fun resolutionRank(item: MgtvSourceItem): Int {
        val match = RESOLUTION_REGEX.find(item.name)
        if (match != null) {
            return match.groupValues[1].toIntOrNull() ?: 0
        }
        return when (item.name.trim()) {
            "蓝光", "超清" -> 1080
            "高清" -> 720
            "标清" -> 480
            "流畅" -> 360
            else -> 0
        }
    }

    private fun definitionNumericRank(definition: String): Int =
        definition.trim().toIntOrNull() ?: 0
}
