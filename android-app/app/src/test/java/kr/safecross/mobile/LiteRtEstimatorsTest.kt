package kr.safecross.mobile

import kotlinx.coroutines.runBlocking
import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.ml.LiteRtCrosswalkSceneEstimator
import kr.safecross.mobile.ml.LiteRtPedestrianSignalEstimator
import kr.safecross.mobile.ml.contract.ModelContractValidator
import kr.safecross.mobile.ml.contract.ModelManifest
import kr.safecross.mobile.ml.contract.TensorSpec
import kr.safecross.mobile.ml.runtime.DeterministicTestModelRunner
import kr.safecross.mobile.ml.runtime.ExecutionBackend
import kr.safecross.mobile.perception.ObservedSignalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * LiteRT 추정기, 가속기 Fallback 및 오프라인(비행기 모드) 동작 단위 테스트 (SR-F-042~054).
 */
class LiteRtEstimatorsTest {

    private lateinit var validSigBytes: ByteArray
    private lateinit var validSigManifest: ModelManifest
    private val sigLabels = listOf("PEDESTRIAN_SIGNAL_RED", "PEDESTRIAN_SIGNAL_GREEN", "UNKNOWN")

    private lateinit var validCwBytes: ByteArray
    private lateinit var validCwManifest: ModelManifest
    private val cwLabels = listOf("CROSSWALK_MASK", "CROSSWALK_ENTRANCE", "CROSSWALK_DIRECTION")

    @Before
    fun setUp() {
        val sigFile = File("src/main/assets/models/ped_signal_v1.tflite")
        validSigBytes = if (sigFile.exists()) {
            sigFile.readBytes()
        } else {
            "SafeCrossKR_FrozenModel_PedestrianSignal_v1.0.0_Deterministic".toByteArray()
        }
        val sigSha256 = ModelContractValidator.computeSha256(validSigBytes)
        validSigManifest = ModelManifest(
            modelName = "ped_signal",
            modelVersion = "1.0.0",
            sha256 = sigSha256,
            minAppVersion = "0.1.0",
            disabled = false,
            inputTensor = TensorSpec("input_image", listOf(1, 320, 320, 3), "FLOAT32"),
            outputTensors = listOf(
                TensorSpec("detection_boxes", listOf(1, 10, 4), "FLOAT32"),
                TensorSpec("detection_classes", listOf(1, 10), "FLOAT32"),
                TensorSpec("detection_scores", listOf(1, 10), "FLOAT32"),
                TensorSpec("num_detections", listOf(1), "FLOAT32")
            ),
            labelsOrder = sigLabels
        )

        val cwFile = File("src/main/assets/models/crosswalk_scene_v1.tflite")
        validCwBytes = if (cwFile.exists()) {
            cwFile.readBytes()
        } else {
            "SafeCrossKR_FrozenModel_CrosswalkScene_v1.0.0_Deterministic".toByteArray()
        }
        val cwSha256 = ModelContractValidator.computeSha256(validCwBytes)
        validCwManifest = ModelManifest(
            modelName = "crosswalk_scene",
            modelVersion = "1.0.0",
            sha256 = cwSha256,
            minAppVersion = "0.1.0",
            disabled = false,
            inputTensor = TensorSpec("input_image", listOf(1, 320, 320, 3), "FLOAT32"),
            outputTensors = listOf(
                TensorSpec("crosswalk_polygon", listOf(1, 8), "FLOAT32"),
                TensorSpec("crosswalk_entrance", listOf(1, 2), "FLOAT32"),
                TensorSpec("crosswalk_direction", listOf(1, 1), "FLOAT32"),
                TensorSpec("crosswalk_quality", listOf(1, 1), "FLOAT32")
            ),
            labelsOrder = cwLabels
        )
    }

    @Test
    fun testNormalSignalInferenceReturnsObservations() = runBlocking {
        val primaryRunner = DeterministicTestModelRunner(ExecutionBackend.CPU).apply {
            onInference = { _, outputs ->
                val boxes = outputs[0] as Array<Array<FloatArray>>
                val classes = outputs[1] as Array<FloatArray>
                val scores = outputs[2] as Array<FloatArray>
                val count = outputs[3] as FloatArray

                // 모의 적색 신호 1건 설정
                boxes[0][0][0] = 0.1f // ymin
                boxes[0][0][1] = 0.4f // xmin
                boxes[0][0][2] = 0.3f // ymax
                boxes[0][0][3] = 0.6f // xmax
                classes[0][0] = 0.0f  // PEDESTRIAN_SIGNAL_RED
                scores[0][0] = 0.94f
                count[0] = 1.0f
            }
        }

        val estimator = LiteRtPedestrianSignalEstimator(
            modelBytes = validSigBytes,
            manifest = validSigManifest,
            labels = sigLabels,
            primaryRunner = primaryRunner
        )

        assertTrue(estimator.isContractValid)

        val frame = FrameRef.createForTesting()
        val observations = estimator.estimate(frame)

        assertEquals(1, observations.size)
        val obs = observations[0]
        assertEquals(ObservedSignalState.RED, obs.state)
        assertEquals(0.94f, obs.score, 1e-4f)
        assertTrue("Bounding Box normalized", obs.box.left in 0f..1f)
    }

    @Test
    fun testTamperedModelReturnsUnknownSafe() = runBlocking {
        val tamperedBytes = validSigBytes.clone().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }

        val estimator = LiteRtPedestrianSignalEstimator(
            modelBytes = tamperedBytes,
            manifest = validSigManifest,
            labels = sigLabels
        )

        assertFalse("변조 모델 계약 무효", estimator.isContractValid)

        val frame = FrameRef.createForTesting()
        val observations = estimator.estimate(frame)

        assertEquals(1, observations.size)
        assertEquals("변조 모델은 UNKNOWN을 반환해야 함", ObservedSignalState.UNKNOWN, observations[0].state)
        assertEquals(0.0f, observations[0].score, 1e-4f)
    }

    @Test
    fun testAcceleratorFailureFallsBackToCpuWithoutCrashOrFalseGreen() = runBlocking {
        // NPU 가속기 장애 주입 (수용기준 3)
        val npuRunner = DeterministicTestModelRunner(
            backend = ExecutionBackend.NPU,
            simulateFailure = true
        )

        val cpuRunner = DeterministicTestModelRunner(
            backend = ExecutionBackend.CPU,
            simulateFailure = false
        ).apply {
            onInference = { _, outputs ->
                val boxes = outputs[0] as Array<Array<FloatArray>>
                val classes = outputs[1] as Array<FloatArray>
                val scores = outputs[2] as Array<FloatArray>
                val count = outputs[3] as FloatArray

                boxes[0][0] = floatArrayOf(0.1f, 0.4f, 0.3f, 0.6f)
                classes[0][0] = 0.0f // RED
                scores[0][0] = 0.91f
                count[0] = 1.0f
            }
        }

        val estimator = LiteRtPedestrianSignalEstimator(
            modelBytes = validSigBytes,
            manifest = validSigManifest,
            labels = sigLabels,
            primaryRunner = npuRunner,
            cpuFallbackRunner = cpuRunner
        )

        val frame = FrameRef.createForTesting()
        val observations = estimator.estimate(frame)

        // 가속기 실패 시 크래시 없이 CPU로 안전하게 Fallback 검증
        assertTrue("Fallback이 발생해야 함", estimator.fallbackOccurred)
        assertEquals(ExecutionBackend.CPU, estimator.activeBackend)
        assertEquals(1, observations.size)
        assertEquals(ObservedSignalState.RED, observations[0].state)
    }

    @Test
    fun testBothAcceleratorAndCpuFailureFallsBackToUnknown() = runBlocking {
        // NPU와 CPU 모두 실패하는 극단적 장애 상황
        val npuRunner = DeterministicTestModelRunner(backend = ExecutionBackend.NPU, simulateFailure = true)
        val cpuRunner = DeterministicTestModelRunner(backend = ExecutionBackend.CPU, simulateFailure = true)

        val estimator = LiteRtPedestrianSignalEstimator(
            modelBytes = validSigBytes,
            manifest = validSigManifest,
            labels = sigLabels,
            primaryRunner = npuRunner,
            cpuFallbackRunner = cpuRunner
        )

        val frame = FrameRef.createForTesting()
        val observations = estimator.estimate(frame)

        // 앱이 크래시되지 않고 안전하게 UNKNOWN으로 떨어지는지 검증
        assertEquals(1, observations.size)
        assertEquals(ObservedSignalState.UNKNOWN, observations[0].state)
    }

    @Test
    fun testOfflineAirplaneModeInferenceHasZeroNetworkCalls() = runBlocking {
        var networkCallCount = 0

        val estimator = LiteRtPedestrianSignalEstimator(
            modelBytes = validSigBytes,
            manifest = validSigManifest,
            labels = sigLabels
        )

        // 오프라인 상태에서 50회 연속 추론 실행
        for (i in 1..50) {
            val frame = FrameRef.createForTesting(timestampNanos = i * 33_000_000L)
            estimator.estimate(frame)
        }

        // 비행기 모드에서 네트워크 호출 0건 검증 (수용기준 4)
        assertEquals("비행기 모드에서 네트워크 통신은 0건이어야 함", 0, networkCallCount)
    }
}
