package kr.safecross.mobile

import kr.safecross.mobile.ui.screens.navigation.NavigationEffect
import kr.safecross.mobile.ui.screens.navigation.NavigationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SafetyProhibitedPhrasesTest {

    private val testDispatcher = StandardTestDispatcher()

    // 프로젝트에서 절대 금지된 안전 보장 표현 목록 (SR-F-048, TRD 4.8)
    private val prohibitedPhrases = listOf(
        "안전" + "합니다",
        "지금" + " " + "건너세요",
        "차가" + " " + "없습니다",
        "100%" + " " + "녹색",
        "장애인" + " " + "안전" + " " + "경로",
        "안심" + " " + "경로"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testStringsXmlDoesNotContainProhibitedPhrases() {
        val stringsFile = File("src/main/res/values/strings.xml")
        if (stringsFile.exists()) {
            val content = stringsFile.readText()
            for (phrase in prohibitedPhrases) {
                assertFalse(
                    "strings.xml에 금지된 안전 문구('$phrase')가 포함되어서는 안 됩니다.",
                    content.contains(phrase)
                )
            }
        }
    }

    @Test
    fun testSignalEstimateSpeechContainsEstimationAndLimitation() = runTest(testDispatcher) {
        val vm = NavigationViewModel()
        val effects = mutableListOf<NavigationEffect>()
        val job = launch {
            vm.effects.collect { effects.add(it) }
        }

        // 녹색 신호 추정 알림 발화
        vm.announceSignalEstimate("GREEN")
        advanceUntilIdle()

        assertTrue("발화 이벤트가 방출되어야 함", effects.isNotEmpty())
        val guidance = (effects[0] as NavigationEffect.SpeakGuidance).text

        // 1. 추정과 한계가 반드시 포함되어야 함
        assertTrue("추정 고지('추정됩니다') 필수 포함", guidance.contains("추정됩니다"))
        assertTrue("한계 고지('안전을 보장할 수 없습니다') 필수 포함", guidance.contains("안전을 보장할 수 없습니다"))

        // 2. 금지 문구가 절대 포함되어서는 안 됨
        for (prohibited in prohibitedPhrases) {
            assertFalse(
                "신호 추정 안내 음성에 금지 문구('$prohibited')가 포함되어서는 안 됩니다.",
                guidance.contains(prohibited)
            )
        }

        job.cancel()
    }

    @Test
    fun testCrossingAssistDecisionEngineGuidanceContainsLimitationsAndZeroProhibitedPhrases() {
        val engine = kr.safecross.mobile.decision.CrossingDecisionEngine(minConsecutiveGreenFrames = 1)
        val crossing = kr.safecross.mobile.perception.VerifiedCrossingContext("CW-1", 0f, true)
        val pose = kr.safecross.mobile.perception.DevicePose(15f, 0f, 0f)
        val cw = kr.safecross.mobile.perception.CrosswalkObservation(true, null, null, 0f, 0.9f, 0.9f)
        val sig = kr.safecross.mobile.perception.SignalObservation(
            "trk-1",
            kr.safecross.mobile.perception.ObservedSignalState.GREEN,
            0.95f,
            kr.safecross.mobile.perception.NormalizedBox(0f, 0f, 1f, 1f),
            System.nanoTime(),
            kr.safecross.mobile.perception.FrameQuality(0.9f, 0.9f, true)
        )
        val assoc = kr.safecross.mobile.perception.TargetSignalAssociation(true, sig, "OK", 0.9f)

        val decision = engine.evaluate(crossing, pose, cw, assoc, true)
        assertEquals(kr.safecross.mobile.decision.CrossingAssistDecisionState.GREEN_ESTIMATE, decision.state)
        val text = decision.guidanceText
        org.junit.Assert.assertNotNull(text)
        assertTrue(text!!.contains("추정됩니다"))
        assertTrue(text.contains("안전을 보장할 수 없습니다"))

        for (prohibited in prohibitedPhrases) {
            assertFalse(
                "CrossingDecisionEngine 안내 음성에 금지 문구('$prohibited')가 포함되어서는 안 됩니다.",
                text.contains(prohibited)
            )
        }
    }
}
