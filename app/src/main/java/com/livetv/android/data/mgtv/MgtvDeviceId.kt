package com.livetv.android.data.mgtv

import android.content.Context
import java.util.UUID

/**
 * MGTV 设备标识提供者。
 *
 * `/v1/live/source` 要求同时传 `did` 与 `deviceId`，二者取**同一个** UUID。
 * 该 UUID 是网页/客户端自己生成的设备标识（首次访问生成、之后复用），
 * 不需要服务端签发，因此生成一个本地 UUID 即可，不涉及任何凭据伪造。
 */
fun interface MgtvDeviceIdProvider {
    fun deviceId(): String
}

/** 进程内固定、重启即变的设备标识（用于测试或无法持久化的场景）。 */
object EphemeralMgtvDeviceId : MgtvDeviceIdProvider {
    private val id: String by lazy { UUID.randomUUID().toString() }
    override fun deviceId(): String = id
}

/**
 * 持久化设备标识：首次生成后写入 `SharedPreferences` 并复用，行为与网页端一致。
 */
class SharedPrefsMgtvDeviceId(context: Context) : MgtvDeviceIdProvider {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    private companion object {
        const val PREFS_NAME = "livetv_mgtv"
        const val KEY_DEVICE_ID = "device_id"
    }
}
