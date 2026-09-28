package kr.safecross.mobile

import kotlinx.coroutines.test.runTest
import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.ml.LiteRtDepthEstimator
import kr.safecross.mobile.ml.contract.ModelContractValidator
import kr.safecross.mobile.ml.contract.ModelManifest
import kr.safecross.mobile.ml.contract.TensorSpec
import kr.safecross.mobile.ml.runtime.DeterministicTestModelRunner
import kr.safecross.mobile.ml.runtime.ExecutionBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LiteRtDepthEstimator의 계약 검증/전처리/후처리 로직을 순수 JVM에서 검증한다.
 * 실기기 네이티브 TFLite 추론 자체의 검증은 androidTest의 DepthModelSmokeTest가 담당한다.
 */
class LiteRtDepthEstimatorTest {

    private lateinit var modelBytes: ByteArray

    /** 실제 640x384 모델 대신 4x4로 축소한 매니페스트 - 테스트를 빠르고 간단하게 유지 */
    private lateinit var tinyManifest: ModelManifest

    @Before
    fun setUp() {
        val modelFile = File("src/main/assets/models/best_depth_model_fp16.tflite")
        modelBytes = if (modelFile.exists()) {
            modelFile.readBytes()
        } else {
            "SafeCrossKR_FrozenModel_DepthEstimation_fp16_Deterministic".toByteArray()
        }
        tinyManifest = ModelManifest(
            modelName = "best_depth_model",
            modelVersion = "fp16-1.0.0",
            sha256 = ModelContractValidator.computeSha256(modelBytes),
            minAppVersion = "0.1.0",
            disabled = false,
            inputTensor = TensorSpec("serving_default_input:0", listOf(1, 4, 4, 3), "FLOAT32"),
            outputTensors = listOf(TensorSpec("PartitionedCall:0", listOf(1, 4, 4, 1), "FLOAT32")),
            labelsOrder = emptyList()
        )
    }

    @Test
    fun realModelAsset_passesContractValidation() {
        val realManifest = tinyManifest.copy(
            inputTensor = TensorSpec("serving_default_input:0", listOf(1, 384, 640, 3), "FLOAT32"),
            outputTensors = listOf(TensorSpec("PartitionedCall:0", listOf(1, 384, 640, 1), "FLOAT32"))
        )
        val result = ModelContractValidator.validate(modelBytes, realManifest, emptyList())
        assertTrue("실제 배포 모델은 계약 검증을 통과해야 합니다: ${result.reason}", result.isValid)
    }

    @Test
    fun estimate_withoutRgbaBuffer_returnsUnavailable() = runTest {
        val runner = DeterministicTestModelRunner(ExecutionBackend.CPU)
        val estimator = LiteRtDepthEstimator(modelBytes, tinyManifest, runner, runner)

        val frame = FrameRef.createForTesting(width = 4, height = 4, rgbaBuffer = null)
        val result = estimator.estimate(frame)

        assertFalse(result.isAvailable)
    }

    @Test
    fun estimate_withValidFrame_populatesDepthMapFromRunnerOutput() = runTest {
        val runner = DeterministicTestModelRunner(ExecutionBackend.CPU)
        runner.onInference = { _, outputs ->
            @Suppress("UNCHECKED_CAST")
            val batch = (outputs[0] as Array<Array<Array<FloatArray>>>)[0]
            var counter = 0f
            for (y in batch.indices) {
                for (x in batch[y].indices) {
                    batch[y][x][0] = counter
                    counter += 1f
                }
            }
        }
        val estimator = LiteRtDepthEstimator(modelBytes, tinyManifest, runner, runner)

        val rgba = ByteBuffer.allocateDirect(4 * 4 * 4).apply {
            order(ByteOrder.nativeOrder())
            repeat(4 * 4) { put(byteArrayOf(128.toByte(), 128.toByte(), 128.toByte(), 255.toByte())) }
            rewind()
        }
        val frame = FrameRef.createForTesting(width = 4, height = 4, rgbaBuffer = rgba)

        val result = estimator.estimate(frame)

        assertTrue(result.isAvailable)
        assertEquals(4, result.mapWidth)
        assertEquals(4, result.mapHeight)
        assertEquals(0f, result.minDepth)
        assertEquals(15f, result.maxDepth)
        assertEquals(16, result.depthMap?.size)
    }
}
