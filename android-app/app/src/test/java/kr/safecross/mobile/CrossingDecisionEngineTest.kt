package kr.safecross.mobile

import kr.safecross.mobile.decision.CrossingAssistDecisionState
import kr.safecross.mobile.decision.CrossingDecisionEngine
import kr.safecross.mobile.guidance.HapticFeedbackType
import kr.safecross.mobile.perception.CrosswalkObservation
import kr.safecross.mobile.perception.DevicePose
import kr.safecross.mobile.perception.FrameQuality
import kr.safecross.mobile.perception.NormalizedBox
import kr.safecross.mobile.perception.ObservedSignalState
import kr.safecross.mobile.perception.SignalObservation
import kr.safecross.mobile.perception.TargetSignalAssociation
import kr.safecross.mobile.perception.VerifiedCrossingContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 횡단 보조 안전 결정 상태기계 단위 테스트 (SR-F-044, SR-F-046, SR-F-054).
 */
class CrossingDecisionEngineTest {

    private lateinit var engine: CrossingDecisionEngine
    private val standardPose = DevicePose(pitchDegrees = 15f, rollDegrees = 0f, headingDegrees = 0f)
    private val standardCrossing = VerifiedCrossingContext(
        crossingId = "CW-TEST-001",
        approachBearingDegrees = 0f,
        isFieldVerified = true
    )
    private val standardCrosswalk = CrosswalkObservation(
        hasCrosswalk = true,
        polygon = null,
        entrancePoint = null,
        directionDegrees = 0f,
        quality = 0.9f,
        confidence = 0.95f
    )

    @Before
    fun setUp() {
        engine = CrossingDecisionEngine(minConsecutiveGreenFrames = 5)
    }

    private fun createSignal(state: ObservedSignalState, trackId: String = "track-1"): SignalObservation {
        return SignalObservation(
            ephemeralTrackId = trackId,
            state = state,
            score = 0.95f,
            box = NormalizedBox(0.4f, 0.2f, 0.6f, 0.4f),
            frameTimestampNanos = System.nanoTime(),
            quality = FrameQuality(0.9f, 0.9f, true)
        )
    }

    @Test
    fun testSingleFakeGreenDoesNotProduceGreenEstimate() {
        // Given: 적색 관측
        val redSignal = createSignal(ObservedSignalState.RED)
        val redAssoc = TargetSignalAssociation(true, redSignal, "UNIQUE", 0.9f)
        val redDecision = engine.evaluate(standardCrossing, standardPose, standardCrosswalk, redAssoc, true)
        assertEquals(CrossingAssistDecisionState.RED_ESTIMATE, redDecision.state)

        // When: 단 1회 프레임의 Fake GREEN 관측 유입 (SR-F-044)
        val singleGreenSignal = createSignal(ObservedSignalState.GREEN)
        val greenAssoc = TargetSignalAssociation(true, singleGreenSignal, "UNIQUE", 0.9f)
        val singleGreenDecision = engine.evaluate(standardCrossing, standardPose, standardCrosswalk, greenAssoc, true)

        // Then: 절대 GREEN_ESTIMATE로 사용자에게 안내되지 않음!
        assertNotEquals(CrossingAssistDecisionState.GREEN_ESTIMATE, singleGreenDecision.state)
        assertEquals(CrossingAssistDecisionState.GREEN_CANDIDATE, singleGreenDecision.state)
        assertNull(singleGreenDecision.guidanceText) // 후보 분석 중에는 침묵
        assertEquals(1, singleGreenDecision.consecutiveGreenCount)

        // And: 다음 프레임에 다시 RED로 복귀하면 카운터 즉시 리셋
        val nextRedDecision = engine.evaluate(standardCrossing, standardPose, standardCrosswalk, redAssoc, true)
        assertEquals(CrossingAssistDecisionState.RED_ESTIMATE, nextRedDecision.state)
        assertEquals(0, nextRedDecision.consecutiveGreenCount)
    }

    @Test
    fun testSustainedGreenSequenceProducesGreenEstimateWithDisclaimer() {
        val greenSignal = createSignal(ObservedSignalState.GREEN)
        val greenAssoc = TargetSignalAssociation(true, greenSignal, "UNIQUE", 0.9f)

        // 1~4 프레임: GREEN_CANDIDATE 유지
        for (i in 1..4) {
            val decision = engine.evaluate(standardCrossing, standardPose, standardCrosswalk, greenAssoc, true)
            assertEquals(CrossingAssistDecisionState.GREEN_CANDIDATE, decision.state)
            assertEquals(i, decision.consecutiveGreenCount)
            assertNull(decision.guidanceText)
        }

        // 5 프레임째: 연속 합의 충족 -> GREEN_ESTIMATE 전환
        val finalDecision = engine.evaluate(standardCrossing, standardPose, standardCrosswalk, greenAssoc, true)
        assertEquals(CrossingAssistDecisionState.GREEN_ESTIMATE, finalDecision.state)
        assertEquals(5, finalDecision.consecutiveGreenCount)
        assertNotNull(finalDecision.guidanceText)
        assertTrue(finalDecision.guidanceText!!.contains("녹색으로 추정됩니다"))
        assertTrue(finalDecision.guidanceText!!.contains("안전을 보장할 수 없습니다"))
        assertEquals(HapticFeedbackType.GREEN_ESTIMATE, finalDecision.hapticType)
    }

    @Test
    fun testNoCrossingContextProducesUnknown() {
        val greenSignal = createSignal(ObservedSignalState.GREEN)
        val assoc = TargetSignalAssociation(true, greenSignal, "UNIQUE", 0.9f)

        // When: 횡단보도 문맥이 null인 경우 (SR-F-054)
        val decision = engine.evaluate(
            crossingContext = null,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            isTiltSuitable = true
        )

        // Then: 무조건 UNKNOWN
        assertEquals(CrossingAssistDecisionState.UNKNOWN, decision.state)
        assertTrue(decision.guidanceText!!.contains("위치 정보가 없어"))
        assertEquals(HapticFeedbackType.UNKNOWN_CAUTION, decision.hapticType)
    }

    @Test
    fun testAmbiguousMultipleSignalsProduceUnknown() {
        // When: 복수 신호로 인해 연결이 모호한 경우 (SR-F-046)
        val ambiguousAssoc = TargetSignalAssociation(
            isUnique = false,
            targetSignal = null,
            reason = "AMBIGUOUS_MULTIPLE_SIGNALS",
            confidence = 0.3f
        )
        val decision = engine.evaluate(
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = ambiguousAssoc,
            isTiltSuitable = true
        )

        // Then: 무조건 UNKNOWN
        assertEquals(CrossingAssistDecisionState.UNKNOWN, decision.state)
        assertTrue(decision.guidanceText!!.contains("신호가 모호하거나"))
        assertEquals(HapticFeedbackType.UNKNOWN_CAUTION, decision.hapticType)
    }

    @Test
    fun testWrongDirectionProducesUnknown() {
        // When: 방향 불일치 발생
        val wrongDirAssoc = TargetSignalAssociation(
            isUnique = false,
            targetSignal = null,
            reason = "DIRECTION_MISMATCH",
            confidence = 0.2f
        )
        val decision = engine.evaluate(
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = wrongDirAssoc,
            isTiltSuitable = true
        )

        // Then: 무조건 UNKNOWN
        assertEquals(CrossingAssistDecisionState.UNKNOWN, decision.state)
    }

    @Test
    fun testUnsuitableTiltProducesUnknown() {
        val greenSignal = createSignal(ObservedSignalState.GREEN)
        val assoc = TargetSignalAssociation(true, greenSignal, "UNIQUE", 0.9f)

        // When: 스마트폰 각도 불량 (바닥 또는 하늘을 향함)
        val decision = engine.evaluate(
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            isTiltSuitable = false
        )

        // Then: UNKNOWN
        assertEquals(CrossingAssistDecisionState.UNKNOWN, decision.state)
        assertTrue(decision.guidanceText!!.contains("올바른 각도"))
    }
}
