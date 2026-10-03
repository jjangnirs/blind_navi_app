package kr.safecross.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.safecross.mobile.camera.FakeCameraPipeManager
import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.decision.CrossingAssistDecisionState
import kr.safecross.mobile.decision.CrossingDecisionEngine
import kr.safecross.mobile.guidance.GuidanceArbiter
import kr.safecross.mobile.guidance.HapticFeedbackType
import kr.safecross.mobile.navigation.crossing.AimInstruction
import kr.safecross.mobile.navigation.crossing.CrossingAim
import kr.safecross.mobile.navigation.crossing.CrossingAimCalculator
import kr.safecross.mobile.perception.FakeCrosswalkEstimator
import kr.safecross.mobile.perception.FrameQuality
import kr.safecross.mobile.perception.LockOnSignalAssociator
import kr.safecross.mobile.perception.NormalizedBox
import kr.safecross.mobile.perception.ObservedSignalState
import kr.safecross.mobile.perception.PedestrianSignalEstimator
import kr.safecross.mobile.perception.SignalObservation
import kr.safecross.mobile.perception.VerifiedCrossingContext
import kr.safecross.mobile.sensor.FakeDevicePoseTracker
import kr.safecross.mobile.ui.screens.crossingassist.CrossingAssistEffect
import kr.safecross.mobile.ui.screens.crossingassist.CrossingAssistViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 보행신호등 조준 안내와 높이 걸러내기 (ADR-0040).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossingAimTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun aim(bearing: Double = 0.0, distance: Double = 20.0, bias: Double? = null) =
        CrossingAim(targetBearingDeg = bearing, distanceMeters = distance, compassBiasDeg = bias, updatedAtMs = 0L)

    // ---------------- 조준 계산 ----------------

    @Test
    fun aimInstructionsCoverTurnAndTilt() {
        val expected = CrossingAimCalculator.expectedElevationDeg(20.0) // 약 4.3°
        assertEquals(4.3, expected, 0.2)

        assertEquals(AimInstruction.TURN_LEFT, CrossingAimCalculator.evaluate(aim(), 40f, expected.toFloat()).instruction)
        assertEquals(AimInstruction.TURN_RIGHT, CrossingAimCalculator.evaluate(aim(), 320f, expected.toFloat()).instruction)
        assertEquals(AimInstruction.TILT_UP, CrossingAimCalculator.evaluate(aim(), 5f, -40f).instruction)
        assertEquals(AimInstruction.TILT_DOWN, CrossingAimCalculator.evaluate(aim(), 5f, 35f).instruction)
        assertEquals(AimInstruction.ALIGNED, CrossingAimCalculator.evaluate(aim(), 5f, 0f).instruction)
        assertEquals(AimInstruction.UNKNOWN, CrossingAimCalculator.evaluate(null, 5f, 0f).instruction)
        // 땅을 비추고 있으면(기울기 -60°) 좌우가 어긋나 있어도 먼저 세우라고 안내
        assertEquals(AimInstruction.TILT_UP, CrossingAimCalculator.evaluate(aim(), 40f, -60f).instruction)
    }

    @Test
    fun compassBiasFromVpsIsApplied() {
        // 나침반이 30° 덜 나오는 상태(보정값 +30): 나침반 330° = 실제 0° → 정렬
        assertEquals(AimInstruction.ALIGNED, CrossingAimCalculator.evaluate(aim(bias = 30.0), 330f, 0f).instruction)
        // 보정이 없으면 같은 나침반 값은 오른쪽으로 30° 돌리라는 지시
        val noBias = CrossingAimCalculator.evaluate(aim(), 330f, 0f)
        assertEquals(AimInstruction.TURN_RIGHT, noBias.instruction)
        assertEquals("오른쪽으로 30도 돌리세요.", noBias.message)
    }

    // ---------------- 높이 걸러내기 ----------------

    @Test
    fun heightFilterKeepsPedestrianSignalAndRejectsTailLightsAndOverheadSignals() {
        val d = 20.0
        // 보행신호등 2.8 m
        assertTrue(CrossingAimCalculator.isPlausibleSignalElevation(CrossingAimCalculator.expectedElevationDeg(d, 2.8), d))
        // 차량 미등 1.0 m (같은 거리)
        assertFalse(CrossingAimCalculator.isPlausibleSignalElevation(CrossingAimCalculator.expectedElevationDeg(d, 1.0), d))
        // 도로 위 차량 신호등 6.0 m
        assertFalse(CrossingAimCalculator.isPlausibleSignalElevation(CrossingAimCalculator.expectedElevationDeg(d, 6.0), d))
    }

    @Test
    fun detectionElevationUsesBoxPositionPitchAndFov() {
        // 화면 정중앙이면 앙각 = 기울기
        assertEquals(10.0, CrossingAimCalculator.detectionElevationDeg(0.5f, 10f, 74f), 0.01)
        // 화면 맨 위(0)면 기울기 + 화각 절반
        assertEquals(47.0, CrossingAimCalculator.detectionElevationDeg(0f, 10f, 74f), 0.01)
        // 추정 높이: 20 m 거리, 앙각 4.29° → 약 2.8 m
        assertEquals(2.8, CrossingAimCalculator.estimatedHeightMeters(4.29, 20.0), 0.05)
    }

    // ---------------- 화면 연동 ----------------

    /** 화면 정중앙에서 위/아래 위치(centerY)에 적색 불빛을 내는 가짜 검출기 */
    private class FixedRedLight(var centerY: Float, var centerX: Float = 0.5f) : PedestrianSignalEstimator {
        override suspend fun estimate(frame: FrameRef): List<SignalObservation> = listOf(
            SignalObservation(
                ephemeralTrackId = "track-sig-101",
                state = ObservedSignalState.RED,
                score = 0.95f,
                box = NormalizedBox(centerX - 0.02f, centerY - 0.02f, centerX + 0.02f, centerY + 0.02f),
                frameTimestampNanos = frame.timestampNanos,
                quality = FrameQuality(0.85f, 0.9f, true),
                modelVersion = "test"
            )
        )
    }

    private fun newVm(light: FixedRedLight, pose: FakeDevicePoseTracker) = CrossingAssistViewModel(
        cameraPipeManager = FakeCameraPipeManager(),
        crosswalkEstimator = FakeCrosswalkEstimator(),
        signalEstimator = light,
        signalAssociator = LockOnSignalAssociator(),
        decisionEngine = CrossingDecisionEngine(minConsecutiveGreenFrames = 5),
        poseTracker = pose,
        guidanceArbiter = GuidanceArbiter(),
        cameraVerticalFovDegrees = 74f
    )

    @Test
    fun lowLightAtTailLightHeightIsNotShownAsRed() = runTest(testDispatcher) {
        // 휴대폰을 수평(pitch 0)으로 들고, 불빛이 화면 중앙보다 아래(앙각 약 -5°, 20 m에서 높이 약 -0.5 m 수준)
        val pose = FakeDevicePoseTracker()
        pose.setPose(pitch = 0f, roll = 0f)
        val vm = newVm(FixedRedLight(centerY = 0.57f), pose)
        vm.onCameraPermissionGranted(VerifiedCrossingContext("CW-TEST-1", 0f, true))
        vm.updateAim(aim(distance = 20.0))
        advanceUntilIdle()
        repeat(5) { i -> vm.processFrame(FrameRef.createForTesting(timestampNanos = (i + 1) * 150_000_000L)); advanceUntilIdle() }
        assertNotEquals(CrossingAssistDecisionState.RED_ESTIMATE, vm.uiState.value.decisionState)

        // 같은 불빛이 보행신호등 높이(앙각 약 +4°)에 있으면 적색으로 인정
        val vm2 = newVm(FixedRedLight(centerY = 0.45f), pose)
        vm2.onCameraPermissionGranted(VerifiedCrossingContext("CW-TEST-1", 0f, true))
        vm2.updateAim(aim(distance = 20.0))
        advanceUntilIdle()
        repeat(5) { i -> vm2.processFrame(FrameRef.createForTesting(timestampNanos = (i + 1) * 150_000_000L)); advanceUntilIdle() }
        assertEquals(CrossingAssistDecisionState.RED_ESTIMATE, vm2.uiState.value.decisionState)
    }

    @Test
    fun aimGuidanceSpeaksTurnDirectionWithMatchingHaptic() = runTest(testDispatcher) {
        // 신호등이 북쪽(0°)인데 카메라는 동쪽(약 90°)을 보고 있음 → 왼쪽으로 돌리라는 안내 + 짧은 진동
        val pose = FakeDevicePoseTracker()
        pose.setPose(pitch = 5f, roll = 0f)
        // 아직 조준선 밖(화면 오른쪽 끝)에서만 불빛이 보이는 상황
        val light = FixedRedLight(centerY = 0.45f, centerX = 0.9f)
        val vm = newVm(light, pose)
        val effects = mutableListOf<CrossingAssistEffect.SpeakGuidance>()
        val job = launch { vm.effects.collect { if (it is CrossingAssistEffect.SpeakGuidance) effects.add(it) } }
        vm.onCameraPermissionGranted(VerifiedCrossingContext("CW-TEST-1", 0f, true))
        vm.updateAim(aim(bearing = 0.0, distance = 20.0))
        advanceUntilIdle()

        // FakeDevicePoseTracker는 카메라 방위각을 제공하지 않으므로 직접 주입
        pose.setPoseWithCameraHeading(pitch = 5f, cameraHeading = 90f)
        vm.processFrame(FrameRef.createForTesting(timestampNanos = 2_000_000_000L))
        advanceUntilIdle()
        job.cancel()

        val aimSpeech = effects.firstOrNull { it.text.startsWith("왼쪽으로") }
        assertTrue("조준 안내가 나와야 함: ${effects.map { it.text }}", aimSpeech != null)
        assertEquals(HapticFeedbackType.TURN_LEFT, aimSpeech!!.hapticType)
    }
}
