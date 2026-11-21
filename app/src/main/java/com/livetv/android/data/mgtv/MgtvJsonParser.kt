package com.livetv.android.data.mgtv

import org.json.JSONObject

/**
 * MGTV 目录与播放源接口响应解析器（纯 Kotlin + `org.json`，可在纯 JVM 单测执行）。
 *
 * 支持：
 * 1. `getLiveAssetCategoryList` 的 JSONP（`livecallback({...})`）与纯 JSON 响应；
 * 2. `/v1/live/source` 的 JSON 响应与 `sources[]` 全字段解析。
 */
object MgtvJsonParser {

    /**
     * 剥离 JSONP 包裹（如 `livecallback({...});`），若本就是纯 JSON 则原样返回。
     */
    fun unwrapJsonp(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val openIdx = trimmed.indexOf('(')
        val closeIdx = trimmed.lastIndexOf(')')
        if (openIdx > 0 && closeIdx > openIdx) {
            return trimmed.substring(openIdx + 1, closeIdx).trim()
        }
        return trimmed
    }

    /**
     * 解析 MGTV 频道目录列表；解析失败或业务错误码非 `"0"` 时返回空列表。
     */
    fun parseCategoryList(raw: String): List<MgtvCategory> {
        val jsonText = unwrapJsonp(raw)
        if (jsonText.isEmpty()) return emptyList()

        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val errno = root.optString("errno", "").trim()
        if (errno.isNotEmpty() && errno != "0") return emptyList()

        val data = root.optJSONObject("data") ?: return emptyList()
        val catArray = data.optJSONArray("category") ?: return emptyList()

        val result = mutableListOf<MgtvCategory>()
        for (i in 0 until catArray.length()) {
            val catObj = catArray.optJSONObject(i) ?: continue
            val catId = catObj.optString("category_id", "").trim()
            val catName = catObj.optString("name", "").trim()
            if (catId.isEmpty() || catName.isEmpty()) continue

            val chArray = catObj.optJSONArray("channels") ?: continue
            val channels = mutableListOf<MgtvChannel>()
            for (j in 0 until chArray.length()) {
                val chObj = chArray.optJSONObject(j) ?: continue
                val id = chObj.optString("id", "").trim()
                val name = chObj.optString("name", "").trim()
                if (id.isEmpty() || name.isEmpty()) continue
                channels += MgtvChannel(
                    cameraId = id,
                    name = name,
                    logoUrl = chObj.optString("channel_image", "").trim(),
                    channelNumber = chObj.optString("channel_number", "").trim(),
                    currentProgram = chObj.optString("curr_program", "").trim(),
                )
            }
            if (channels.isNotEmpty()) {
                result += MgtvCategory(id = catId, name = catName, channels = channels)
            }
        }
        return result
    }

    /**
     * 解析 `/v1/live/source` 响应 JSON；结构损坏或解析异常返回 `null`。
     */
    fun parseSourceResponse(raw: String): MgtvSourceResponse? {
        val jsonText = unwrapJsonp(raw)
        if (jsonText.isEmpty()) return null

        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val code = root.optString("code", "").trim()
        val msg = root.optString("msg", "").trim()
        val data = root.optJSONObject("data") ?: return null

        // 严格 fail-closed：isp2p / hdcp 缺失或非整数（如 "true"/未知枚举）一律回落 -1，绝不默认放行
        val responseIsP2p = parseIntField(data, "isp2p", defaultValue = UNKNOWN_FLAG)
        val responseHdcp = parseIntField(data, "hdcp", defaultValue = UNKNOWN_FLAG)
        val responseIsDrm = hasDrmFlag(data)
        val sourcesArray = data.optJSONArray("sources")

        val sources = mutableListOf<MgtvSourceItem>()
        if (sourcesArray != null) {
            for (i in 0 until sourcesArray.length()) {
                val itemObj = sourcesArray.optJSONObject(i) ?: continue
                sources += MgtvSourceItem(
                    url = itemObj.optString("url", "").trim(),
                    format = itemObj.optString("format", "").trim(),
                    videoFormat = itemObj.optString("videoFormat", "").trim(),
                    audioFormat = itemObj.optString("audioFormat", "").trim(),
                    definition = itemObj.optString("definition", "").trim(),
                    name = itemObj.optString("name", "").trim(),
                    urlExpireDurationSec = parseLongField(itemObj, "urlExpireDuration", defaultValue = 0L),
                    sid = itemObj.optString("sid", "").trim(),
                    npuk = itemObj.optString("npuk", "").trim(),
                    isOtherCdn = parseIntField(itemObj, "isothercdn", defaultValue = 0),
                    isP2p = if (itemObj.has("isp2p") && !itemObj.isNull("isp2p")) {
                        parseIntField(itemObj, "isp2p", defaultValue = UNKNOWN_FLAG)
                    } else {
                        null
                    },
                    hdcp = if (itemObj.has("hdcp") && !itemObj.isNull("hdcp")) {
                        parseIntField(itemObj, "hdcp", defaultValue = UNKNOWN_FLAG)
                    } else {
                        null
                    },
                    isDrm = hasDrmFlag(itemObj),
                )
            }
        }

        return MgtvSourceResponse(
            code = code,
            message = msg,
            cameraId = data.optString("cameraId", "").trim(),
            cameraName = data.optString("cameraName", "").trim(),
            isP2p = responseIsP2p,
            hdcp = responseHdcp,
            serverTimeSec = parseLongField(data, "servertime", defaultValue = 0L),
            sources = sources,
            isDrm = responseIsDrm,
        )
    }

    private const val UNKNOWN_FLAG = -1

    private val DRM_KEYS = listOf("drm", "isDrm", "is_drm", "drmType", "drm_type")

    private fun hasDrmFlag(obj: JSONObject): Boolean {
        for (key in DRM_KEYS) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.optString(key, "").trim().lowercase()
            if (value.isNotEmpty() && value != "0" && value != "false" && value != "none") {
                return true
            }
        }
        return false
    }

    private fun parseIntField(obj: JSONObject, key: String, defaultValue: Int): Int {
        if (!obj.has(key) || obj.isNull(key)) return defaultValue
        return obj.optString(key, "").trim().toIntOrNull() ?: defaultValue
    }

    private fun parseLongField(obj: JSONObject, key: String, defaultValue: Long): Long {
        if (!obj.has(key) || obj.isNull(key)) return defaultValue
        return obj.optString(key, "").trim().toLongOrNull() ?: defaultValue
    }
}
