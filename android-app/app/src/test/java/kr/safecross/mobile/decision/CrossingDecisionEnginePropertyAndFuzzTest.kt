package kr.safecross.mobile.decision

import kr.safecross.mobile.decision.model.CrossingDecisionInput
import kr.safecross.mobile.decision.model.CrossingState
import kr.safecross.mobile.decision.model.DecisionLocation
import kr.safecross.mobile.decision.model.KillSwitchStatus
import kr.safecross.mobile.decision.model.OfficialSignalObservation
import kr.safecross.mobile.decision.model.OfficialSignalState
import kr.safecross.mobile.decision.model.UserTriggerAction
import kr.safecross.mobile.perception.CrosswalkObservation
import kr.safecross.mobile.perception.DevicePose
import kr.safecross.mobile.perception.FrameQuality
import kr.safecross.mobile.perception.NormalizedBox
import kr.safecross.mobile.perception.ObservedSignalState
import kr.safecross.mobile.perception.PointF
import kr.safecross.mobile.perception.SignalObservation
import kr.safecross.mobile.perception.TargetSignalAssociation
import kr.safecross.mobile.perception.VerifiedCrossingContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * Table-Driven, Property-Based, Fuzz 및 개인정보 보호 테스트 스위트.
 */
class CrossingDecisionEnginePropertyAndFuzzTest {

    private lateinit var engine: CrossingDecisionEngine
    private val standardPose = DevicePose(pitchDegrees = 15f, rollDegrees = 0f, headingDegrees = 0f)
    private val standardCrossing = VerifiedCrossingContext(
        crossingId = "CW-PROP-TEST",
        approachBearingDegrees = 0f,
        isFieldVerified = true,
        isAiAllowed = true
    )
    private val standardCrosswalk = CrosswalkObservation(
        hasCrosswalk = true,
        polygon = listOf(PointF(0.2f, 0.8f), PointF(0.8f, 0.8f)),
        entrancePoint = PointF(0.5f, 0.8f),
        directionDegrees = 0f,
        quality = 0.9f,
        confidence = 0.95f
    )

    @Before
    fun setUp() {
        engine = CrossingDecisionEngine(
            config = CrossingDecisionConfig(
                minUsableFrames = 5,
                windowNanos = 2_000_000_000L,
                minGreenAgreement = 0.90f,
                minCalibratedScore = 0.90f
            )
        )
    }

    private fun createSignal(
        state: ObservedSignalState,
        trackId: String = "track-prop",
        score: Float = 0.95f,
        timestampNanos: Long = 1_000_000_000L
    ): SignalObservation {
        return SignalObservation(
            ephemeralTrackId = trackId,
            state = state,
            score = score,
            box = NormalizedBox(0.4f, 0.2f, 0.6f, 0.4f),
            frameTimestampNanos = timestampNanos,
            quality = FrameQuality(0.9f, 0.9f, true),
            modelVersion = "test-1.0.0"
        )
    }

    // -------------------------------------------------------------
    // 1. Table-Driven Tests (상태 전이 매트릭스)
    // -------------------------------------------------------------
    data class TableTestCase(
        val name: String,
        val signalState: ObservedSignalState,
        val isFieldVerified: Boolean,
        val isAiAllowed: Boolean,
        val isTiltSuitable: Boolean,
        val isUniqueAssoc: Boolean,
        val expectedState: CrossingState
    )

    @Test
    fun testTableDrivenDecisionMatrix() {
        val testCases = listOf(
            TableTestCase("정상 적색 관측", ObservedSignalState.RED, true, true, true, true, CrossingState.RED_ESTIMATE),
            TableTestCase("미검증 횡단보도 녹색", ObservedSignalState.GREEN, false, true, true, true, CrossingState.UNKNOWN),
            TableTestCase("AI 미허용 횡단보도 녹색", ObservedSignalState.GREEN, true, false, true, true, CrossingState.UNKNOWN),
            TableTestCase("스마트폰 각도 불량 녹색", ObservedSignalState.GREEN, true, true, false, true, CrossingState.UNKNOWN),
            TableTestCase("모호한 다중 신호 녹색", ObservedSignalState.GREEN, true, true, true, false, CrossingState.UNKNOWN),
            TableTestCase("단일 녹색(첫 프레임)", ObservedSignalState.GREEN, true, true, true, true, CrossingState.GREEN_CANDIDATE)
        )

        for (tc in testCases) {
            engine.reset()
            val now = 1_000_000_000L
            val sig = createSignal(tc.signalState, timestampNanos = now)
            val assoc = TargetSignalAssociation(tc.isUniqueAssoc, sig, if (tc.isUniqueAssoc) "UNIQUE" else "AMBIGUOUS", 0.95f)
            val crossing = VerifiedCrossingContext(
                crossingId = "CW-TEST",
                approachBearingDegrees = 0f,
                isFieldVerified = tc.isFieldVerified,
                isAiAllowed = tc.isAiAllowed
            )
            val input = CrossingDecisionInput(
                monotonicTimeNanos = now,
                crossingContext = crossing,
                devicePose = standardPose,
                crosswalk = standardCrosswalk,
                association = assoc,
                isTiltSuitable = tc.isTiltSuitable
            )

            val output = engine.evaluate(input)
            assertEquals("테스트 케이스 실패: ${tc.name}", tc.expectedState, output.state)
        }
    }

    // -------------------------------------------------------------
    // 2. Property-Based Invariant Tests
    // -------------------------------------------------------------

    /**
     * 불변식 1: GREEN_CANDIDATE는 사용자에게 일체 노출되지 않음 (guidanceText == null && hapticType == null)
     */
    @Test
    fun testInvariant_GreenCandidateNeverExposedToUser() {
        val rng = Random(42)
        for (i in 1..20) {
            engine.reset()
            val frameCount = rng.nextInt(1, 4) // 1~3 프레임 (5프레임 미달)
            for (f in 1..frameCount) {
                val now = 1_000_000_000L + f * 100_000_000L
                val sig = createSignal(ObservedSignalState.GREEN, timestampNanos = now)
                val assoc = TargetSignalAssociation(true, sig, "UNIQUE", 0.95f)
                val input = CrossingDecisionInput(
                    monotonicTimeNanos = now,
                    crossingContext = standardCrossing,
                    devicePose = standardPose,
                    crosswalk = standardCrosswalk,
                    association = assoc,
                    isTiltSuitable = true
                )
                val output = engine.evaluate(input)
                assertEquals(CrossingState.GREEN_CANDIDATE, output.state)
                assertNull("GREEN_CANDIDATE에서 guidanceText가 null이어야 합니다", output.guidanceText)
                assertNull("GREEN_CANDIDATE에서 hapticType이 null이어야 합니다", output.hapticType)
            }
        }
    }

    /**
     * 불변식 2: 관측 중 적색 신호가 존재하면 절대 GREEN_ESTIMATE가 될 수 없음
     */
    @Test
    fun testInvariant_RedSignalProhibitsGreenEstimate() {
        engine.reset()
        var now = 1_000_000_000L
        val sigGreen = createSignal(ObservedSignalState.GREEN, timestampNanos = now)
        val assocGreen = TargetSignalAssociation(true, sigGreen, "UNIQUE", 0.95f)

        // 4프레임 녹색 누적
        for (i in 1..4) {
            now += 100_000_000L
            engine.evaluate(
                CrossingDecisionInput(
                    monotonicTimeNanos = now,
                    crossingContext = standardCrossing,
                    devicePose = standardPose,
                    crosswalk = standardCrosswalk,
                    association = assocGreen,
                    isTiltSuitable = true
                )
            )
        }

        // 5번째 프레임에 적색 신호 유입
        now += 100_000_000L
        val sigRed = createSignal(ObservedSignalState.RED, timestampNanos = now)
        val assocRed = TargetSignalAssociation(true, sigRed, "UNIQUE", 0.95f)
        val outputRed = engine.evaluate(
            CrossingDecisionInput(
                monotonicTimeNanos = now,
                crossingContext = standardCrossing,
                devicePose = standardPose,
                crosswalk = standardCrosswalk,
                association = assocRed,
                isTiltSuitable = true
            )
        )

        assertEquals(CrossingState.RED_ESTIMATE, outputRed.state)
        assertNotEquals(CrossingState.GREEN_ESTIMATE, outputRed.state)
    }

    /**
     * 불변식 3: 킬스위치가 활성화되어 있으면 절대 녹색 상태가 될 수 없음
     */
    @Test
    fun testInvariant_KillSwitchAlwaysProhibitsGreen() {
        val now = 2_000_000_000L
        val sig = createSignal(ObservedSignalState.GREEN, timestampNanos = now)
        val assoc = TargetSignalAssociation(true, sig, "UNIQUE", 0.99f)
        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            killSwitch = KillSwitchStatus(isKilled = true, reason = "KILL_ALL"),
            isTiltSuitable = true
        )
        val output = engine.evaluate(input)
        assertNotEquals(CrossingState.GREEN_ESTIMATE, output.state)
        assertNotEquals(CrossingState.GREEN_CANDIDATE, output.state)
        assertEquals(CrossingState.UNKNOWN, output.state)
    }

    /**
     * 불변식 4: 결정론성(Determinism) - 동일 입력 시퀀스는 항상 동일한 상태 및 사유 코드 목록 산출
     */
    @Test
    fun testInvariant_DeterminismExactSequenceReproducibility() {
        fun runSequence(): List<Pair<CrossingState, String>> {
            engine.reset()
            val results = mutableListOf<Pair<CrossingState, String>>()
            var t = 10_000_000_000L

            // 1. 적색 2프레임
            for (i in 1..2) {
                t += 100_000_000L
                val r = engine.evaluate(
                    CrossingDecisionInput(
                        monotonicTimeNanos = t,
                        crossingContext = standardCrossing,
                        devicePose = standardPose,
                        crosswalk = standardCrosswalk,
                        association = TargetSignalAssociation(true, createSignal(ObservedSignalState.RED, timestampNanos = t), "UNIQUE", 0.95f),
                        isTiltSuitable = true
                    )
                )
                results.add(r.state to r.reasonCode)
            }

            // 2. 녹색 5프레임
            for (i in 1..5) {
                t += 100_000_000L
                val r = engine.evaluate(
                    CrossingDecisionInput(
                        monotonicTimeNanos = t,
                        crossingContext = standardCrossing,
                        devicePose = standardPose,
                        crosswalk = standardCrosswalk,
                        association = TargetSignalAssociation(true, createSignal(ObservedSignalState.GREEN, timestampNanos = t), "UNIQUE", 0.95f),
                        isTiltSuitable = true
                    )
                )
                results.add(r.state to r.reasonCode)
            }
            return results
        }

        val run1 = runSequence()
        val run2 = runSequence()

        assertEquals("결정론적 실행 결과의 길이가 일치해야 합니다", run1.size, run2.size)
        for (i in run1.indices) {
            assertEquals("단계 $i 에서 상태 및 사유 코드가 100% 일치해야 합니다", run1[i], run2[i])
        }
    }

    // -------------------------------------------------------------
    // 3. Fuzz Tests (무작위/비정상 입력에 대한 강건성)
    // -------------------------------------------------------------
    @Test
    fun testFuzz_RandomNoisyInputsNeverThrowUnhandledExceptions() {
        val rng = Random(12345)
        engine.reset()
        var t = 1_000_000_000L

        for (step in 1..200) {
            // 무작위 시각 (간혹 역행)
            t += rng.nextLong(-50_000_000L, 200_000_000L)

            val randomState = ObservedSignalState.values()[rng.nextInt(ObservedSignalState.values().size)]
            val randomScore = rng.nextFloat() * 1.5f - 0.2f // -0.2 ~ 1.3
            val randomTrack = "track-${rng.nextInt(1, 4)}"
            val randomTilt = rng.nextBoolean()
            val randomVerified = rng.nextBoolean()
            val randomAssoc = rng.nextBoolean()

            val sig = SignalObservation(
                ephemeralTrackId = randomTrack,
                state = randomState,
                score = randomScore,
                box = NormalizedBox(0f, 0f, 1f, 1f),
                frameTimestampNanos = t,
                quality = FrameQuality(rng.nextFloat(), rng.nextFloat(), rng.nextBoolean()),
                modelVersion = "fuzz"
            )

            val input = CrossingDecisionInput(
                monotonicTimeNanos = t,
                crossingContext = if (randomVerified) standardCrossing else null,
                devicePose = standardPose,
                crosswalk = standardCrosswalk,
                association = TargetSignalAssociation(randomAssoc, sig, "FUZZ", randomScore),
                isTiltSuitable = randomTilt
            )

            // 크래시 없이 안전하게 실행되어야 함
            val output = engine.evaluate(input)
            assertNotNull(output)
            assertNotNull(output.state)
            assertNotNull(output.reasonCode)
        }
    }

    // -------------------------------------------------------------
    // 4. Privacy & Zero Leakage Verification
    // -------------------------------------------------------------
    @Test
    fun testPrivacy_TransitionLogContainsZeroCoordinatesAndZeroPixels() {
        engine.reset()
        val now = 5_000_000_000L
        val sig = createSignal(ObservedSignalState.RED, timestampNanos = now)
        val assoc = TargetSignalAssociation(true, sig, "UNIQUE", 0.95f)
        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            location = DecisionLocation(35.1234567, 126.9876543, 5.0f, 45f, now),
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            isTiltSuitable = true
        )

        val output = engine.evaluate(input)
        val log = output.transitionLog
        assertNotNull("상태 변경 시 전이 로그가 생성되어야 합니다", log)

        val logString = log.toString()
        // 위경도 좌표 부동소수점 원문이 로그 문자열에 포함되어서는 안 됨
        assertFalse("로그에 위도(35.1234567)가 포함되어서는 안 됩니다", logString.contains("35.1234567"))
        assertFalse("로그에 경도(126.9876543)가 포함되어서는 안 됩니다", logString.contains("126.9876543"))
        assertFalse("로그에 픽셀이나 바운딩 박스 상세가 포함되어서는 안 됩니다", logString.contains("NormalizedBox"))
    }
}
