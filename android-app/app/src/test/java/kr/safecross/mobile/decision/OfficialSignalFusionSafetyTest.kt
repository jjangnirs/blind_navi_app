package kr.safecross.mobile.decision

import kr.safecross.mobile.decision.model.CrossingDecisionInput
import kr.safecross.mobile.decision.model.CrossingState
import kr.safecross.mobile.decision.model.DecisionLocation
import kr.safecross.mobile.decision.model.OfficialSignalObservation
import kr.safecross.mobile.decision.model.OfficialSignalState
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 공식 실시간 신호와 온디바이스 카메라 지각 안전 융합 테스트 (PR-F-016, SR-F-058, ST-012~015, TRD 4.7).
 *
 * 수용 기준:
 * - 정적 데이터가 동적 신호로 변환되는 경로 0건.
 * - 모든 만료·방향미매핑·충돌 fixture에서 GREEN_ESTIMATE 0건.
 * - 공식 신호 단독 GREEN 출력은 feature flag 기본 OFF.
 * - 공식 신호 없을 때 카메라-only 경로는 기존 안전 게이트 엄격 요구.
 * - 로그/전이 캡처에 원시 위치와 카메라 프레임 0건.
 */
class OfficialSignalFusionSafetyTest {

    private lateinit var engine: CrossingDecisionEngine

    private val standardCrossing = VerifiedCrossingContext(
        crossingId = "CW-FUSION-01",
        approachBearingDegrees = 0f,
        isFieldVerified = true,
        isAiAllowed = true
    )

    private val standardPose = DevicePose(pitchDegrees = 10f, rollDegrees = 0f, headingDegrees = 0f)

    private val standardCrosswalk = CrosswalkObservation(
        hasCrosswalk = true,
        polygon = listOf(PointF(0.2f, 0.8f), PointF(0.8f, 0.8f), PointF(0.6f, 0.3f), PointF(0.4f, 0.3f)),
        entrancePoint = PointF(0.5f, 0.8f),
        directionDegrees = 0f,
        quality = 0.9f,
        confidence = 0.95f
    )

    private val standardLocation = DecisionLocation(
        lat = 35.150000,
        lon = 126.850000,
        accuracyM = 5.0f,
        bearingDegrees = 0f,
        timestampNanos = 10_000_000_000L
    )

    @Before
    fun setUp() {
        engine = CrossingDecisionEngine(minConsecutiveGreenFrames = 5)
    }

    private fun createCameraSignal(state: ObservedSignalState, trackId: String = "track-01", timestampNanos: Long = 10_000_000_000L): SignalObservation {
        return SignalObservation(
            ephemeralTrackId = trackId,
            state = state,
            score = 0.95f,
            box = NormalizedBox(0.45f, 0.1f, 0.55f, 0.3f),
            frameTimestampNanos = timestampNanos,
            quality = FrameQuality(lighting = 0.95f, blur = 0.95f, isUsable = true)
        )
    }

    @Test
    fun testOfficialGreenVsCameraRedProducesZeroFalseGreenAndConflictUnknown() {
        val now = 10_000_000_000L
        val cameraRed = createCameraSignal(ObservedSignalState.RED, timestampNanos = now)
        val assoc = TargetSignalAssociation(true, cameraRed, "UNIQUE", 0.95f)

        val officialGreen = OfficialSignalObservation(
            provider = "KOROAD_REALTIME",
            intersectionId = "INT-101",
            movementId = "MOV-NORTH",
            state = OfficialSignalState.GREEN,
            sourceTimestampEpochMs = System.currentTimeMillis() - 300,
            receivedMonotonicNanos = now
        )

        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            location = standardLocation,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            officialSignal = officialGreen,
            isTiltSuitable = true
        )

        val output = engine.evaluate(input)

        assertEquals("State must be UNKNOWN on conflict", CrossingState.UNKNOWN, output.state)
        assertNotEquals("Must NEVER produce GREEN_ESTIMATE on conflict", CrossingState.GREEN_ESTIMATE, output.state)
        assertTrue(
            "Conflict reason code expected",
            output.reasonCode.contains("CONFLICT") || output.reasonCode == "OFFICIAL_CAMERA_CONFLICT"
        )
    }

    @Test
    fun testOfficialRedVsCameraGreenProducesZeroFalseGreenAndUnknown() {
        val now = 10_000_000_000L
        val cameraGreen = createCameraSignal(ObservedSignalState.GREEN, timestampNanos = now)
        val assoc = TargetSignalAssociation(true, cameraGreen, "UNIQUE", 0.95f)

        val officialRed = OfficialSignalObservation(
            provider = "KOROAD_REALTIME",
            intersectionId = "INT-101",
            movementId = "MOV-NORTH",
            state = OfficialSignalState.RED,
            sourceTimestampEpochMs = System.currentTimeMillis() - 300,
            receivedMonotonicNanos = now
        )

        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            location = standardLocation,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            officialSignal = officialRed,
            isTiltSuitable = true
        )

        val output = engine.evaluate(input)

        assertEquals("State must be UNKNOWN on conflict", CrossingState.UNKNOWN, output.state)
        assertNotEquals("Must NEVER produce GREEN_ESTIMATE on conflict", CrossingState.GREEN_ESTIMATE, output.state)
    }

    @Test
    fun testOfficialSignalOnlyWithoutCameraProhibitsGreenByDefaultFeatureFlag() {
        val now = 10_000_000_000L

        val officialGreen = OfficialSignalObservation(
            provider = "KOROAD_REALTIME",
            intersectionId = "INT-101",
            movementId = "MOV-NORTH",
            state = OfficialSignalState.GREEN,
            sourceTimestampEpochMs = System.currentTimeMillis() - 300,
            receivedMonotonicNanos = now
        )

        // 카메라 신호 부재 (association = null) 및 allowOfficialOnlyGreen = false (기본값)
        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            location = standardLocation,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = null,
            officialSignal = officialGreen,
            allowOfficialOnlyGreen = false // 기본값 OFF
        )

        val output = engine.evaluate(input)

        assertEquals("Must be UNKNOWN when official-only green is disabled", CrossingState.UNKNOWN, output.state)
        assertEquals("OFFICIAL_ONLY_GREEN_DISABLED", output.reasonCode)
        assertNotEquals(CrossingState.GREEN_ESTIMATE, output.state)
    }

    @Test
    fun testUnmappedMovementRejectsOfficialSignalAndProducesUnknown() {
        val now = 10_000_000_000L
        val cameraGreen = createCameraSignal(ObservedSignalState.GREEN, timestampNanos = now)
        val assoc = TargetSignalAssociation(true, cameraGreen, "UNIQUE", 0.95f)

        // UNMAPPED movement ID
        val unmappedOfficial = OfficialSignalObservation(
            provider = "KOROAD_REALTIME",
            intersectionId = "INT-101",
            movementId = "UNMAPPED", // 미매핑
            state = OfficialSignalState.GREEN,
            sourceTimestampEpochMs = System.currentTimeMillis() - 300,
            receivedMonotonicNanos = now
        )

        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            location = standardLocation,
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            officialSignal = unmappedOfficial
        )

        val output = engine.evaluate(input)
        assertEquals(CrossingState.UNKNOWN, output.state)
        assertEquals("UNMAPPED_MOVEMENT", output.reasonCode)
    }

    @Test
    fun testStaleOfficialSignalRejectedAndProducesUnknown() {
        val now = 20_000_000_000L
        val cameraGreen = createCameraSignal(ObservedSignalState.GREEN, timestampNanos = now)
        val assoc = TargetSignalAssociation(true, cameraGreen, "UNIQUE", 0.95f)

        // 6초 전 수신된 만료 신호 (maxAge = 5초)
        val staleOfficial = OfficialSignalObservation(
            provider = "KOROAD_REALTIME",
            intersectionId = "INT-101",
            movementId = "MOV-NORTH",
            state = OfficialSignalState.GREEN,
            sourceTimestampEpochMs = System.currentTimeMillis() - 6500,
            receivedMonotonicNanos = now - 6_000_000_000L, // 6초 경과
            maxAgeNanos = 5_000_000_000L
        )

        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            location = standardLocation.copy(timestampNanos = now),
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc,
            officialSignal = staleOfficial
        )

        val output = engine.evaluate(input)
        assertEquals(CrossingState.UNKNOWN, output.state)
        assertEquals("OFFICIAL_SIGNAL_EXPIRED", output.reasonCode)
    }

    @Test
    fun testCameraOnlyPathMaintainsAllExistingSafetyGatesWhenOfficialSignalAbsent() {
        var now = 10_000_000_000L

        // 공식 실시간 신호 없이 (officialSignal = null) 순수 카메라 파이프라인
        // 1~4프레임: GREEN_CANDIDATE 유지 (사용자에게 노출 금지)
        for (i in 1..4) {
            now += 100_000_000L
            val sig = createCameraSignal(ObservedSignalState.GREEN, trackId = "track-stable", timestampNanos = now)
            val assoc = TargetSignalAssociation(true, sig, "UNIQUE", 0.95f)
            val input = CrossingDecisionInput(
                monotonicTimeNanos = now,
                crossingContext = standardCrossing,
                location = standardLocation.copy(timestampNanos = now),
                devicePose = standardPose,
                crosswalk = standardCrosswalk,
                association = assoc,
                officialSignal = null // 공식 신호 없음
            )
            val out = engine.evaluate(input)
            assertEquals("Frames 1-4 must stay in GREEN_CANDIDATE", CrossingState.GREEN_CANDIDATE, out.state)
        }

        // 5번째 프레임: 합의 임계치 달성 -> GREEN_ESTIMATE 전이 (한계 고지문 포함)
        now += 100_000_000L
        val sig5 = createCameraSignal(ObservedSignalState.GREEN, trackId = "track-stable", timestampNanos = now)
        val assoc5 = TargetSignalAssociation(true, sig5, "UNIQUE", 0.95f)
        val input5 = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = standardCrossing,
            location = standardLocation.copy(timestampNanos = now),
            devicePose = standardPose,
            crosswalk = standardCrosswalk,
            association = assoc5,
            officialSignal = null
        )
        val out5 = engine.evaluate(input5)
        assertEquals(CrossingState.GREEN_ESTIMATE, out5.state)
        assertTrue(out5.guidanceText!!.contains("녹색으로 추정됩니다"))
        assertTrue(out5.guidanceText!!.contains("안전을 보장할 수 없습니다"))
    }
}
