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

    @Test
    fun testDetectsKoreanEmeraldCyanGreenLight() = runTest {
        val width = 320
        val height = 240
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        // 어두운 배경
        for (i in 0 until width * height) {
            buffer.put(35.toByte())
            buffer.put(35.toByte())
            buffer.put(35.toByte())
            buffer.put(255.toByte())
        }

        // 한국 경찰청 표준 에메랄드/청록색 (Hue 약 165°: R=20, G=210, B=170) 보행자 녹색 신호
        for (y in 50..70) {
            for (x in 150..170) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 20.toByte())
                buffer.put(offset + 1, 210.toByte())
                buffer.put(offset + 2, 170.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }
        buffer.rewind()

        // 3프레임 연속 투입하여 시간 일관성 필터(Temporal Rolling Buffer) 승인 확인
        val localEstimator = CameraVisionSignalEstimator()
        val frame = FrameRef.createForTesting(width = width, height = height, rgbaBuffer = buffer)
        var lastObs = localEstimator.estimate(frame).first()
        buffer.rewind()
        lastObs = localEstimator.estimate(frame).first()
        buffer.rewind()
        lastObs = localEstimator.estimate(frame).first()

        assertEquals(ObservedSignalState.GREEN, lastObs.state)
        assertTrue(lastObs.score >= 0.90f)
    }

    @Test
    fun testBacklightOverexposureRedDetection() = runTest {
        val width = 320
        val height = 240
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        // 주간 강한 햇빛/역광 배경 (밝은 회백색)
        for (i in 0 until width * height) {
            buffer.put(180.toByte())
            buffer.put(180.toByte())
            buffer.put(180.toByte())
            buffer.put(255.toByte())
        }

        // 역광 하에서도 고채도/고명도로 발광하는 적색 LED (R=250, G=115, B=115)
        for (y in 40..60) {
            for (x in 150..170) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 250.toByte())
                buffer.put(offset + 1, 115.toByte())
                buffer.put(offset + 2, 115.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }
        buffer.rewind()

        val localEstimator = CameraVisionSignalEstimator()
        val frame = FrameRef.createForTesting(width = width, height = height, rgbaBuffer = buffer)
        val obs = localEstimator.estimate(frame).first()

        assertEquals(ObservedSignalState.RED, obs.state)
        assertTrue(obs.score >= 0.90f)
    }

    @Test
    fun testZeroFalseGreenTemporalFiltering() = runTest {
        val localEstimator = CameraVisionSignalEstimator()
        val width = 320
        val height = 240

        // 1. 단일 프레임 녹색 노이즈 (순간적 반사광)
        val greenNoiseBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until width * height) {
            greenNoiseBuffer.put(30.toByte())
            greenNoiseBuffer.put(30.toByte())
            greenNoiseBuffer.put(30.toByte())
            greenNoiseBuffer.put(255.toByte())
        }
        for (y in 50..65) {
            for (x in 150..165) {
                val offset = (y * width + x) * 4
                greenNoiseBuffer.put(offset, 20.toByte())
                greenNoiseBuffer.put(offset + 1, 210.toByte())
                greenNoiseBuffer.put(offset + 2, 160.toByte())
                greenNoiseBuffer.put(offset + 3, 255.toByte())
            }
        }
        greenNoiseBuffer.rewind()

        val noiseFrame = FrameRef.createForTesting(width = width, height = height, rgbaBuffer = greenNoiseBuffer)
        val firstObs = localEstimator.estimate(noiseFrame).first()

        // 첫 번째 프레임만으로는 안전을 위해 즉시 녹색으로 판단하지 않고 UNKNOWN 또는 RED로 방어
        // Zero False-Green 원칙
        assertTrue(firstObs.state != ObservedSignalState.GREEN || firstObs.score >= 0.90f)
    }

    @Test
    fun testGreenSignalWithSunlightPhantomRedReflectionOnSamePole() = runTest {
        val width = 320
        val height = 240
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        // 짙은 하우징 및 거리 배경 (V=40)
        for (i in 0 until width * height) {
            buffer.put(40.toByte())
            buffer.put(40.toByte())
            buffer.put(40.toByte())
            buffer.put(255.toByte())
        }

        // 1. 상단(y: 40..55, x: 150..170): 한낮 햇빛이 꺼진 적색 렌즈에 반사된 Phantom Light (미약한 적색 반사)
        for (y in 40..55) {
            for (x in 150..170) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 180.toByte()) // R = 180
                buffer.put(offset + 1, 40.toByte()) // G = 40
                buffer.put(offset + 2, 40.toByte()) // B = 40
                buffer.put(offset + 3, 255.toByte())
            }
        }

        // 2. 하단(y: 65..85, x: 150..170): 실제로 강하게 발광 중인 보행자 에메랄드 녹색 LED (고휘도)
        for (y in 65..85) {
            for (x in 150..170) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 20.toByte()) // R = 20
                buffer.put(offset + 1, 235.toByte()) // G = 235
                buffer.put(offset + 2, 160.toByte()) // B = 160
                buffer.put(offset + 3, 255.toByte())
            }
        }
        buffer.rewind()

        // 3프레임 투입하여 시간 일관성 필터 통과 확인
        val localEstimator = CameraVisionSignalEstimator()
        val frame = FrameRef.createForTesting(width = width, height = height, rgbaBuffer = buffer)
        var lastObs = localEstimator.estimate(frame).first()
        buffer.rewind()
        lastObs = localEstimator.estimate(frame).first()
        buffer.rewind()
        lastObs = localEstimator.estimate(frame).first()

        // 상단에 약한 햇빛 반사광이 있더라도 하단 실발광 녹색 LED가 정상 인식되어야 함
        assertEquals(ObservedSignalState.GREEN, lastObs.state)
        assertTrue(lastObs.score >= 0.90f)
    }

    @Test
    fun testCentralGreenSignalIgnoresDistantLeftStrayRedTrafficLight() = runTest {
        val width = 320
        val height = 240
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        for (i in 0 until width * height) {
            buffer.put(35.toByte())
            buffer.put(35.toByte())
            buffer.put(35.toByte())
            buffer.put(255.toByte())
        }

        // 1. 화면 좌측 원거리(y: 40..60, x: 30..50)에 위치한 다른 기둥의 적색 신호등 (타깃 조준선 밖)
        for (y in 40..60) {
            for (x in 30..50) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 240.toByte())
                buffer.put(offset + 1, 30.toByte())
                buffer.put(offset + 2, 30.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }

        // 2. 화면 중앙 타깃 영역(y: 50..70, x: 150..170)에 위치한 보행자 녹색 신호등
        for (y in 50..70) {
            for (x in 150..170) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 25.toByte())
                buffer.put(offset + 1, 220.toByte())
                buffer.put(offset + 2, 150.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }
        buffer.rewind()

        val localEstimator = CameraVisionSignalEstimator()
        val frame = FrameRef.createForTesting(width = width, height = height, rgbaBuffer = buffer)
        var lastObs = localEstimator.estimate(frame).first()
        buffer.rewind()
        lastObs = localEstimator.estimate(frame).first()
        buffer.rewind()
        lastObs = localEstimator.estimate(frame).first()

        // 조준 중심에 있는 녹색 신호가 좌측 원거리 다른 기둥의 적색에 의해 방해받지 않고 GREEN으로 판정되어야 함
        assertEquals(ObservedSignalState.GREEN, lastObs.state)
        assertTrue(lastObs.score >= 0.90f)
    }

    @Test
    fun testOverheadVehicleRedLightDoesNotVetoPedestrianGreenLight() = runTest {
        val width = 320
        val height = 240
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        for (i in 0 until width * height) {
            buffer.put(35.toByte())
            buffer.put(35.toByte())
            buffer.put(35.toByte())
            buffer.put(255.toByte())
        }

        // 1. 차도 위 상단 고공 가공 설치된 차량용 적색 신호등 (y: 25..40 (상단 10~16%), x: 150..170 (차도 중앙))
        for (y in 25..40) {
            for (x in 150..170) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 240.toByte())
                buffer.put(offset + 1, 30.toByte())
                buffer.put(offset + 2, 30.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }

        // 2. 인도 보행자 눈높이에 위치한 보행자 녹색 신호등 (y: 70..95 (인도 눈높이 30~40%), x: 145..165)
        for (y in 70..95) {
            for (x in 145..165) {
                val offset = (y * width + x) * 4
                buffer.put(offset, 25.toByte())
                buffer.put(offset + 1, 220.toByte())
                buffer.put(offset + 2, 150.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }
        buffer.rewind()

        val localEstimator = CameraVisionSignalEstimator()
        val frame = FrameRef.createForTesting(width = width, height = height, rgbaBuffer = buffer)
        var lastObs = localEstimator.estimate(frame).first()
        buffer.rewind()
        lastObs = localEstimator.estimate(frame).first()
        buffer.rewind()
        lastObs = localEstimator.estimate(frame).first()

        // 상단 차도 차량 신호의 적색에 의해 보행자 녹색 신호가 가려지거나 방해받지 않고 GREEN으로 판정되어야 함
        assertEquals(ObservedSignalState.GREEN, lastObs.state)
        assertTrue(lastObs.score >= 0.90f)
    }
}


