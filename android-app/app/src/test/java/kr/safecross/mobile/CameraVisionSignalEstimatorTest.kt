package kr.safecross.mobile

import kotlinx.coroutines.test.runTest
import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.perception.CameraVisionSignalEstimator
import kr.safecross.mobile.perception.ObservedSignalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CameraVisionSignalEstimatorTest {

    private val estimator = CameraVisionSignalEstimator()

    @Test
    fun testDetectsRedLightFromRgbaBuffer() = runTest {
        val width = 320
        val height = 240
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        // 짙은 거리 배경 (회색/어두운 톤)
        for (i in 0 until width * height) {
            buffer.put(50.toByte()) // R
            buffer.put(50.toByte()) // G
            buffer.put(50.toByte()) // B
            buffer.put(255.toByte()) // A
        }

        // 상단 중앙(y: 40..60, x: 150..170)에 고휘도 적색 신호등 LED 램프 주입
        for (y in 40..60) {
            for (x in 150..170) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 240.toByte()) // R = 240
                buffer.put(offset + 1, 30.toByte()) // G = 30
                buffer.put(offset + 2, 30.toByte()) // B = 30
                buffer.put(offset + 3, 255.toByte())
            }
        }
        buffer.rewind()

        val frame = FrameRef.createForTesting(width = width, height = height, rgbaBuffer = buffer)
        val observations = estimator.estimate(frame)

        assertEquals(1, observations.size)
        val obs = observations.first()
        assertEquals(ObservedSignalState.RED, obs.state)
        assertTrue(obs.score >= 0.92f)
        assertNotNull(obs.box)
    }

    @Test
    fun testDetectsGreenLightFromRgbaBuffer() = runTest {
        val width = 320
        val height = 240
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        // 배경
        for (i in 0 until width * height) {
            buffer.put(40.toByte())
            buffer.put(40.toByte())
            buffer.put(40.toByte())
            buffer.put(255.toByte())
        }

        // 상단(y: 50..70, x: 150..170)에 에메랄드 녹색 보행 신호등 LED 주입
        for (y in 50..70) {
            for (x in 150..170) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 25.toByte()) // R = 25
                buffer.put(offset + 1, 220.toByte()) // G = 220
                buffer.put(offset + 2, 140.toByte()) // B = 140 (에메랄드 청록 LED)
                buffer.put(offset + 3, 255.toByte())
            }
        }
        buffer.rewind()

        val frame = FrameRef.createForTesting(width = width, height = height, rgbaBuffer = buffer)
        val observations = estimator.estimate(frame)

        assertEquals(1, observations.size)
        val obs = observations.first()
        assertEquals(ObservedSignalState.GREEN, obs.state)
        assertTrue(obs.score >= 0.92f)
        assertNotNull(obs.box)
    }

    @Test
    fun testNoBufferFallsBackSafely() = runTest {
        val frame = FrameRef.createForTesting()
        val observations = estimator.estimate(frame)

        assertEquals(1, observations.size)
        assertNotNull(observations.first().state)
    }

    @Test
    fun testRejectsYellowTrafficLight() = runTest {
        val width = 320
        val height = 240
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        // 어두운 배경
        for (i in 0 until width * height) {
            buffer.put(30.toByte())
            buffer.put(30.toByte())
            buffer.put(30.toByte())
            buffer.put(255.toByte())
        }

        // 차량용 황색(Yellow/Orange) 신호등 주입 (R=220, G=180, B=20)
        for (y in 40..60) {
            for (x in 150..170) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 220.toByte())
                buffer.put(offset + 1, 180.toByte())
                buffer.put(offset + 2, 20.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }
        buffer.rewind()

        val frame = FrameRef.createForTesting(width = width, height = height, rgbaBuffer = buffer)
        val observations = estimator.estimate(frame)

        assertEquals(1, observations.size)
        // 황색은 보행자 신호가 아니므로 UNKNOWN으로 안전 판정되어야 함
        assertEquals(ObservedSignalState.UNKNOWN, observations.first().state)
    }

    @Test
    fun testRejectsHorizontalVehicleTrafficLight() = runTest {
        val width = 320
        val height = 240
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        // 어두운 배경
        for (i in 0 until width * height) {
            buffer.put(30.toByte())
            buffer.put(30.toByte())
            buffer.put(30.toByte())
            buffer.put(255.toByte())
        }

        // 가로로 길게 늘어선 가로형 차량 신호등 (y: 45..52 (높이 8), x: 120..180 (너비 61))
        // 종횡비 Width / Height > 7
        for (y in 45..52) {
            for (x in 120..180) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 240.toByte()) // R = 240
                buffer.put(offset + 1, 30.toByte()) // G = 30
                buffer.put(offset + 2, 30.toByte()) // B = 30
                buffer.put(offset + 3, 255.toByte())
            }
        }
        buffer.rewind()

        val frame = FrameRef.createForTesting(width = width, height = height, rgbaBuffer = buffer)
        val observations = estimator.estimate(frame)

        assertEquals(1, observations.size)
        // 가로형 차량 신호등은 보행자 신호등(원형/세로형)이 아니므로 배제되어 UNKNOWN이어야 함
        assertEquals(ObservedSignalState.UNKNOWN, observations.first().state)
    }
}
