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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SRD 8.2 필수 안전 시나리오 ST-001 ~ ST-015 전수 자동화 단위 테스트.
 */
class SafetyScenariosStTest {

    private lateinit var engine: CrossingDecisionEngine
    private val standardPose = DevicePose(pitchDegrees = 15f, rollDegrees = 0f, headingDegrees = 0f)
    private val standardCrossing = VerifiedCrossingContext(
        crossingId = "CW-ST-TEST",
        approachBearingDegrees = 0f,
        isFieldVerified = true,
        isAiAllowed = true
    )
    private val standardCrosswalk = CrosswalkObservation(
        hasCrosswalk = true,
        polygon = listOf(PointF(0.2f, 0.8f), PointF(0.8f, 0.8f), PointF(0.7f, 0.3f), PointF(0.3f, 0.3f)),
        entrancePoint = PointF(0.5f, 0.8f),
        directionDegrees = 0f,
        quality = 0.9f,
        confidence = 0.95f
    )

    @Before
    fun setUp() {
        // minUsableFrames = 5 for predictable test sequence
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
        trackId: String = "track-st",
        score: Float = 0.95f,
        qualityUsable: Boolean = true,
        lighting: Float = 0.9f,
        blur: Float = 0.9f,
        timestampNanos: Long = 1_000_000_000L
    ): SignalObservation {
        return SignalObservation(
            ephemeralTrackId = trackId,
            state = state,
            score = score,
            box = NormalizedBox(0.4f, 0.2f, 0.6f, 0.4f),
            frameTimestampNanos = timestampNanos,
            quality = FrameQuality(lighting = lighting, blur = blur, isUsable = qualityUsable),
            modelVersion = "test-model-1.0.0"
        )
    }

    /**
     * ST-001: 적색 신호가 10초 지속 -> RED_ESTIMATE 또는 UNKNOWN, GREEN 금지
     */
    @Test
    fun testSt001_RedSignalContinuesFor10Seconds() {
        var baseTime = 1_000_000_000L
        for (sec in 1..10) {
            baseTime += 1_000_000_000L
            val redSignal = createSignal(ObservedSignalState.RED, timestampNanos = baseTime)
            val assoc = TargetSignalAssociation(true, redSignal, "UNIQUE", 0.95f)
            val input = CrossingDecisionInput(
                monotonicTimeNanos = baseTime,
                crossingContext = standardCrossing,
                devicePose = standardPose,
                crosswalk = standardCrosswalk,
                association = assoc,
                isTiltSuitable = true
            )
            val output = engine.evaluate(input)

            // 적색 유지 중에는 절대 GREEN_ESTIMATE 또는 GREEN_CANDIDATE가 될 수 없음
            assertNotEquals(CrossingState.GREEN_ESTIMATE, output.state)
            assertNotEquals(CrossingState.GREEN_CANDIDATE, output.state)
            assertEquals(CrossingState.RED_ESTIMATE, output.state)
        }
    }

    /**
     * ST-002: 차량 신호는 녹색, 보행자 신호는 적색 -> GREEN 금지
     */
    @Test
    fun testSt002_VehicleGreenPedestrianRedProhibitsGreen() {
        val now = 2_000_000_000L
        // 카메라가 보행자 신호 적색을 식별
        val pedRedSignal = createSignal(ObservedSignalState.RED, timestampNanos = now)
        val assoc = TargetSignalAssociation(true, pedRedSignal, "UNIQUE", 0.95f)

        // 주변 차량 신호가 녹색이거나 가짜 녹색이 유입되더라도 보행 신호가 적색이면 GREEN 절대 금지
        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            isTiltSuitable = true
        )
        val output = engine.evaluate(input)

        assertEquals(CrossingState.RED_ESTIMATE, output.state)
        assertNotEquals(CrossingState.GREEN_ESTIMATE, output.state)
    }

    /**
     * ST-003: 화면에 서로 다른 방향 녹색·적색 동시 존재 -> UNKNOWN
     */
    @Test
    fun testSt003_MultipleDirectionalSignalsProduceUnknown() {
        val now = 3_000_000_000L
        // Associator가 복수 방향 신호로 인해 특정 불가(isUnique = false) 판정
        val ambiguousAssoc = TargetSignalAssociation(
            isUnique = false,
            targetSignal = null,
            reason = "AMBIGUOUS_MULTIPLE_SIGNALS",
            confidence = 0.4f
        )
        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = ambiguousAssoc,
            isTiltSuitable = true
        )
        val output = engine.evaluate(input)

        assertEquals(CrossingState.UNKNOWN, output.state)
        assertEquals("AMBIGUOUS_MULTIPLE_SIGNALS", output.reasonCode)
    }

    /**
     * ST-004: 신호 일부 가림 (FrameQuality 불량) -> UNKNOWN
     */
    @Test
    fun testSt004_SignalPartiallyOccludedProducesUnknown() {
        val now = 4_000_000_000L
        val occludedSignal = createSignal(
            state = ObservedSignalState.GREEN,
            qualityUsable = false, // 가림/흔들림으로 사용 불가
            blur = 0.1f,
            timestampNanos = now
        )
        val assoc = TargetSignalAssociation(true, occludedSignal, "UNIQUE", 0.8f)
        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            isTiltSuitable = true
        )
        val output = engine.evaluate(input)

        assertEquals(CrossingState.UNKNOWN, output.state)
        assertEquals("POOR_FRAME_QUALITY", output.reasonCode)
    }

    /**
     * ST-005: LED 광고판·상점 불빛 -> 보행신호로 선택하지 않음 (신뢰도 미달)
     */
    @Test
    fun testSt005_LedBillboardLowScoreProhibitsGreen() {
        val now = 5_000_000_000L
        // 점수가 0.75f로 0.90f 기준 미달
        val billboardSignal = createSignal(
            state = ObservedSignalState.GREEN,
            score = 0.75f,
            timestampNanos = now
        )
        val assoc = TargetSignalAssociation(true, billboardSignal, "UNIQUE", 0.75f)
        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            isTiltSuitable = true
        )
        val output = engine.evaluate(input)

        assertEquals(CrossingState.UNKNOWN, output.state)
        assertEquals("LOW_CALIBRATED_SCORE", output.reasonCode)
    }

    /**
     * ST-006: 강한 역광·렌즈 오염 -> UNKNOWN + 카메라 조정 안내
     */
    @Test
    fun testSt006_StrongBacklightLensDirtyProducesUnknownWithTiltGuidance() {
        val now = 6_000_000_000L
        val flareSignal = createSignal(
            state = ObservedSignalState.GREEN,
            lighting = 0.15f, // 심한 역광
            timestampNanos = now
        )
        val assoc = TargetSignalAssociation(true, flareSignal, "UNIQUE", 0.95f)
        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            isTiltSuitable = true
        )
        val output = engine.evaluate(input)

        assertEquals(CrossingState.UNKNOWN, output.state)
        assertEquals("POOR_FRAME_QUALITY", output.reasonCode)
        assertTrue(output.guidanceText!!.contains("오염") || output.guidanceText!!.contains("역광"))
    }

    /**
     * ST-007: GPS가 반대편 횡단보도로 점프 (정확도 불량 > 15m) -> UNKNOWN
     */
    @Test
    fun testSt007_GpsJumpPoorAccuracyProhibitsEstimate() {
        val now = 7_000_000_000L
        val greenSignal = createSignal(ObservedSignalState.GREEN, timestampNanos = now)
        val assoc = TargetSignalAssociation(true, greenSignal, "UNIQUE", 0.95f)
        val badGpsLocation = DecisionLocation(
            lat = 35.1500,
            lon = 126.8500,
            accuracyM = 35.0f, // 15m 초과
            bearingDegrees = 180f,
            timestampNanos = now
        )
        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            location = badGpsLocation,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            isTiltSuitable = true
        )
        val output = engine.evaluate(input)

        assertEquals(CrossingState.UNKNOWN, output.state)
        assertEquals("POOR_LOCATION_ACCURACY", output.reasonCode)
    }

    /**
     * ST-008: 앱이 백그라운드로 이동 -> IDLE 전이
     */
    @Test
    fun testSt008_AppTransitionsToBackgroundProducesIdle() {
        val now = 8_000_000_000L
        val cancelInput = CrossingDecisionInput(
            monotonicTimeNanos = now,
            userTrigger = UserTriggerAction.CANCEL_OR_BACKGROUND
        )
        val output = engine.evaluate(cancelInput)

        assertEquals(CrossingState.IDLE, output.state)
        assertEquals("USER_CANCELLED_OR_BACKGROUND", output.reasonCode)
    }

    /**
     * ST-009: 모델 파일 변조 (관측치 품질/상태 UNKNOWN) -> UNKNOWN
     */
    @Test
    fun testSt009_CorruptedModelObservationProducesUnknown() {
        val now = 9_000_000_000L
        val corruptedSignal = createSignal(
            state = ObservedSignalState.UNKNOWN,
            score = 0.0f,
            timestampNanos = now
        )
        val assoc = TargetSignalAssociation(true, corruptedSignal, "UNIQUE", 0.0f)
        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            isTiltSuitable = true
        )
        val output = engine.evaluate(input)

        assertEquals(CrossingState.UNKNOWN, output.state)
    }

    /**
     * ST-010: 서버 kill switch 활성 -> GREEN_ESTIMATE 비활성 + UNKNOWN
     */
    @Test
    fun testSt010_ServerKillSwitchActiveProhibitsGreenEstimate() {
        val now = 10_000_000_000L
        val greenSignal = createSignal(ObservedSignalState.GREEN, timestampNanos = now)
        val assoc = TargetSignalAssociation(true, greenSignal, "UNIQUE", 0.95f)

        // Kill switch 활성화
        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            killSwitch = KillSwitchStatus(isKilled = true, reason = "EMERGENCY_SERVER_STOP"),
            isTiltSuitable = true
        )
        val output = engine.evaluate(input)

        assertEquals(CrossingState.UNKNOWN, output.state)
        assertEquals("KILL_SWITCH_ACTIVE", output.reasonCode)
        assertTrue(output.guidanceText!!.contains("비활성화"))
    }

    /**
     * ST-011: 횡단보도 도색 훼손/미검증 지도 기하 -> UNKNOWN
     */
    @Test
    fun testSt011_UnverifiedCrossingContextProducesUnknown() {
        val now = 11_000_000_000L
        val greenSignal = createSignal(ObservedSignalState.GREEN, timestampNanos = now)
        val assoc = TargetSignalAssociation(true, greenSignal, "UNIQUE", 0.95f)
        val unverifiedCrossing = VerifiedCrossingContext(
            crossingId = "UNVERIFIED-1",
            approachBearingDegrees = 0f,
            isFieldVerified = false, // 미검증
            isAiAllowed = false
        )
        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = unverifiedCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            isTiltSuitable = true
        )
        val output = engine.evaluate(input)

        assertEquals(CrossingState.UNKNOWN, output.state)
        assertEquals("CROSSING_NOT_FIELD_VERIFIED", output.reasonCode)
    }

    /**
     * ST-012: 공식 신호 GREEN, 카메라 RED 또는 반대 -> UNKNOWN, 불일치 사유 코드
     */
    @Test
    fun testSt012_OfficialCameraConflictProducesUnknown() {
        val now = 12_000_000_000L

        // 경우 1: 공식 신호 GREEN, 카메라 RED
        val cameraRed = createSignal(ObservedSignalState.RED, timestampNanos = now)
        val assocRed = TargetSignalAssociation(true, cameraRed, "UNIQUE", 0.95f)
        val officialGreen = OfficialSignalObservation(
            provider = "UTIC",
            intersectionId = "INT-1",
            movementId = "MOV-1",
            state = OfficialSignalState.GREEN,
            sourceTimestampEpochMs = 1700000000000L,
            receivedMonotonicNanos = now
        )
        val input1 = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assocRed,
            officialSignal = officialGreen,
            isTiltSuitable = true
        )
        val output1 = engine.evaluate(input1)
        assertEquals(CrossingState.UNKNOWN, output1.state)
        assertEquals("CONFLICT_OFFICIAL_GREEN_CAMERA_RED", output1.reasonCode)

        // 경우 2: 공식 신호 RED, 카메라 GREEN
        val cameraGreen = createSignal(ObservedSignalState.GREEN, timestampNanos = now)
        val assocGreen = TargetSignalAssociation(true, cameraGreen, "UNIQUE", 0.95f)
        val officialRed = OfficialSignalObservation(
            provider = "UTIC",
            intersectionId = "INT-1",
            movementId = "MOV-1",
            state = OfficialSignalState.RED,
            sourceTimestampEpochMs = 1700000000000L,
            receivedMonotonicNanos = now
        )
        val input2 = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assocGreen,
            officialSignal = officialRed,
            isTiltSuitable = true
        )
        val output2 = engine.evaluate(input2)
        assertEquals(CrossingState.UNKNOWN, output2.state)
        assertEquals("CONFLICT_OFFICIAL_RED_CAMERA_GREEN", output2.reasonCode)
    }

    /**
     * ST-013: 공식 신호가 최대 나이를 초과하거나 생성시각이 역행 -> 관측 폐기, GREEN 금지
     */
    @Test
    fun testSt013_OfficialSignalExpiredOrInvalidTimestampProducesUnknown() {
        val now = 13_000_000_000L
        val cameraGreen = createSignal(ObservedSignalState.GREEN, timestampNanos = now)
        val assocGreen = TargetSignalAssociation(true, cameraGreen, "UNIQUE", 0.95f)

        // 5초 만료 시간을 초과한 오래된 공식 신호 (received 6초 전)
        val expiredOfficial = OfficialSignalObservation(
            provider = "UTIC",
            intersectionId = "INT-1",
            movementId = "MOV-1",
            state = OfficialSignalState.GREEN,
            sourceTimestampEpochMs = 1700000000000L,
            receivedMonotonicNanos = now - 6_000_000_000L,
            maxAgeNanos = 5_000_000_000L
        )
        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assocGreen,
            officialSignal = expiredOfficial,
            isTiltSuitable = true
        )
        val output = engine.evaluate(input)

        assertEquals(CrossingState.UNKNOWN, output.state)
        assertEquals("OFFICIAL_SIGNAL_EXPIRED", output.reasonCode)
    }

    /**
     * ST-014: 다른 신호 box의 RED 뒤 목표 box GREEN -> 같은 트랙 전이로 인정하지 않음
     */
    @Test
    fun testSt014_DifferentTrackIdTransitionRejected() {
        var now = 14_000_000_000L

        // Track-A 에서 RED 관측
        val redTrackA = createSignal(ObservedSignalState.RED, trackId = "track-A", timestampNanos = now)
        val assocA = TargetSignalAssociation(true, redTrackA, "UNIQUE", 0.95f)
        val inputRed = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assocA,
            isTiltSuitable = true
        )
        val outputRed = engine.evaluate(inputRed)
        assertEquals(CrossingState.RED_ESTIMATE, outputRed.state)

        // 바로 다음 프레임에 Track-B 로 GREEN 관측 유입
        now += 100_000_000L
        val greenTrackB = createSignal(ObservedSignalState.GREEN, trackId = "track-B", timestampNanos = now)
        val assocB = TargetSignalAssociation(true, greenTrackB, "UNIQUE", 0.95f)
        val inputGreen = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assocB,
            isTiltSuitable = true
        )
        val outputGreen = engine.evaluate(inputGreen)

        // 다른 트랙의 전환은 즉시 인정되지 않고 UNKNOWN으로 거부
        assertEquals(CrossingState.UNKNOWN, outputGreen.state)
        assertEquals("DIFFERENT_TRACK_TRANSITION_REJECTED", outputGreen.reasonCode)
    }

    /**
     * ST-015: 횡단보도 방향과 선택된 신호의 연결 방향 불일치 -> UNKNOWN
     */
    @Test
    fun testSt015_DirectionMismatchProducesUnknown() {
        val now = 15_000_000_000L
        val greenSignal = createSignal(ObservedSignalState.GREEN, timestampNanos = now)
        val assoc = TargetSignalAssociation(true, greenSignal, "UNIQUE", 0.95f)

        // 횡단보도 진행 방향이 90도 (접근 방향 0도와 90도 차이 -> 35도 한도 초과)
        val mismatchCrosswalk = CrosswalkObservation(
            hasCrosswalk = true,
            polygon = null,
            entrancePoint = null,
            directionDegrees = 90.0f,
            quality = 0.9f,
            confidence = 0.95f
        )
        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing, // approachBearing = 0f
            devicePose = standardPose,
            crosswalk = mismatchCrosswalk,
            association = assoc,
            isTiltSuitable = true
        )
        val output = engine.evaluate(input)

        assertEquals(CrossingState.UNKNOWN, output.state)
        assertEquals("DIRECTION_MISMATCH", output.reasonCode)
    }

    /**
     * SR-F-045, TRD 4.6: DevicePose 센서 데이터가 오래된 경우(stale > 2.0s) 즉각 UNKNOWN으로 차단되는지 검증
     */
    @Test
    fun testSt006_StaleDevicePoseProducesUnknown() {
        val now = 10_000_000_000L
        val greenSignal = createSignal(ObservedSignalState.GREEN, timestampNanos = now)
        val assoc = TargetSignalAssociation(true, greenSignal, "UNIQUE", 0.95f)

        // 3초 전 오래된 센서 자세 데이터 주입 (허용치 2초 초과)
        val stalePose = DevicePose(
            pitchDegrees = 15f,
            rollDegrees = 0f,
            headingDegrees = 0f,
            timestampNanos = now - 3_000_000_000L
        )

        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            location = DecisionLocation(35.15, 126.85, 5f, 0f, now, false),
            crossingContext = standardCrossing,
            devicePose = stalePose,
            crosswalk = standardCrosswalk,
            association = assoc,
            isTiltSuitable = true
        )

        val output = engine.evaluate(input)

        assertEquals(CrossingState.UNKNOWN, output.state)
        assertEquals("INPUT_EXPIRED_DEVICE_POSE", output.reasonCode)
    }
}

