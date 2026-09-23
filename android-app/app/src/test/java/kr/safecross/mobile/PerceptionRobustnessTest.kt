package kr.safecross.mobile

import kotlinx.coroutines.test.runTest
import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.perception.CameraVisionSignalEstimator
import kr.safecross.mobile.perception.FrameQuality
import kr.safecross.mobile.perception.LocalVlmSignalVerifier
import kr.safecross.mobile.perception.NormalizedBox
import kr.safecross.mobile.perception.ObservedSignalState
import kr.safecross.mobile.perception.SignalObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 신호등 인지 파이프라인의 3대 핵심 취약점 개선 검증 테스트:
 * 1. IoU 기반 공간 추적 및 위치 급변 시 Track/시간 버퍼 리셋
 * 2. 화면을 가로지르는 차량/동적 객체 이동 속도(Motion Vector) 필터
 * 3. 신호등 외곽 다크 하우징(차광판 케이스) 콘트라스트 검증 및 간판 배제
 */
class PerceptionRobustnessTest {

    private val verifier = LocalVlmSignalVerifier()
    private val estimator = CameraVisionSignalEstimator()

    @Test
    fun testIoUCalculationCorrectness() {
        val boxA = NormalizedBox(0.2f, 0.2f, 0.4f, 0.4f)
        val boxB = NormalizedBox(0.2f, 0.2f, 0.4f, 0.4f)
        // 1. 완전히 동일한 박스 -> IoU = 1.0
        assertEquals(1.0f, LocalVlmSignalVerifier.computeIoU(boxA, boxB), 0.001f)

        // 2. 전혀 겹치지 않는 박스 -> IoU = 0.0
        val boxC = NormalizedBox(0.5f, 0.5f, 0.7f, 0.7f)
        assertEquals(0.0f, LocalVlmSignalVerifier.computeIoU(boxA, boxC), 0.001f)

        // 3. 일부 겹치는 박스 (0.3f..0.4f 겹침)
        val boxD = NormalizedBox(0.3f, 0.2f, 0.5f, 0.4f)
        val iou = LocalVlmSignalVerifier.computeIoU(boxA, boxD)
        assertTrue(iou in 0.30f..0.35f)
    }

    @Test
    fun testSpatialJumpResetsTemporalHistory() {
        val dummyBuffer = ByteBuffer.allocateDirect(100)

        // 1. 좌측 상단 위치의 정상 적색 신호 (Track 1)
        val obs1 = SignalObservation(
            ephemeralTrackId = "track-1",
            state = ObservedSignalState.RED,
            score = 0.95f,
            box = NormalizedBox(0.1f, 0.1f, 0.2f, 0.3f),
            frameTimestampNanos = 1_000_000_000L,
            quality = FrameQuality(1.0f, 1.0f, true),
            modelVersion = "test"
        )
        val res1 = verifier.verify(obs1, dummyBuffer, 320, 240)
        assertEquals(ObservedSignalState.RED, res1.verifiedState)

        // 2. 우측 하단으로 박스가 순간 점프한 신호 (IoU = 0.0)
        // 기존 롤링 버퍼가 리셋되어 이전 상태와 합산되지 않아야 함
        val obsJump = SignalObservation(
            ephemeralTrackId = "track-jump",
            state = ObservedSignalState.GREEN,
            score = 0.95f,
            box = NormalizedBox(0.7f, 0.7f, 0.8f, 0.9f),
            frameTimestampNanos = 1_066_000_000L,
            quality = FrameQuality(1.0f, 1.0f, true),
            modelVersion = "test"
        )
        val resJump = verifier.verify(obsJump, dummyBuffer, 320, 240)
        // Zero False-Green: 점프 후 첫 번째 녹색 신호이므로 단독 승인되지 않고 UNKNOWN으로 안전 방어되어야 함
        assertEquals(ObservedSignalState.UNKNOWN, resJump.verifiedState)
    }

    @Test
    fun testRejectsHighSpeedMovingVehicleMotion() {
        val dummyBuffer = ByteBuffer.allocateDirect(100)

        // 1. 프레임 1: 중앙 좌측에 위치한 후보 (t = 1.0s)
        val frame1 = SignalObservation(
            ephemeralTrackId = "veh-1",
            state = ObservedSignalState.GREEN,
            score = 0.90f,
            box = NormalizedBox(0.10f, 0.40f, 0.20f, 0.60f),
            frameTimestampNanos = 1_000_000_000L,
            quality = FrameQuality(1.0f, 1.0f, true),
            modelVersion = "test"
        )
        verifier.verify(frame1, dummyBuffer, 320, 240)

        // 2. 프레임 2: 100ms 뒤 화면 중앙 우측으로 급격히 이동한 차량 (t = 1.1s, Δx = 0.50, 속도 = 5.0 / sec)
        val frame2Fast = SignalObservation(
            ephemeralTrackId = "veh-1",
            state = ObservedSignalState.GREEN,
            score = 0.90f,
            box = NormalizedBox(0.60f, 0.40f, 0.70f, 0.60f),
            frameTimestampNanos = 1_100_000_000L,
            quality = FrameQuality(1.0f, 1.0f, true),
            modelVersion = "test"
        )
        val result = verifier.verify(frame2Fast, dummyBuffer, 320, 240)

        // 속도 > 0.55 / sec 초과로 동적 객체(차량 등)로 판단되어 즉시 기각되어야 함
        assertFalse(result.isVerified)
        assertEquals(ObservedSignalState.UNKNOWN, result.verifiedState)
        assertEquals("REJECTED_DYNAMIC_MOTION", result.verificationReason)
    }

    @Test
    fun testStationaryPedestrianSignalPassesMotionFilter() {
        val dummyBuffer = ByteBuffer.allocateDirect(100)

        // 1. 프레임 1: 신호등 관측 (t = 1.00s)
        val frame1 = SignalObservation(
            ephemeralTrackId = "sig-stat",
            state = ObservedSignalState.RED,
            score = 0.95f,
            box = NormalizedBox(0.45f, 0.20f, 0.55f, 0.40f),
            frameTimestampNanos = 1_000_000_000L,
            quality = FrameQuality(1.0f, 1.0f, true),
            modelVersion = "test"
        )
        verifier.verify(frame1, dummyBuffer, 320, 240)

        // 2. 프레임 2: 66ms 뒤 미세한 손떨림 (Δx = 0.01, 속도 = 0.15 / sec <= 0.55)
        val frame2 = SignalObservation(
            ephemeralTrackId = "sig-stat",
            state = ObservedSignalState.RED,
            score = 0.95f,
            box = NormalizedBox(0.46f, 0.20f, 0.56f, 0.40f),
            frameTimestampNanos = 1_066_000_000L,
            quality = FrameQuality(1.0f, 1.0f, true),
            modelVersion = "test"
        )
        val result = verifier.verify(frame2, dummyBuffer, 320, 240)

        // 손떨림 수준의 정상 정지 신호등은 모션 필터를 안전하게 통과함
        assertTrue(result.isVerified)
        assertEquals(ObservedSignalState.RED, result.verifiedState)
    }

    @Test
    fun testDarkHousingContrastVerificationMethod() {
        val width = 100
        val height = 100
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        // 1. 신호등 케이스(다크 하우징): 외곽 배경이 어두움 (V = 0.15)
        for (i in 0 until width * height) {
            buffer.put(35.toByte())
            buffer.put(35.toByte())
            buffer.put(35.toByte())
            buffer.put(255.toByte())
        }
        buffer.rewind()

        // 램프 중심: minX=40, maxX=60, minY=40, maxY=60
        val hasDarkHousing = estimator.verifyDarkHousingContrast(
            buffer = buffer,
            width = width,
            height = height,
            minX = 40,
            maxX = 60,
            minY = 40,
            maxY = 60,
            lampBrightness = 0.92f
        )
        assertTrue("어두운 하우징 케이스는 콘트라스트 검증을 통과해야 함", hasDarkHousing)

        // 2. 하우징 없는 간판/유리창: 외곽 배경도 밝음 (V = 0.85, 대비 = 0.05 < 0.20)
        buffer.clear()
        for (i in 0 until width * height) {
            buffer.put(215.toByte())
            buffer.put(215.toByte())
            buffer.put(215.toByte())
            buffer.put(255.toByte())
        }
        buffer.rewind()

        val hasNoHousing = estimator.verifyDarkHousingContrast(
            buffer = buffer,
            width = width,
            height = height,
            minX = 40,
            maxX = 60,
            minY = 40,
            maxY = 60,
            lampBrightness = 0.90f
        )
        assertFalse("밝은 배경의 간판/전광판은 다크 하우징 부재로 기각되어야 함", hasNoHousing)
    }

    @Test
    fun testRejectsGreenSignboardWithoutDarkHousingInFullPipeline() = runTest {
        val width = 320
        val height = 240
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        // 밝은 벽면/간판 배경 (R=210, G=210, B=210)
        for (i in 0 until width * height) {
            buffer.put(210.toByte())
            buffer.put(210.toByte())
            buffer.put(210.toByte())
            buffer.put(255.toByte())
        }

        // 간판 위에 적힌 초록색 글씨/그림 (R=20, G=225, B=140)
        // 하우징 케이스 없음!
        for (y in 50..70) {
            for (x in 150..170) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 20.toByte())
                buffer.put(offset + 1, 225.toByte())
                buffer.put(offset + 2, 140.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }
        buffer.rewind()

        val frame = FrameRef.createForTesting(width = width, height = height, rgbaBuffer = buffer)
        val observations = estimator.estimate(frame)

        assertEquals(1, observations.size)
        // 하우징이 없는 간판의 녹색은 보행자 신호등이 아니므로 UNKNOWN으로 기각되어야 함 (Zero False-Green)
        assertEquals(ObservedSignalState.UNKNOWN, observations.first().state)
    }

    @Test
    fun testIsolatesPedestrianLightFromCoexistingVehicleLight() = runTest {
        val width = 320
        val height = 240
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        // 어두운 배경 (신호등 하우징 환경)
        for (i in 0 until width * height) {
            buffer.put(30.toByte())
            buffer.put(30.toByte())
            buffer.put(30.toByte())
            buffer.put(255.toByte())
        }

        // 1. 좌측 가로형 차량 신호등 (y: 40..46, x: 40..75 -> width 36, height 7, 가로 비율 > 5.0)
        for (y in 40..46) {
            for (x in 40..75) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 240.toByte())
                buffer.put(offset + 1, 30.toByte())
                buffer.put(offset + 2, 30.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }

        // 2. 우측 세로형 보행자 신호등 (y: 40..50, x: 155..160 -> width 6, height 11, 세로 비율 정상)
        for (y in 40..50) {
            for (x in 155..160) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 245.toByte())
                buffer.put(offset + 1, 25.toByte())
                buffer.put(offset + 2, 25.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }
        buffer.rewind()

        val frame = FrameRef.createForTesting(width = width, height = height, rgbaBuffer = buffer)
        val observations = estimator.estimate(frame)

        assertEquals(1, observations.size)
        val obs = observations.first()
        // 가로형 차량 신호등에 간섭받지 않고 우측 세로형 보행자 신호등을 정확히 식별
        assertEquals(ObservedSignalState.RED, obs.state)
        // 검출된 박스의 중심이 보행자 신호등 위치(x=155..160) 주변이어야 함
        val boxCenterX = (obs.box.left + obs.box.right) / 2f * width
        assertTrue("박스 중심($boxCenterX)이 보행자 신호등 부근이어야 함", boxCenterX in 140f..175f)
    }
}
