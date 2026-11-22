package com.livetv.android.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 手机端双引擎四场景加载策略（[MobileLoadingScenePolicy]）纯 JVM 单测：
 * 覆盖四场景分派、LoadStage 单调递增状态机、真实网络信号分类、炉石风 Tips 避重随机与代次隔离。
 */
class MobileLoadingScenePolicyTest {

    @Test
    fun `resolves four distinct loading scenes with unified visual skeleton and differentiated rhythms`() {
        assertEquals("过渡动画时长应统一为 200ms", 200L, MobileLoadingScenePolicy.FADE_DURATION_MS)
        assertEquals("柔光呼吸最低透明度应为 0.65f", 0.65f, MobileLoadingScenePolicy.SEGMENT_BREATH_MIN_ALPHA)
        assertEquals("柔光呼吸最高透明度应为 1.0f", 1.0f, MobileLoadingScenePolicy.SEGMENT_BREATH_MAX_ALPHA)
        assertEquals("柔光呼吸周期应为 900ms", 900L, MobileLoadingScenePolicy.SEGMENT_BREATH_CYCLE_MS)

        // 场景 A：MGTV 切台（与 CCTV 共享虚化尾帧 + 大字台名 + 3 段极细进度条 + 底部无框字幕 Tip 的统一视觉骨架，但采用快切节奏）
        val specA = MobileLoadingScenePolicy.specFor(MobileLoadingScene.SCENE_A_MGTV_SWITCH)
        assertTrue("场景 A 应启用三段式极细进度指示以对齐双端视觉骨架", specA.showStageProgress)
        assertEquals("场景 A 分段进度条段数应为 3", 3, specA.segmentCount)
        assertEquals("场景 A 初始阶段应立即点亮第 1 段 CONNECTING", LoadStage.CONNECTING, specA.initialStage)
        assertTrue("场景 A 应启用底部无框字幕 Tips 以消除双端割裂", specA.showTips)
        assertEquals("场景 A Tips 应立即随遮罩浮现", 0L, specA.tipInitialDelayMs)
        assertEquals(4_000L, specA.tipRotateIntervalMs)
        assertEquals(8, specA.tipPoolSize)
        assertEquals(1_500L, specA.reconnectHintDelayMs)

        // 场景 B：CCTV 同页切台（3 段进度，立即点亮第 1 段 CONNECTING 呼吸，杜绝进度条发僵）
        val sceneB = MobileLoadingScenePolicy.resolveCctvEntryScene(
            fromCrossEngineSwitch = false,
            isSamePageSwitch = true,
            hasLastFrame = true,
        )
        assertEquals(MobileLoadingScene.SCENE_B_CCTV_SWITCH, sceneB)
        val specB = MobileLoadingScenePolicy.specFor(sceneB)
        assertTrue("场景 B 显示三段式真实信号进度", specB.showStageProgress)
        assertEquals("场景 B 极细分段条应为 3 段", 3, specB.segmentCount)
        assertEquals("场景 B 初始阶段应立即进入 CONNECTING 避免首段空等死板", LoadStage.CONNECTING, specB.initialStage)
        assertTrue("场景 B 允许本地 EPG 命中时展示信息卡", specB.allowProgramInfo)
        assertTrue("场景 B 启用切台 Tips", specB.showTips)
        assertEquals(0L, specB.tipInitialDelayMs)
        assertEquals(4_000L, specB.tipRotateIntervalMs)
        assertEquals(8, specB.tipPoolSize)

        // 场景 C：MGTV -> CCTV 跨源切台
        val sceneC = MobileLoadingScenePolicy.resolveCctvEntryScene(
            fromCrossEngineSwitch = true,
            isSamePageSwitch = false,
            hasLastFrame = true,
        )
        assertEquals(MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV, sceneC)
        val specC = MobileLoadingScenePolicy.specFor(sceneC)
        assertTrue(specC.showStageProgress)
        assertEquals("场景 C 极细分段条应为 4 段", 4, specC.segmentCount)
        assertEquals("场景 C 前置阶段应为 RELEASING", LoadStage.RELEASING, specC.initialStage)
        assertTrue(specC.allowProgramInfo)
        assertTrue(specC.showTips)
        assertEquals(0L, specC.tipInitialDelayMs)
        assertEquals(4_000L, specC.tipRotateIntervalMs)
        assertEquals(8, specC.tipPoolSize)

        // 场景 D：初次冷启动进入直播
        val sceneD = MobileLoadingScenePolicy.resolveCctvEntryScene(
            fromCrossEngineSwitch = false,
            isSamePageSwitch = false,
            hasLastFrame = false,
        )
        assertEquals(MobileLoadingScene.SCENE_D_COLD_START, sceneD)
        val specD = MobileLoadingScenePolicy.specFor(sceneD)
        assertTrue(specD.showStageProgress)
        assertEquals("场景 D 极细分段条应为 4 段", 4, specD.segmentCount)
        assertEquals("场景 D 前置阶段应为 INIT_PLAYER", LoadStage.INIT_PLAYER, specD.initialStage)
        assertTrue("场景 D 立即显示冷启动专属 Tips", specD.showTips)
        assertEquals("场景 D Tips 应 0ms 立即展示", 0L, specD.tipInitialDelayMs)
        assertEquals("场景 D Tips 应每 5s 轮换", 5_000L, specD.tipRotateIntervalMs)
        assertEquals(4, specD.tipPoolSize)
    }

    @Test
    fun `litSegmentCount and activeBreathingSegmentIndex map stages monotonically across all scenes`() {
        assertEquals(1, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_A_MGTV_SWITCH, LoadStage.CONNECTING))
        assertEquals(2, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_A_MGTV_SWITCH, LoadStage.STREAMING))
        assertEquals(3, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_A_MGTV_SWITCH, LoadStage.DECODING))
        assertEquals(0, MobileLoadingScenePolicy.activeBreathingSegmentIndex(MobileLoadingScene.SCENE_A_MGTV_SWITCH, LoadStage.CONNECTING))
        assertEquals(1, MobileLoadingScenePolicy.activeBreathingSegmentIndex(MobileLoadingScene.SCENE_A_MGTV_SWITCH, LoadStage.STREAMING))
        assertEquals(2, MobileLoadingScenePolicy.activeBreathingSegmentIndex(MobileLoadingScene.SCENE_A_MGTV_SWITCH, LoadStage.DECODING))

        assertEquals(0, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_B_CCTV_SWITCH, null))
        assertEquals(1, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_B_CCTV_SWITCH, LoadStage.CONNECTING))
        assertEquals(2, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_B_CCTV_SWITCH, LoadStage.STREAMING))
        assertEquals(3, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_B_CCTV_SWITCH, LoadStage.DECODING))
        assertEquals(0, MobileLoadingScenePolicy.activeBreathingSegmentIndex(MobileLoadingScene.SCENE_B_CCTV_SWITCH, LoadStage.CONNECTING))
        assertEquals(1, MobileLoadingScenePolicy.activeBreathingSegmentIndex(MobileLoadingScene.SCENE_B_CCTV_SWITCH, LoadStage.STREAMING))
        assertEquals(2, MobileLoadingScenePolicy.activeBreathingSegmentIndex(MobileLoadingScene.SCENE_B_CCTV_SWITCH, LoadStage.DECODING))

        assertEquals(1, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV, LoadStage.RELEASING))
        assertEquals(2, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV, LoadStage.CONNECTING))
        assertEquals(3, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV, LoadStage.STREAMING))
        assertEquals(4, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV, LoadStage.DECODING))
        assertEquals(0, MobileLoadingScenePolicy.activeBreathingSegmentIndex(MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV, LoadStage.RELEASING))
        assertEquals(3, MobileLoadingScenePolicy.activeBreathingSegmentIndex(MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV, LoadStage.DECODING))

        assertEquals(1, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_D_COLD_START, LoadStage.INIT_PLAYER))
        assertEquals(2, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_D_COLD_START, LoadStage.CONNECTING))
        assertEquals(3, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_D_COLD_START, LoadStage.STREAMING))
        assertEquals(4, MobileLoadingScenePolicy.litSegmentCount(MobileLoadingScene.SCENE_D_COLD_START, LoadStage.DECODING))
    }

    @Test
    fun `soft fallback stage advances within ceiling and differentiates MGTV fast rhythm from CCTV`() {
        val sceneA = MobileLoadingScene.SCENE_A_MGTV_SWITCH
        val sceneC = MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV

        assertEquals(LoadStage.STREAMING, MobileLoadingScenePolicy.nextSoftFallbackStage(sceneA, LoadStage.CONNECTING))
        assertEquals(LoadStage.DECODING, MobileLoadingScenePolicy.nextSoftFallbackStage(sceneA, LoadStage.STREAMING))
        assertNull("已达 DECODING 上限时不得越界推进", MobileLoadingScenePolicy.nextSoftFallbackStage(sceneA, LoadStage.DECODING))

        assertEquals(LoadStage.CONNECTING, MobileLoadingScenePolicy.nextSoftFallbackStage(sceneC, LoadStage.RELEASING))
        assertEquals(LoadStage.STREAMING, MobileLoadingScenePolicy.nextSoftFallbackStage(sceneC, LoadStage.CONNECTING))
        assertEquals(LoadStage.DECODING, MobileLoadingScenePolicy.nextSoftFallbackStage(sceneC, LoadStage.STREAMING))
        assertNull(MobileLoadingScenePolicy.nextSoftFallbackStage(sceneC, LoadStage.DECODING))

        val mgtvConnectDelay = MobileLoadingScenePolicy.softAdvanceDelayMs(sceneA, LoadStage.CONNECTING)!!
        val cctvConnectDelay = MobileLoadingScenePolicy.softAdvanceDelayMs(sceneC, LoadStage.CONNECTING)!!
        assertTrue("MGTV 快切推进窗口应显著短于 CCTV 阶段窗口", mgtvConnectDelay < cctvConnectDelay)
        assertNull(MobileLoadingScenePolicy.softAdvanceDelayMs(sceneC, LoadStage.DECODING))
    }

    @Test
    fun `loadStage advances strictly monotonically and never regresses`() {
        val sceneC = MobileLoadingScene.SCENE_C_CROSS_ENGINE_TO_CCTV
        var stage: LoadStage? = MobileLoadingScenePolicy.specFor(sceneC).initialStage
        assertEquals(LoadStage.RELEASING, stage)

        // 正常推进：RELEASING -> CONNECTING -> STREAMING -> DECODING
        stage = MobileLoadingScenePolicy.advanceStage(sceneC, stage, LoadStage.CONNECTING)
        assertEquals(LoadStage.CONNECTING, stage)

        stage = MobileLoadingScenePolicy.advanceStage(sceneC, stage, LoadStage.STREAMING)
        assertEquals(LoadStage.STREAMING, stage)

        // 乱序/迟到的旧阶段信号绝不倒退
        assertEquals(
            LoadStage.STREAMING,
            MobileLoadingScenePolicy.advanceStage(sceneC, stage, LoadStage.CONNECTING),
        )
        assertEquals(
            LoadStage.STREAMING,
            MobileLoadingScenePolicy.advanceStage(sceneC, stage, LoadStage.RELEASING),
        )

        stage = MobileLoadingScenePolicy.advanceStage(sceneC, stage, LoadStage.DECODING)
        assertEquals(LoadStage.DECODING, stage)

        // 已达 DECODING 后收到后续 .ts 切片 STREAMING 信号不得倒退
        assertEquals(
            LoadStage.DECODING,
            MobileLoadingScenePolicy.advanceStage(sceneC, stage, LoadStage.STREAMING),
        )

        // 场景 A（MGTV 切台）同样支持 CONNECTING -> STREAMING -> DECODING 单调推进
        assertEquals(
            LoadStage.STREAMING,
            MobileLoadingScenePolicy.advanceStage(
                MobileLoadingScene.SCENE_A_MGTV_SWITCH,
                LoadStage.CONNECTING,
                LoadStage.STREAMING,
            ),
        )
    }

    @Test
    fun `classifies real CCTV VDN and media requests into LoadStage without false positives`() {
        assertEquals(
            LoadStage.CONNECTING,
            MobileLoadingScenePolicy.classifyCctvNetworkSignal(
                host = "vdnx.live.cntv.cn",
                path = "/api/v3/vdn/live",
            ),
        )
        assertEquals(
            LoadStage.CONNECTING,
            MobileLoadingScenePolicy.classifyCctvNetworkSignal(
                host = "vdn.apps.cntv.cn",
                path = "/api/getHttpVideoInfo.do",
            ),
        )
        assertEquals(
            LoadStage.STREAMING,
            MobileLoadingScenePolicy.classifyCctvNetworkSignal(
                host = "ldncctvwbcdtxy.liveplay.myqcloud.com",
                path = "/live/cctv1_2000.m3u8",
            ),
        )
        assertEquals(
            LoadStage.STREAMING,
            MobileLoadingScenePolicy.classifyCctvNetworkSignal(
                host = "ldncctvwbcdbd.a.bdydns.com",
                path = "/live/segment_00123.ts",
            ),
        )
        assertNull(
            MobileLoadingScenePolicy.classifyCctvNetworkSignal(
                host = "tv.cctv.com",
                path = "/live/cctv13/m/",
            ),
        )
        assertNull(
            MobileLoadingScenePolicy.classifyCctvNetworkSignal(
                host = "js.player.cntv.cn",
                path = "/creator/liveplayer.js",
            ),
        )
    }

    @Test
    fun `pickNextTipIndex never repeats previous index across consecutive draws`() {
        val rng = Random(42)
        for (poolSize in listOf(4, 8)) {
            var current = -1
            repeat(120) {
                val next = MobileLoadingScenePolicy.pickNextTipIndex(
                    poolSize = poolSize,
                    lastIndex = current,
                ) { bound -> rng.nextInt(bound) }
                assertTrue("索引必须在 0 until $poolSize 范围内", next in 0 until poolSize)
                assertNotEquals("连续两次抽取不得重复同一条 Tip", current, next)
                current = next
            }
        }
        assertEquals(0, MobileLoadingScenePolicy.pickNextTipIndex(1, 0) { 0 })
        assertEquals(-1, MobileLoadingScenePolicy.pickNextTipIndex(0, -1) { 0 })
    }

    @Test
    fun `generation guard drops stale stage and tip callbacks`() {
        assertFalse(MobileLoadingScenePolicy.isGenerationValid(activeGeneration = 5L, callbackGeneration = 4L))
        assertFalse(MobileLoadingScenePolicy.isGenerationValid(activeGeneration = 5L, callbackGeneration = 0L))
        assertTrue(MobileLoadingScenePolicy.isGenerationValid(activeGeneration = 5L, callbackGeneration = 5L))
    }
}
