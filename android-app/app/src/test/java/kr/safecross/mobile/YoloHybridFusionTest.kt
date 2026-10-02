package kr.safecross.mobile

import kotlinx.coroutines.runBlocking
import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.ml.YoloPedestrianSignalDetector
import kr.safecross.mobile.perception.CameraVisionSignalEstimator
import kr.safecross.mobile.perception.FrameQuality
import kr.safecross.mobile.perception.NormalizedBox
import kr.safecross.mobile.perception.ObservedSignalState
import kr.safecross.mobile.perception.PedestrianSignalEstimator
import kr.safecross.mobile.perception.SignalObservation
import kr.safecross.mobile.perception.TwoTierHybridSignalEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * YOLO(색 분류 모델) + HSV 색 분석 교차 검증 융합 규칙 테스트.
 *
 * - 녹색은 모델과 HSV가 모두 녹색일 때만 인정
 * - 어느 한쪽이라도 적색이면 적색 우선
 * - 모델 적색 + HSV 녹색 충돌은 UNKNOWN
 */
class YoloHybridFusionTest {

    private class FakeYolo(var detections: List<SignalObservation>) : PedestrianSignalEstimator {
        override suspend fun estimate(frame: FrameRef): List<SignalObservation> =
            detections.map { it.copy(frameTimestampNanos = frame.timestampNanos) }
    }

    private fun yolo(state: ObservedSignalState, box: NormalizedBox, score: Float = 0.60f) = SignalObservation(
        ephemeralTrackId = "trk-yolo-1",
        state = state,
        score = score,
        box = box,
        frameTimestampNanos = 0L,
        quality = FrameQuality(0.85f, 0.90f, true),
        modelVersion = YoloPedestrianSignalDetector.MODEL_VERSION
    )

    private val centerBox = NormalizedBox(left = 0.47f, top = 0.25f, right = 0.53f, bottom = 0.40f)

    private fun runFrames(estimator: TwoTierHybridSignalEstimator, frames: Int): SignalObservation = runBlocking {
        var last: SignalObservation? = null
        for (i in 1..frames) {
            last = estimator.estimate(FrameRef.createForTesting(timestampNanos = i * 100_000_000L)).first()
        }
        last!!
    }

    @Test
    fun modelGreenAndHsvGreenConfirmsGreen() {
        val estimator = TwoTierHybridSignalEstimator(
            primaryDetector = FakeYolo(listOf(yolo(ObservedSignalState.GREEN, centerBox))),
            colorAnalyzer = CameraVisionSignalEstimator(testFallbackState = ObservedSignalState.GREEN),
            fallbackToViewfinder = true
        )
        val result = runFrames(estimator, 3)
        assertEquals(ObservedSignalState.GREEN, result.state)
        assertEquals("two-tier-hybrid-yolo-v3.0", result.modelVersion)
    }

    @Test
    fun modelGreenButHsvRedResolvesToRed() {
        val estimator = TwoTierHybridSignalEstimator(
            primaryDetector = FakeYolo(listOf(yolo(ObservedSignalState.GREEN, centerBox))),
            colorAnalyzer = CameraVisionSignalEstimator(testFallbackState = ObservedSignalState.RED),
            fallbackToViewfinder = true
        )
        assertEquals(ObservedSignalState.RED, runFrames(estimator, 3).state)
    }

    @Test
    fun modelRedButHsvGreenIsNeverGreen() {
        val estimator = TwoTierHybridSignalEstimator(
            primaryDetector = FakeYolo(listOf(yolo(ObservedSignalState.RED, centerBox))),
            colorAnalyzer = CameraVisionSignalEstimator(testFallbackState = ObservedSignalState.GREEN),
            fallbackToViewfinder = true
        )
        val result = runFrames(estimator, 6)
        assertNotEquals(ObservedSignalState.GREEN, result.state)
    }

    @Test
    fun modelGreenWithoutHsvConfirmationIsNeverGreen() {
        val estimator = TwoTierHybridSignalEstimator(
            primaryDetector = FakeYolo(listOf(yolo(ObservedSignalState.GREEN, centerBox, score = 0.95f))),
            colorAnalyzer = CameraVisionSignalEstimator(testFallbackState = ObservedSignalState.UNKNOWN),
            fallbackToViewfinder = true
        )
        val result = runFrames(estimator, 6)
        assertNotEquals(ObservedSignalState.GREEN, result.state)
    }

    @Test
    fun modelRedAloneIsAcceptedAsRed() {
        val estimator = TwoTierHybridSignalEstimator(
            primaryDetector = FakeYolo(listOf(yolo(ObservedSignalState.RED, centerBox, score = 0.55f))),
            colorAnalyzer = CameraVisionSignalEstimator(testFallbackState = ObservedSignalState.UNKNOWN),
            fallbackToViewfinder = true
        )
        assertEquals(ObservedSignalState.RED, runFrames(estimator, 3).state)
    }

    @Test
    fun lockOnKeepsFollowingPreviousTargetAmongMultipleDetections() = runBlocking {
        val leftBox = NormalizedBox(left = 0.36f, top = 0.25f, right = 0.40f, bottom = 0.35f)
        val rightBox = NormalizedBox(left = 0.58f, top = 0.25f, right = 0.62f, bottom = 0.35f)
        val detector = FakeYolo(listOf(yolo(ObservedSignalState.GREEN, leftBox)))
        val estimator = TwoTierHybridSignalEstimator(
            primaryDetector = detector,
            colorAnalyzer = CameraVisionSignalEstimator(testFallbackState = ObservedSignalState.GREEN),
            fallbackToViewfinder = true
        )

        // 1프레임: 왼쪽 신호등 하나만 보여서 Lock-on
        estimator.estimate(FrameRef.createForTesting(timestampNanos = 100_000_000L))

        // 2프레임: 오른쪽에 더 점수가 높은 후보가 나타나도 직전 타깃(왼쪽)을 계속 추종
        detector.detections = listOf(
            yolo(ObservedSignalState.GREEN, rightBox, score = 0.90f),
            yolo(ObservedSignalState.GREEN, leftBox.copy(left = 0.365f, right = 0.405f), score = 0.40f)
        )
        val result = estimator.estimate(FrameRef.createForTesting(timestampNanos = 200_000_000L)).first()
        assertTrue("직전 타깃 위치(왼쪽)를 유지해야 함", result.box.centerX < 0.5f)
    }

    @Test
    fun parsesClassOrderFromBundledYoloModelMetadata() {
        val modelFile = File("src/main/assets/models/ped_signal_v1.tflite")
        if (!modelFile.exists() || modelFile.length() < 1024) return
        val states = YoloPedestrianSignalDetector.parseClassStates(modelFile.readBytes())
        assertEquals(listOf(ObservedSignalState.GREEN, ObservedSignalState.RED), states)
    }

    @Test
    fun refusesToGuessClassOrderWithoutMetadata() {
        assertNull(YoloPedestrianSignalDetector.parseClassStates("no metadata here".toByteArray()))
        val ambiguous = """{"names": {"0": "light", "1": "pole"}}""".toByteArray()
        assertNull(YoloPedestrianSignalDetector.parseClassStates(ambiguous))
    }
}
