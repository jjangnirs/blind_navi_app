package kr.safecross.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import kr.safecross.mobile.perception.FakeCrosswalkEstimator
import kr.safecross.mobile.perception.FakeSignalAssociator
import kr.safecross.mobile.perception.FakeSignalEstimator
import kr.safecross.mobile.perception.ObservedSignalState
import kr.safecross.mobile.perception.VerifiedCrossingContext
import kr.safecross.mobile.sensor.FakeDevicePoseTracker
import kr.safecross.mobile.ui.screens.crossingassist.CrossingAssistEffect
import kr.safecross.mobile.ui.screens.crossingassist.CrossingAssistViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 횡단 보조 ViewModel 단위 테스트 (SR-F-040, SR-F-049, SR-F-070).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossingAssistViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var cameraPipe: FakeCameraPipeManager
    private lateinit var crosswalkEstimator: FakeCrosswalkEstimator
    private lateinit var signalEstimator: FakeSignalEstimator
    private lateinit var signalAssociator: FakeSignalAssociator
    private lateinit var decisionEngine: CrossingDecisionEngine
    private lateinit var poseTracker: FakeDevicePoseTracker
    private lateinit var guidanceArbiter: GuidanceArbiter
    private lateinit var viewModel: CrossingAssistViewModel

    private val verifiedCrossing = VerifiedCrossingContext("CW-TEST-1", 0f, true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        cameraPipe = FakeCameraPipeManager()
        crosswalkEstimator = FakeCrosswalkEstimator()
        signalEstimator = FakeSignalEstimator.createStableGreenSequence(6)
        signalAssociator = FakeSignalAssociator()
        decisionEngine = CrossingDecisionEngine(minConsecutiveGreenFrames = 5)
        poseTracker = FakeDevicePoseTracker()
        guidanceArbiter = GuidanceArbiter()

        viewModel = CrossingAssistViewModel(
            cameraPipeManager = cameraPipe,
            crosswalkEstimator = crosswalkEstimator,
            signalEstimator = signalEstimator,
            signalAssociator = signalAssociator,
            decisionEngine = decisionEngine,
            poseTracker = poseTracker,
            guidanceArbiter = guidanceArbiter
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testCameraPermissionGrantedStartsAssistance() = runTest {
        viewModel.onCameraPermissionGranted(verifiedCrossing)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.hasCameraPermission)
        assertTrue(state.isCameraBound)
        assertEquals(CrossingAssistDecisionState.SCANNING, state.decisionState)
        assertTrue(poseTracker.isTracking)
    }

    @Test
    fun testCameraPermissionDeniedSetsUnknownAndWarns() = runTest {
        viewModel.onCameraPermissionDenied()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.hasCameraPermission)
        assertEquals(CrossingAssistDecisionState.UNKNOWN, state.decisionState)
        assertTrue(state.statusMessage.contains("권한이 거부"))
    }

    @Test
    fun testProcessFramesAccumulatesToGreenEstimate() = runTest {
        signalEstimator.sequence = listOf(ObservedSignalState.GREEN)
        signalEstimator.resetSequence()

        viewModel.onCameraPermissionGranted(verifiedCrossing)
        advanceUntilIdle()

        // 5회 연속 GREEN 프레임 처리
        for (i in 1..5) {
            viewModel.processFrame(FrameRef.createForTesting(timestampNanos = i * 33_000_000L))
            advanceUntilIdle()
        }

        val state = viewModel.uiState.value
        assertEquals(CrossingAssistDecisionState.GREEN_ESTIMATE, state.decisionState)
        assertTrue(state.crosswalkDetected)
    }

    @Test
    fun testStopAssistanceReleasesAllResourcesAndFinishes() = runTest {
        val effects = mutableListOf<CrossingAssistEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        viewModel.onCameraPermissionGranted(verifiedCrossing)
        advanceUntilIdle()

        viewModel.stopAssistance()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isCameraBound)
        assertTrue(state.isTerminated)
        assertFalse(poseTracker.isTracking)
        assertEquals(1, cameraPipe.unbindCount)

        assertTrue(effects.contains(CrossingAssistEffect.FinishScreen))
        job.cancel()
    }

    @Test
    fun testSignalLockedInReticleEmitsConfirmationHaptic() = runTest {
        val effects = mutableListOf<CrossingAssistEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        viewModel.onCameraPermissionGranted(verifiedCrossing)
        advanceUntilIdle()

        // FakeSignalEstimator의 기본 박스는 NormalizedBox(0.45f, 0.20f, 0.55f, 0.40f)로
        // UI 기본 reticleBox(0.20f, 0.10f, 0.80f, 0.60f)의 중심에 정확히 위치함
        viewModel.processFrame(FrameRef.createForTesting(timestampNanos = 100_000_000L))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isSignalInReticle)

        val lockOnEffect = effects.filterIsInstance<CrossingAssistEffect.SpeakGuidance>()
            .firstOrNull { it.text.contains("신호등이 조준") }
        assertTrue(lockOnEffect != null)
        assertEquals(kr.safecross.mobile.guidance.HapticFeedbackType.ORIENTATION_ALIGNED, lockOnEffect?.hapticType)

        job.cancel()
    }

    @Test
    fun testTiltGuidanceHysteresisAndSuitableElevationRange() = runTest {
        // -20도 ~ +30도 사이의 보행 자세는 SUITABLE 유지
        poseTracker.setPose(pitch = 10f, roll = 0f)
        advanceUntilIdle()
        assertEquals(kr.safecross.mobile.sensor.TiltGuidance.SUITABLE, viewModel.uiState.value.tiltGuidance)

        // 바닥을 너무 향할 때 TILT_UP 유도
        poseTracker.setPose(pitch = -30f, roll = 0f)
        advanceUntilIdle()
        assertEquals(kr.safecross.mobile.sensor.TiltGuidance.TILT_UP, viewModel.uiState.value.tiltGuidance)

        // 고개를 살짝 들어 -15도에 도달하면 히스테리시스로 인해 즉시 SUITABLE 복귀
        poseTracker.setPose(pitch = -15f, roll = 0f)
        advanceUntilIdle()
        assertEquals(kr.safecross.mobile.sensor.TiltGuidance.SUITABLE, viewModel.uiState.value.tiltGuidance)
    }
}
