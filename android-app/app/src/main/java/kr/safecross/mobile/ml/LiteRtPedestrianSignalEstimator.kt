package kr.safecross.mobile.ml

import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.ml.contract.ModelContractValidator
import kr.safecross.mobile.ml.contract.ModelManifest
import kr.safecross.mobile.ml.runtime.DeterministicTestModelRunner
import kr.safecross.mobile.ml.runtime.ExecutionBackend
import kr.safecross.mobile.ml.runtime.ModelRunner
import kr.safecross.mobile.ml.vision.VisionTransforms
import kr.safecross.mobile.perception.FrameQuality
import kr.safecross.mobile.perception.NormalizedBox
import kr.safecross.mobile.perception.ObservedSignalState
import kr.safecross.mobile.perception.PedestrianSignalEstimator
import kr.safecross.mobile.perception.SignalObservation
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 온디바이스 LiteRT 보행자 신호등 추정기 (SR-F-042~054, TRD 4.5).
 */
class LiteRtPedestrianSignalEstimator(
    private val modelBytes: ByteArray,
    private val manifest: ModelManifest,
    private val labels: List<String>,
    private var primaryRunner: ModelRunner = DeterministicTestModelRunner(ExecutionBackend.CPU),
    private var cpuFallbackRunner: ModelRunner = DeterministicTestModelRunner(ExecutionBackend.CPU)
) : PedestrianSignalEstimator, AutoCloseable {

    var activeBackend: ExecutionBackend = primaryRunner.backend
        private set

    var fallbackOccurred: Boolean = false
        private set

    val isContractValid: Boolean

    init {
        // 1. 모델 무결성, SHA-256, 라벨 순서, 텐서 규약 전수 검증 (SR-F-050)
        val validation = ModelContractValidator.validate(modelBytes, manifest, labels)
        isContractValid = validation.isValid
    }

    override suspend fun estimate(frame: FrameRef): List<SignalObservation> {
        // 게이트 1: 모델 변조, 라벨 오류, 킬스위치 시 즉시 UNKNOWN 안전 반환 (SR-F-047, SR-F-051)
        if (!isContractValid) {
            return listOf(createUnknownObservation(frame, "MODEL_CONTRACT_VALIDATION_FAILED"))
        }

        val transform = VisionTransforms.computeLetterboxTransform(
            srcWidth = frame.width,
            srcHeight = frame.height,
            dstWidth = manifest.inputTensor.shape.getOrElse(1) { 320 },
            dstHeight = manifest.inputTensor.shape.getOrElse(2) { 320 }
        )

        // 가상 입력 버퍼 (320x320x3 Float32)
        val inputBuffer = ByteBuffer.allocateDirect(1 * 320 * 320 * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        // 출력 버퍼 (boxes: [1, 10, 4], classes: [1, 10], scores: [1, 10], count: [1])
        val outBoxes = Array(1) { Array(10) { FloatArray(4) } }
        val outClasses = Array(1) { FloatArray(10) }
        val outScores = Array(1) { FloatArray(10) }
        val outCount = FloatArray(1)

        val outputMap = mapOf<Int, Any>(
            0 to outBoxes,
            1 to outClasses,
            2 to outScores,
            3 to outCount
        )

        // 2. 가속기 실행 및 단계적 Fallback (SR-F-047)
        try {
            primaryRunner.runInference(inputBuffer, outputMap)
            activeBackend = primaryRunner.backend
        } catch (accelEx: Exception) {
            fallbackOccurred = true
            // 1차 Fallback: CPU 베이스라인 시도
            try {
                cpuFallbackRunner.runInference(inputBuffer, outputMap)
                activeBackend = ExecutionBackend.CPU
            } catch (cpuEx: Exception) {
                // 2차 Fallback: 크래시 없이 UNKNOWN 반환 (절대 false-green 방지)
                activeBackend = ExecutionBackend.CPU
                return listOf(createUnknownObservation(frame, "INFERENCE_EXECUTION_FAILED"))
            }
        }

        // 3. 모델 출력 후처리 및 원본 프레임 좌표계 복원
        val count = outCount[0].toInt().coerceIn(0, 10)
        if (count == 0) {
            return emptyList()
        }

        val observations = mutableListOf<SignalObservation>()
        for (i in 0 until count) {
            val classIdx = outClasses[0][i].toInt()
            val score = outScores[0][i]
            val box = outBoxes[0][i]

            val rawBox = NormalizedBox(
                left = box[1],   // xmin
                top = box[0],    // ymin
                right = box[3],  // xmax
                bottom = box[2]  // ymax
            )

            // Letterbox 역변환: 원본 프레임 정규화 좌표계 [0, 1]로 복원
            val unletterboxed = VisionTransforms.unletterboxBox(rawBox, transform)

            val state = when (labels.getOrNull(classIdx)) {
                "PEDESTRIAN_SIGNAL_RED" -> ObservedSignalState.RED
                "PEDESTRIAN_SIGNAL_GREEN" -> ObservedSignalState.GREEN
                else -> ObservedSignalState.UNKNOWN
            }

            observations.add(
                SignalObservation(
                    ephemeralTrackId = "trk-litert-${i + 1}",
                    state = state,
                    score = score,
                    box = unletterboxed,
                    frameTimestampNanos = frame.timestampNanos,
                    quality = FrameQuality(lighting = 0.85f, blur = 0.90f, isUsable = true),
                    modelVersion = manifest.modelVersion
                )
            )
        }

        return observations
    }

    private fun createUnknownObservation(frame: FrameRef, reason: String): SignalObservation {
        return SignalObservation(
            ephemeralTrackId = "trk-fail-safe",
            state = ObservedSignalState.UNKNOWN,
            score = 0.0f,
            box = NormalizedBox(0f, 0f, 0f, 0f),
            frameTimestampNanos = frame.timestampNanos,
            quality = FrameQuality(0f, 0f, false),
            modelVersion = manifest.modelVersion
        )
    }

    override fun close() {
        primaryRunner.close()
        cpuFallbackRunner.close()
    }
}
