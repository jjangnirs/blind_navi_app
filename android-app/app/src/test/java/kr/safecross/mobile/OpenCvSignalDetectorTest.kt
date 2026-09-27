package kr.safecross.mobile

import kr.safecross.mobile.decision.CrossingDecisionConfig
import kr.safecross.mobile.decision.CrossingDecisionEngine
import kr.safecross.mobile.decision.model.CrossingDecisionInput
import kr.safecross.mobile.decision.model.CrossingState
import kr.safecross.mobile.perception.CrosswalkObservation
import kr.safecross.mobile.perception.DevicePose
import kr.safecross.mobile.perception.FrameQuality
import kr.safecross.mobile.perception.NormalizedBox
import kr.safecross.mobile.perception.ObservedSignalState
import kr.safecross.mobile.perception.OpenCvBridge
import kr.safecross.mobile.perception.OpenCvSignalDetector
import kr.safecross.mobile.perception.SignalObservation
import kr.safecross.mobile.perception.TargetSignalAssociation
import kr.safecross.mobile.perception.VerifiedCrossingContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * OpenCV 연동 및 화면-음성 동기화, 적색 핑퐁(Thrashing) 방지 검증 테스트 (ADR-030).
 */
class OpenCvSignalDetectorTest {

    @Test
    fun testOpenCvBridgeGracefulHandlingOnJvm() {
        // JVM 테스트 환경에서는 네이티브 libopencv_java4.so가 없으므로 크래시 없이 false를 반환하고 안전하게 fallback해야 함
        val initialized = OpenCvBridge.init()
        // JVM 환경에서는 false이거나 이미 로드된 경우 true여도 크래시가 없어야 함
        val dummyBuffer = ByteBuffer.allocateDirect(100 * 100 * 4)
        val result = OpenCvSignalDetector.detectInRoi(
            dummyBuffer, 100, 100, 10, 90, 10, 90
        )
        if (!initialized) {
            assertNull("OpenCV 네이티브 미로드 시 안전하게 null을 반환하여 코틀린 파이프라인으로 fallback해야 함", result)
        }
    }

    @Test
    fun testRedInGreenPhaseDoesNotThrashOnSingleFrameFlicker() {
        val engine = CrossingDecisionEngine(minConsecutiveGreenFrames = 3)
        val crossing = VerifiedCrossingContext("CW-TEST-1", 0.0f, isFieldVerified = true)
        val crosswalk = CrosswalkObservation(true, null, null, 0f, 0.9f, 0.9f)
        val pose = DevicePose(0f, 0f, 0f)

        // 1. 녹색 신호 3프레임 연속 누적으로 GREEN_ESTIMATE 도달
        for (i in 0 until 3) {
            val greenObs = SignalObservation(
                ephemeralTrackId = "track-1",
                state = ObservedSignalState.GREEN,
                score = 0.95f,
                box = NormalizedBox(0.40f, 0.40f, 0.50f, 0.50f),
                frameTimestampNanos = (10 + i) * 100_000_000L,
                quality = FrameQuality(1.0f, 1.0f, true)
            )
            val assoc = TargetSignalAssociation(true, greenObs, "SINGLE_TARGET_CONFIRMED", 0.95f)
            val output = engine.evaluate(
                CrossingDecisionInput(
                    crossingContext = crossing,
                    crosswalk = crosswalk,
                    association = assoc,
                    location = null,
                    devicePose = pose,
                    officialSignal = null,
                    isTiltSuitable = true,
                    monotonicTimeNanos = (10 + i) * 100_000_000L
                )
            )
            if (i == 2) {
                assertEquals(CrossingState.GREEN_ESTIMATE, output.state)
            }
        }

        // 2. 단 1프레임(33ms) 동안 차량등이나 반사광으로 적색 신호가 순간 유입됨
        val redTransientObs = SignalObservation(
            ephemeralTrackId = "track-2",
            state = ObservedSignalState.RED,
            score = 0.95f,
            box = NormalizedBox(0.50f, 0.40f, 0.52f, 0.42f),
            frameTimestampNanos = 13 * 100_000_000L,
            quality = FrameQuality(1.0f, 1.0f, true)
        )
        val assocRedTransient = TargetSignalAssociation(true, redTransientObs, "SINGLE_TARGET_CONFIRMED", 0.95f)
        val transientOutput = engine.evaluate(
            CrossingDecisionInput(
                crossingContext = crossing,
                crosswalk = crosswalk,
                association = assocRedTransient,
                location = null,
                devicePose = pose,
                officialSignal = null,
                isTiltSuitable = true,
                monotonicTimeNanos = 13 * 100_000_000L
            )
        )

        // 1프레임 노이즈로는 RED_ESTIMATE로 즉각 튀어 음성 핑퐁을 유발하지 않고 GREEN_ESTIMATE 완충을 유지해야 함!
        assertEquals("1프레임 적색 노이즈는 즉시 RED_ESTIMATE로 튀지 않아야 함", CrossingState.GREEN_ESTIMATE, transientOutput.state)
        assertNull("1프레임 적색 노이즈에 적색 대기 안내가 발화되어서는 안 됨", transientOutput.guidanceText)

        // 3. 만약 적색이 2프레임 연속 지속되면, 실제 신호 변경이므로 RED_ESTIMATE로 정상 전이
        val redSustainedObs = SignalObservation(
            ephemeralTrackId = "track-2",
            state = ObservedSignalState.RED,
            score = 0.95f,
            box = NormalizedBox(0.50f, 0.40f, 0.52f, 0.42f),
            frameTimestampNanos = 14 * 100_000_000L,
            quality = FrameQuality(1.0f, 1.0f, true)
        )
        val assocRedSustained = TargetSignalAssociation(true, redSustainedObs, "SINGLE_TARGET_CONFIRMED", 0.95f)
        val sustainedOutput = engine.evaluate(
            CrossingDecisionInput(
                crossingContext = crossing,
                crosswalk = crosswalk,
                association = assocRedSustained,
                location = null,
                devicePose = pose,
                officialSignal = null,
                isTiltSuitable = true,
                monotonicTimeNanos = 14 * 100_000_000L
            )
        )
        assertEquals("2프레임 지속된 적색은 정상적으로 RED_ESTIMATE로 전환되어야 함", CrossingState.RED_ESTIMATE, sustainedOutput.state)
        assertEquals("적색 신호입니다. 대기하세요.", sustainedOutput.guidanceText)
    }

    @Test
    fun testDefiniteRedSignalPrioritizesSafetyEvenUnderMinorTiltWarning() {
        val engine = CrossingDecisionEngine(minConsecutiveGreenFrames = 3)
        val crossing = VerifiedCrossingContext("CW-TEST-1", 0.0f, isFieldVerified = true)
        val crosswalk = CrosswalkObservation(true, null, null, 0f, 0.9f, 0.9f)
        val pose = DevicePose(pitchDegrees = -32f, rollDegrees = 0f, headingDegrees = 0f)

        // 화면 전방에 고신뢰도(0.98) 적색 신호가 명확히 감지되었으나, 기기 각도가 약간 숙여져 isTiltSuitable = false인 상황
        val redSignal = SignalObservation(
            ephemeralTrackId = "track-red-1",
            state = ObservedSignalState.RED,
            score = 0.98f,
            box = NormalizedBox(0.42f, 0.15f, 0.44f, 0.18f),
            frameTimestampNanos = 100_000_000L,
            quality = FrameQuality(1.0f, 1.0f, true)
        )
        val assoc = TargetSignalAssociation(true, redSignal, "SINGLE_TARGET_CONFIRMED", 0.98f)

        val output = engine.evaluate(
            CrossingDecisionInput(
                crossingContext = crossing,
                crosswalk = crosswalk,
                association = assoc,
                location = null,
                devicePose = pose,
                officialSignal = null,
                isTiltSuitable = false, // 각도 경고 상황!
                monotonicTimeNanos = 100_000_000L
            )
        )

        // 안전 최우선 원칙(ST-001): 적색 정지 신호는 각도 불량이라는 이유로 침묵/UNKNOWN으로 억제되지 않고 적색 정지를 경고해야 함!
        assertEquals("명확한 적색 신호는 각도 경고보다 우선하여 적색 정지를 안내해야 함", CrossingState.RED_ESTIMATE, output.state)
        assertEquals("적색 신호입니다. 대기하세요.", output.guidanceText)
    }
}
