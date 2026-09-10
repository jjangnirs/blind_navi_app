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
}
