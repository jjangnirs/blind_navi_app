package kr.safecross.mobile

import kotlinx.coroutines.runBlocking
import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.perception.CameraVisionSignalEstimator
import kr.safecross.mobile.perception.FrameQuality
import kr.safecross.mobile.perception.LocalVlmSignalVerifier
import kr.safecross.mobile.perception.NormalizedBox
import kr.safecross.mobile.perception.ObservedSignalState
import kr.safecross.mobile.perception.PedestrianSignalEstimator
import kr.safecross.mobile.perception.SignalObservation
import kr.safecross.mobile.perception.TwoTierHybridSignalEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * 2단계 하이브리드 보행신호 판정기(TwoTierHybridSignalEstimator) 단위 테스트.
 *
 * [핵심 검증 항목]
 * 1. 딥러닝 모델이 신호등 박스를 찾지 못한 경우: 배경에 녹색이 있어도 절대 GREEN 판정 불가 (Zero False-Green)
 * 2. 딥러닝 모델이 신호등 박스를 찾은 경우: 해당 박스 내부에서만 HSV 정밀 색상 판정 수행
 * 3. 5프레임 롤링 버퍼를 통한 시간 일관성 검증
 */
class TwoTierHybridSignalEstimatorTest {

    /**
     * 모의 딥러닝 객체 검출기
     */
    class FakeObjectDetector(
        var detectedBoxes: List<SignalObservation> = emptyList()
    ) : PedestrianSignalEstimator {
        override suspend fun estimate(frame: FrameRef): List<SignalObservation> {
            return detectedBoxes
        }
    }

    @Test
    fun testNoBoxDetectedReturnsUnknownEvenIfBackgroundIsGreen() = runBlocking {
        // Tier 1 딥러닝 모델: 신호등 객체 미검출 (빈 리스트)
        val fakeDetector = FakeObjectDetector(detectedBoxes = emptyList())

        // Tier 2 HSV 비전 엔진: 배경에 녹색이 있다고 가정 (Fallback GREEN)
        val colorAnalyzer = CameraVisionSignalEstimator(testFallbackState = ObservedSignalState.GREEN)
        val verifier = LocalVlmSignalVerifier()

        val hybridEstimator = TwoTierHybridSignalEstimator(
            primaryDetector = fakeDetector,
            colorAnalyzer = colorAnalyzer,
            verifier = verifier
        )

        val frame = FrameRef.createForTesting()
        val results = hybridEstimator.estimate(frame)

        assertEquals(1, results.size)
        // 딥러닝이 신호등을 못 찾았으므로 배경이 녹색이든 무관하게 반드시 UNKNOWN이어야 함
        assertEquals(ObservedSignalState.UNKNOWN, results[0].state)
        assertNotEquals(ObservedSignalState.GREEN, results[0].state)
    }

    @Test
    fun testTooSmallOrBrokenBoxReturnsUnknownSafe() = runBlocking {
        // 크기가 너무 작은 박스 (폭/높이가 0.01 이하)
        val brokenBox = SignalObservation(
            ephemeralTrackId = "trk-broken",
            state = ObservedSignalState.UNKNOWN,
            score = 0.40f,
            box = NormalizedBox(0.5f, 0.5f, 0.505f, 0.505f),
            frameTimestampNanos = 1000L,
            quality = FrameQuality(0.8f, 0.8f, true),
            modelVersion = "test"
        )
        val fakeDetector = FakeObjectDetector(detectedBoxes = listOf(brokenBox))
        val colorAnalyzer = CameraVisionSignalEstimator(testFallbackState = ObservedSignalState.GREEN)

        val hybridEstimator = TwoTierHybridSignalEstimator(
            primaryDetector = fakeDetector,
            colorAnalyzer = colorAnalyzer
        )

        val results = hybridEstimator.estimate(FrameRef.createForTesting())
        assertEquals(ObservedSignalState.UNKNOWN, results[0].state)
    }

    @Test
    fun testValidBoxDetectedPerformsRoiHsvAnalysis() = runBlocking {
        // Tier 1 딥러닝 모델: 정상적인 신호등 바운딩 박스 검출 완료
        val validBox = NormalizedBox(left = 0.40f, top = 0.15f, right = 0.60f, bottom = 0.45f)
        val detectedBox = SignalObservation(
            ephemeralTrackId = "trk-box-101",
            state = ObservedSignalState.UNKNOWN, // 박스 위치만 알고 상태는 아직 모름
            score = 0.92f,
            box = validBox,
            frameTimestampNanos = 1000L,
            quality = FrameQuality(0.9f, 0.9f, true),
            modelVersion = "detector-v1.0"
        )
        val fakeDetector = FakeObjectDetector(detectedBoxes = listOf(detectedBox))

        // Tier 2 HSV 분석기: 박스 내부가 GREEN 상태
        val colorAnalyzer = CameraVisionSignalEstimator(testFallbackState = ObservedSignalState.GREEN)
        val verifier = LocalVlmSignalVerifier()

        val hybridEstimator = TwoTierHybridSignalEstimator(
            primaryDetector = fakeDetector,
            colorAnalyzer = colorAnalyzer,
            verifier = verifier
        )

        // 3프레임 연속 녹색 주입 (60% 이상 합의 달성)
        var lastResult: SignalObservation? = null
        for (i in 1..3) {
            val frame = FrameRef.createForTesting(timestampNanos = i * 33_000_000L)
            val observations = hybridEstimator.estimate(frame)
            lastResult = observations[0]
        }

        assertEquals(ObservedSignalState.GREEN, lastResult?.state)
        assertEquals(validBox.left, lastResult!!.box.left, 1e-4f)
        assertEquals(validBox.top, lastResult.box.top, 1e-4f)
    }

    @Test
    fun testValidBoxWithRedSignalReturnsRedSafely() = runBlocking {
        val validBox = NormalizedBox(left = 0.45f, top = 0.20f, right = 0.55f, bottom = 0.40f)
        val detectedBox = SignalObservation(
            ephemeralTrackId = "trk-box-red",
            state = ObservedSignalState.UNKNOWN,
            score = 0.95f,
            box = validBox,
            frameTimestampNanos = 2000L,
            quality = FrameQuality(0.9f, 0.9f, true),
            modelVersion = "detector-v1.0"
        )
        val fakeDetector = FakeObjectDetector(detectedBoxes = listOf(detectedBox))
        val colorAnalyzer = CameraVisionSignalEstimator(testFallbackState = ObservedSignalState.RED)

        val hybridEstimator = TwoTierHybridSignalEstimator(
            primaryDetector = fakeDetector,
            colorAnalyzer = colorAnalyzer
        )

        val results = hybridEstimator.estimate(FrameRef.createForTesting())
        assertEquals(ObservedSignalState.RED, results[0].state)
    }
}
