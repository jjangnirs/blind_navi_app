package kr.safecross.mobile.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.ml.runtime.ExecutionBackend
import kr.safecross.mobile.ml.runtime.TfliteModelRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * best_depth_model_fp16.tflite가 실기기/에뮬레이터의 실제 TFLite 런타임에서
 * 정상적으로 로드되고 추론을 수행하는지 확인하는 스모크 테스트.
 *
 * 입력/출력 규격은 export_depth_model_to_tflite.py로 변환된 모델 파일에서
 * 직접 추출한 값과 동일해야 한다: 입력 [1,384,640,3] NHWC float32 (0~1 스케일),
 * 출력 [1,384,640,1] float32 (ReLU 적용된 상대 깊이값, 항상 0 이상).
 */
@RunWith(AndroidJUnit4::class)
class DepthModelSmokeTest {

    private val inputHeight = 384
    private val inputWidth = 640
    private val inputChannels = 3

    @Test
    fun bestDepthModelFp16_loadsAndProducesValidDepthMap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelBytes = context.assets.open("models/best_depth_model_fp16.tflite").use { it.readBytes() }

        assertTrue("모델 파일이 비어 있지 않아야 합니다 (size=${modelBytes.size})", modelBytes.size > 1024)

        val runner = TfliteModelRunner.createFromBytes(modelBytes, ExecutionBackend.CPU, numThreads = 4)

        val inputBuffer = ByteBuffer.allocateDirect(1 * inputHeight * inputWidth * inputChannels * 4).apply {
            order(ByteOrder.nativeOrder())
            // 0~1 정규화 범위를 대표하는 중간 회색값으로 채움 (전처리 규약: 0~255 -> 0~1 스케일링만)
            repeat(inputHeight * inputWidth * inputChannels) { putFloat(0.5f) }
            rewind()
        }

        // 출력 텐서 shape [1, 384, 640, 1]과 동일한 형태의 배열
        val output = Array(1) { Array(inputHeight) { Array(inputWidth) { FloatArray(1) } } }

        try {
            runner.runInference(inputBuffer, mapOf(0 to output))
        } finally {
            runner.close()
        }

        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        var hasNaNOrInfinite = false
        for (row in output[0]) {
            for (pixel in row) {
                val v = pixel[0]
                if (v.isNaN() || v.isInfinite()) hasNaNOrInfinite = true
                if (v < min) min = v
                if (v > max) max = v
            }
        }

        assertFalse("깊이 맵에 NaN/Infinite 값이 없어야 합니다 (min=$min, max=$max)", hasNaNOrInfinite)
        assertTrue("깊이값은 ReLU 후처리로 음수가 될 수 없습니다 (min=$min)", min >= 0f)
        assertTrue("깊이 맵 출력에 변화가 있어야 합니다 (min=$min, max=$max)", max > min)
    }

    /**
     * 실제 프로덕션 통합 지점(LiteRtDepthEstimator.createDefault)이 assets 로드 -> 실제
     * 카메라 프레임 크기(640x480 RGBA, CameraPipeManager 설정과 동일)의 리사이즈/정규화 전처리 ->
     * 실기기 TFLite 추론 -> 후처리까지 전체 파이프라인을 끝까지 정상 수행하는지 검증한다.
     */
    @Test
    fun liteRtDepthEstimator_createDefault_realFrameProducesValidDepthObservation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val estimator = LiteRtDepthEstimator.createDefault(context)
        assertNotNull("createDefault는 실기기에서 null을 반환하면 안 됩니다", estimator)

        val cameraWidth = 640
        val cameraHeight = 480
        val rgba = ByteBuffer.allocateDirect(cameraWidth * cameraHeight * 4).apply {
            order(ByteOrder.nativeOrder())
            // 실제 사진처럼 상단은 밝게(하늘), 하단은 어둡게(바닥) 그라디언트를 채움
            for (y in 0 until cameraHeight) {
                val shade = (255 * y / cameraHeight).toByte()
                for (x in 0 until cameraWidth) {
                    put(shade); put(shade); put(shade); put(255.toByte())
                }
            }
            rewind()
        }
        val frame = FrameRef.createForTesting(width = cameraWidth, height = cameraHeight, rgbaBuffer = rgba)

        try {
            val result = estimator!!.estimate(frame)

            assertTrue("실제 프레임이 주어지면 깊이 관측치가 가용해야 합니다", result.isAvailable)
            assertEquals(640, result.mapWidth)
            assertEquals(384, result.mapHeight)
            assertNotNull(result.depthMap)
            assertEquals(640 * 384, result.depthMap!!.size)
            assertTrue("깊이값은 음수가 될 수 없습니다 (min=${result.minDepth})", result.minDepth >= 0f)
            assertTrue(
                "깊이 맵 출력에 변화가 있어야 합니다 (min=${result.minDepth}, max=${result.maxDepth})",
                result.maxDepth > result.minDepth
            )
        } finally {
            estimator?.close()
        }
    }
}
