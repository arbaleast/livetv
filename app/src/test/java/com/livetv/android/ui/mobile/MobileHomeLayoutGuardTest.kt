package com.livetv.android.ui.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 方案 B 手机首页（暗黑影院风）资源与布局源码守卫：配色、无徽章/序号、左右分栏结构。
 */
class MobileHomeLayoutGuardTest {

    private fun res(path: String): String =
        File("src/main/res/$path").also { assertTrue("缺少 $path", it.isFile) }.readText(Charsets.UTF_8)

    @Test
    fun `home palette follows dark cinema spec`() {
        val colors = res("values/colors.xml")
        listOf(
            "#090B0E", "#0EFFFFFF", "#2EFFFFFF", "#59FFFFFF", "#12FFFFFF", "#14FFFFFF", "#99FFFFFF", "#FFFFFFFF",
        ).forEach { hex ->
            assertTrue("colors.xml 缺少 $hex", colors.contains(hex, ignoreCase = true))
        }
    }

    @Test
    fun `channel card has no badge no ordinal and uses two line typography`() {
        val card = res("layout/item_mobile_channel.xml")
        assertFalse("频道卡片不得再含彩色徽章", card.contains("channel_badge"))
        assertFalse("频道卡片不得再含递增序号", card.contains("channel_index"))
        assertTrue(card.contains("channel_name") && card.contains("channel_description"))
        val bg = res("drawable/bg_mobile_channel_card.xml")
        assertTrue("卡片圆角应为 12dp", bg.contains("12dp"))
        assertTrue("卡片底色应为半透明暗卡", bg.contains("mobile_home_card_bg"))
    }

    @Test
    fun `wide home uses 30 70 split with continue card capsules and icon actions`() {
        val layout = res("layout-w600dp/activity_mobile_main.xml")
        assertTrue(layout.contains("home_continue_card"))
        assertTrue(layout.contains("home_continue_badge"))
        assertTrue(layout.contains("android:layout_weight=\"3\""))
        assertTrue(layout.contains("android:layout_weight=\"7\""))
        assertTrue("顶栏搜索应为图标按钮", layout.contains("ic_home_search"))
        assertTrue("顶栏刷新应为图标按钮", layout.contains("ic_home_refresh"))
        val capsule = res("drawable/bg_mobile_category_pill.xml")
        assertTrue(capsule.contains("mobile_home_capsule_selected"))
        assertTrue(capsule.contains("mobile_home_capsule_selected_stroke"))
        assertTrue(capsule.contains("mobile_home_capsule_normal"))
    }
}
