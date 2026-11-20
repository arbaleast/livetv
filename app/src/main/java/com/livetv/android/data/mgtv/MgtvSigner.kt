package com.livetv.android.data.mgtv

import java.security.MessageDigest
import java.util.Locale

/**
 * MGTV `/v1/live/source` 的请求签名算法（纯 Kotlin，可 JVM 单测）。
 *
 * 算法（与芒果 TV 网页播放器 `vplayer` 中的实现一致）：
 * ```
 * sign = UPPER( MD5( SALT + sortedParams + SALT ) )
 * sortedParams = 按参数名升序，逐项拼接 "参数名" + "参数值"（无分隔符）
 * ```
 *
 * 注意三点：
 * 1. **空字符串要参与拼接**（只有 `null`/缺失才排除，`""` 必须保留）；
 * 2. `sign` 与 `_support` **不参与签名**（它们在签名后才追加到查询串）；
 * 3. 参与签名的参数集必须与实际发出的参数集**完全一致**，否则服务端返回 `2040117`。
 *
 * 该方法只做本地哈希，不联网、不读写存储，因此可被纯 JVM 测试直接覆盖。
 */
object MgtvSigner {

    /**
     * 签名盐值。
     *
     * 来源：芒果 TV 网页播放器公开的 JS 产物（`static.hitv.com/pc2/vplayer.*.js`）中常量。
     * 它随前端代码一起下发，不属于用户凭据；此处仅用于与官方网页客户端行为保持一致。
     */
    private const val SALT = "LMFwh1k1m@pvt#Pt"

    /** 计算签名。传入的 [params] 必须**不含** `sign` 与 `_support`。 */
    fun sign(params: Map<String, String>): String {
        val body = StringBuilder()
        for (key in params.keys.sorted()) {
            val value = params.getValue(key)
            body.append(key).append(value)
        }
        return md5Hex(SALT + body + SALT).uppercase(Locale.US)
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) out.append('0')
            out.append(Integer.toHexString(v))
        }
        return out.toString()
    }
}
