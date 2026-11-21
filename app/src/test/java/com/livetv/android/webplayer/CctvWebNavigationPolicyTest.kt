package com.livetv.android.webplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CctvWebNavigationPolicy` 的纯 JVM 单测：WebView 顶层导航的 Fail-Closed 白名单。
 *
 * 仅放行央视官方直播域（精确 `tv.cctv.com` 与其严格子域），其余一律拦截。
 */
class CctvWebNavigationPolicyTest {

    @Test
    fun `allows the official live page and its strict subdomains`() {
        assertTrue(CctvWebNavigationPolicy.isAllowed("https://tv.cctv.com/live/cctv1/"))
        assertTrue(CctvWebNavigationPolicy.isAllowed("https://live.tv.cctv.com/live/cctv1/"))
    }

    @Test
    fun `blocks same suffix lookalike hosts`() {
        assertFalse(CctvWebNavigationPolicy.isAllowed("https://eviltv.cctv.com/live/cctv1/"))
        assertFalse(CctvWebNavigationPolicy.isAllowed("https://tv.cctv.com.evil.invalid/live/cctv1/"))
    }

    @Test
    fun `blocks userinfo host spoofing`() {
        assertFalse(CctvWebNavigationPolicy.isAllowed("https://tv.cctv.com@evil.invalid/live/cctv1/"))
    }

    @Test
    fun `blocks cleartext http`() {
        assertFalse(CctvWebNavigationPolicy.isAllowed("http://tv.cctv.com/live/cctv1/"))
    }

    @Test
    fun `blocks external ad domains`() {
        assertFalse(CctvWebNavigationPolicy.isAllowed("https://ads.example.invalid/"))
    }

    @Test
    fun `blocks non https and external app schemes`() {
        listOf(
            "intent://scan/#Intent;scheme=zxing;end",
            "market://details?id=com.example",
            "file:///etc/hosts",
            "content://com.example/x",
            "javascript:alert(1)",
            "data:text/html,<b>x</b>",
            "tel:10086",
            "mailto:a@example.invalid",
            "blob:https://tv.cctv.com/uuid",
        ).forEach { url ->
            assertFalse("应拦截 $url", CctvWebNavigationPolicy.isAllowed(url))
        }
    }

    @Test
    fun `blocks empty blank and malformed urls`() {
        listOf("", "   ", "not a url", "https://", "//tv.cctv.com/x").forEach { url ->
            assertFalse("应拦截 [$url]", CctvWebNavigationPolicy.isAllowed(url))
        }
    }

    @Test
    fun `rejection log only exposes scheme and host`() {
        val detail = CctvWebNavigationPolicy.describeForLog("https://ads.example.invalid/path?token=SECRET")

        assertTrue(detail.contains("https"))
        assertTrue(detail.contains("ads.example.invalid"))
        assertFalse("日志不得包含查询串", detail.contains("SECRET"))
    }
}
