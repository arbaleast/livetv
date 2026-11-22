package com.livetv.android.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [MobileUiPreferenceStore] 与 [MobileUiPreferences] CCTV 网页控件状态持久化的纯 JVM 单元测试。
 */
class MobileUiPreferencesTest {

    private class InMemoryStore : MobileUiPreferenceBackend {
        private val strings = mutableMapOf<String, String>()
        private val stringSets = mutableMapOf<String, Set<String>>()
        private val booleans = mutableMapOf<String, Boolean>()

        override fun getString(key: String, defaultValue: String?): String? = strings[key] ?: defaultValue
        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> =
            stringSets[key] ?: defaultValue

        override fun putStringSet(key: String, value: Set<String>) {
            stringSets[key] = value.toSet()
        }

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            booleans[key] ?: defaultValue

        override fun putBoolean(key: String, value: Boolean) {
            booleans[key] = value
        }
    }

    @Test
    fun `cctv web controls preference defaults to false and persists round-trip`() {
        val backend = InMemoryStore()
        val store = MobileUiPreferenceStore(backend)

        assertFalse("默认应为纯净模式（false）", store.isCctvWebControlsVisible())

        store.setCctvWebControlsVisible(true)
        assertTrue(store.isCctvWebControlsVisible())

        // 模拟杀进程重建新实例，底层持久化仍保留为 true
        val reopened = MobileUiPreferenceStore(backend)
        assertTrue(reopened.isCctvWebControlsVisible())

        reopened.setCctvWebControlsVisible(false)
        assertFalse(MobileUiPreferenceStore(backend).isCctvWebControlsVisible())
    }

    @Test
    fun `favorite and last played helpers remain consistent on the shared preference store`() {
        val store = MobileUiPreferenceStore(InMemoryStore())
        assertEquals(null, store.getLastPlayedChannelId())
        store.setLastPlayedChannelId("cctv:cctv13")
        assertEquals("cctv:cctv13", store.getLastPlayedChannelId())

        assertTrue(store.toggleFavoriteChannel("cctv:cctv13"))
        assertTrue("cctv:cctv13" in store.getFavoriteChannelIds())
        assertFalse(store.toggleFavoriteChannel("cctv:cctv13"))
        assertTrue(store.getFavoriteChannelIds().isEmpty())
    }

    @Test
    fun `MobileUiPreferences exposes context APIs with false default`() {
        val src = File("src/main/java/com/livetv/android/ui/mobile/MobileUiPreferences.kt")
            .readText(Charsets.UTF_8)
        assertTrue(src.contains("fun isCctvWebControlsVisible("))
        assertTrue(src.contains("fun setCctvWebControlsVisible("))
        assertTrue(src.contains("cctv_web_controls_visible"))
    }
}
