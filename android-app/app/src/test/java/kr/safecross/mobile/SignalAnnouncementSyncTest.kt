package kr.safecross.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.safecross.mobile.camera.FakeCameraPipeManager
import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.decision.CrossingAssistDecisionState
import kr.safecross.mobile.decision.CrossingDecisionEngine
import kr.safecross.mobile.guidance.GuidanceArbiter
import kr.safecross.mobile.ml.YoloPedestrianSignalDetector
import kr.safecross.mobile.perception.CameraVisionSignalEstimator
import kr.safecross.mobile.perception.FakeCrosswalkEstimator
import kr.safecross.mobile.perception.FakeSignalEstimator
import kr.safecross.mobile.perception.FrameQuality
import kr.safecross.mobile.perception.LockOnSignalAssociator
import kr.safecross.mobile.perception.NormalizedBox
import kr.safecross.mobile.perception.ObservedSignalState
import kr.safecross.mobile.perception.PedestrianSignalEstimator
import kr.safecross.mobile.perception.SignalObservation
import kr.safecross.mobile.perception.TwoTierHybridSignalEstimator
import kr.safecross.mobile.perception.VerifiedCrossingContext
import kr.safecross.mobile.sensor.FakeDevicePoseTracker
import kr.safecross.mobile.ui.screens.crossingassist.CrossingAssistEffect
import kr.safecross.mobile.ui.screens.crossingassist.CrossingAssistViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 2026-10-01 현장 테스트에서 발견된 문제의 회귀 테스트.
 * 1. 화면(배지·박스 색)과 음성 안내가 서로 다른 상태를 따라 어긋남
 * 2. 원거리 보행 녹색등의 픽토그램 LED가 잘게 쪼개져 HSV 블롭 필터에서 모두 기각됨
 * 3. 모델이 1~2프레임 놓칠 때 뷰파인더 전체 분석으로 넘어가 다른 적색등(차량 신호)을 잡음
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignalAnnouncementSyncTest {

    private val testDispatcher = StandardTestDispatcher()
    private val verifiedCrossing = VerifiedCrossingContext("CW-TEST-1", 0f, true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        sequence: List<ObservedSignalState>,
        guidanceArbiter: GuidanceArbiter = GuidanceArbiter()
    ) = CrossingAssistViewModel(
        cameraPipeManager = FakeCameraPipeManager(),
        crosswalkEstimator = FakeCrosswalkEstimator(),
        signalEstimator = FakeSignalEstimator(sequence = sequence),
        signalAssociator = LockOnSignalAssociator(),
        decisionEngine = CrossingDecisionEngine(minConsecutiveGreenFrames = 5),
        poseTracker = FakeDevicePoseTracker(),
        guidanceArbiter = guidanceArbiter
    )

    private suspend fun TestScope.feed(vm: CrossingAssistViewModel, frames: Int, startIndex: Int = 1) {
        for (i in startIndex until startIndex + frames) {
            vm.processFrame(FrameRef.createForTesting(timestampNanos = i * 150_000_000L))
            advanceUntilIdle()
        }
    }

    @Test
    fun briefDetectionDropoutDoesNotFlipScreenOrRepeatRedAnnouncement() = runTest {
        val sequence = List(3) { ObservedSignalState.RED } +
                List(2) { ObservedSignalState.UNKNOWN } +
                List(3) { ObservedSignalState.RED }
        val vm = newViewModel(sequence)
        val spoken = mutableListOf<String>()
        val job = launch {
            vm.effects.collect { if (it is CrossingAssistEffect.SpeakGuidance) spoken.add(it.text) }
        }

        vm.onCameraPermissionGranted(verifiedCrossing)
        advanceUntilIdle()

        val shownStates = mutableListOf<CrossingAssistDecisionState>()
        for (i in 1..sequence.size) {
            vm.processFrame(FrameRef.createForTesting(timestampNanos = i * 150_000_000L))
            advanceUntilIdle()
            shownStates.add(vm.uiState.value.decisionState)
        }
        job.cancel()

        // 2번째 프레임부터 적색 확정 후, 2프레임 누락에도 화면은 계속 적색
        assertEquals(List(sequence.size - 1) { CrossingAssistDecisionState.RED_ESTIMATE }, shownStates.drop(1))
        // 적색 안내는 1회만, 확인 불가 안내는 없어야 함
        assertEquals(1, spoken.count { it == "적색 신호입니다. 대기하세요." })
        assertEquals(0, spoken.count { it.startsWith("신호를 확인할 수 없습니다") })
        // 화면 문구와 음성 문구가 동일
        assertEquals("적색 신호입니다. 대기하세요.", vm.uiState.value.statusMessage)
        assertEquals(ObservedSignalState.RED, vm.uiState.value.detectedSignalColor)
    }

    @Test
    fun sustainedLossAfterRedAnnouncesUnknownOnceAndClearsRedBox() = runTest {
        // 확정된 적색에서 UNKNOWN으로 바뀌려면 4초 이상 연속 필요 (150ms 간격 × 30프레임 = 4.5초)
        val sequence = List(3) { ObservedSignalState.RED } + List(30) { ObservedSignalState.UNKNOWN }
        val vm = newViewModel(sequence)
        val spoken = mutableListOf<String>()
        val job = launch {
            vm.effects.collect { if (it is CrossingAssistEffect.SpeakGuidance) spoken.add(it.text) }
        }

        vm.onCameraPermissionGranted(verifiedCrossing)
        advanceUntilIdle()
        feed(vm, sequence.size)
        job.cancel()

        assertEquals(CrossingAssistDecisionState.UNKNOWN, vm.uiState.value.decisionState)
        assertEquals(null, vm.uiState.value.detectedSignalColor)
        assertEquals(1, spoken.count { it.startsWith("신호를 확인할 수 없습니다") })
        assertEquals(vm.uiState.value.statusMessage, spoken.last())
    }

    @Test
    fun fieldPatternOfAlternatingRedAndUnknownAnnouncesRedOnlyOnce() = runTest {
        // 10/02 현장 재현: 휴대폰 각도가 경계에서 흔들려 적색(1초)과 확인불가(2초)가 번갈아 나옴
        val cycle = List(7) { ObservedSignalState.RED } + List(13) { ObservedSignalState.UNKNOWN }
        val sequence = cycle + cycle + cycle + cycle
        val vm = newViewModel(sequence)
        val spoken = mutableListOf<String>()
        val job = launch {
            vm.effects.collect { if (it is CrossingAssistEffect.SpeakGuidance) spoken.add(it.text) }
        }

        vm.onCameraPermissionGranted(verifiedCrossing)
        advanceUntilIdle()
        feed(vm, sequence.size)
        job.cancel()

        assertEquals(CrossingAssistDecisionState.RED_ESTIMATE, vm.uiState.value.decisionState)
        assertEquals(1, spoken.count { it == "적색 신호입니다. 대기하세요." })
        assertEquals(0, spoken.count { it.startsWith("신호를 확인할 수 없습니다") })
    }

    @Test
    fun redIsAnnouncedAgainAfterUnknownWasAnnounced() = runTest {
        // 적색 -> 5초 이상 확인불가(안내됨) -> 다시 적색: 8초 쿨다운과 무관하게 적색을 다시 알려야 함
        val sequence = List(3) { ObservedSignalState.RED } + List(36) { ObservedSignalState.UNKNOWN } + List(3) { ObservedSignalState.RED }
        // 중재기 쿨다운은 실제 시각(벽시계) 기준이라 테스트의 가상 6초가 몇 ms로 지나가므로 0으로 두고 ViewModel 규칙만 검증
        val vm = newViewModel(sequence, GuidanceArbiter(safetyCooldownMs = 0L, crossingCooldownMs = 0L))
        val spoken = mutableListOf<String>()
        val job = launch {
            vm.effects.collect { if (it is CrossingAssistEffect.SpeakGuidance) spoken.add(it.text) }
        }

        vm.onCameraPermissionGranted(verifiedCrossing)
        advanceUntilIdle()
        feed(vm, sequence.size)
        job.cancel()

        val relevant = spoken.filter { it.startsWith("적색") || it.startsWith("신호를 확인할 수 없습니다") }
        assertEquals(
            listOf("적색 신호입니다. 대기하세요.", "신호를 확인할 수 없습니다. 신호등을 화면 가운데에 맞춰 주세요.", "적색 신호입니다. 대기하세요."),
            relevant
        )
    }

    // ---- 인식 파이프라인 회귀 ----

    private class FakeYolo(var detections: List<SignalObservation>) : PedestrianSignalEstimator {
        override suspend fun estimate(frame: FrameRef): List<SignalObservation> =
            detections.map { it.copy(frameTimestampNanos = frame.timestampNanos) }
    }

    private fun yolo(state: ObservedSignalState, box: NormalizedBox) = SignalObservation(
        ephemeralTrackId = "trk-yolo-1",
        state = state,
        score = 0.50f,
        box = box,
        frameTimestampNanos = 0L,
        quality = FrameQuality(0.85f, 0.90f, true),
        modelVersion = YoloPedestrianSignalDetector.MODEL_VERSION
    )

    /** 어두운 배경에 서로 떨어진 1픽셀 녹색 LED 점(원거리 보행등 픽토그램 분절)을 그린 RGBA 프레임 */
    private fun fragmentedGreenFrame(width: Int, height: Int, timestampNanos: Long): FrameRef {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until width * height) {
            buffer.put(20.toByte()); buffer.put(20.toByte()); buffer.put(20.toByte()); buffer.put(255.toByte())
        }
        val dots = listOf(95 to 60, 99 to 60, 103 to 60, 95 to 66, 103 to 66, 99 to 72, 95 to 78, 103 to 78)
        for ((x, y) in dots) {
            val o = (y * width + x) * 4
            buffer.put(o, 0.toByte()); buffer.put(o + 1, 230.toByte()); buffer.put(o + 2, 160.toByte())
        }
        buffer.rewind()
        return FrameRef.createForTesting(width = width, height = height, timestampNanos = timestampNanos, rgbaBuffer = buffer)
    }

    @Test
    fun modelGreenWithFragmentedGreenLedPixelsIsRecognizedAsGreen() = runBlocking {
        val box = NormalizedBox(left = 0.47f, top = 0.28f, right = 0.53f, bottom = 0.40f)
        val estimator = TwoTierHybridSignalEstimator(
            primaryDetector = FakeYolo(listOf(yolo(ObservedSignalState.GREEN, box))),
            colorAnalyzer = CameraVisionSignalEstimator(),
            fallbackToViewfinder = true
        )
        var last: SignalObservation? = null
        for (i in 1..4) {
            last = estimator.estimate(fragmentedGreenFrame(200, 200, i * 150_000_000L)).first()
        }
        assertEquals(ObservedSignalState.GREEN, last!!.state)
    }

    @Test
    fun modelMissKeepsAnalyzingLockedSignalInsteadOfWholeViewfinder() = runBlocking {
        val box = NormalizedBox(left = 0.47f, top = 0.28f, right = 0.53f, bottom = 0.40f)
        val detector = FakeYolo(listOf(yolo(ObservedSignalState.RED, box)))
        val estimator = TwoTierHybridSignalEstimator(
            primaryDetector = detector,
            colorAnalyzer = CameraVisionSignalEstimator(testFallbackState = ObservedSignalState.RED),
            fallbackToViewfinder = true
        )
        estimator.estimate(FrameRef.createForTesting(timestampNanos = 100_000_000L))

        // 모델이 다음 프레임을 놓쳐도 1.5초 이내면 직전 신호등 위치 유지
        detector.detections = emptyList()
        val held = estimator.estimate(FrameRef.createForTesting(timestampNanos = 400_000_000L)).first()
        assertEquals("two-tier-hybrid-yolo-v3.0", held.modelVersion)

        // 1.5초 넘게 놓치면 뷰파인더 폴백
        val fallback = estimator.estimate(FrameRef.createForTesting(timestampNanos = 2_500_000_000L)).first()
        assertNotEquals("two-tier-hybrid-yolo-v3.0", fallback.modelVersion)
    }
}
