package kr.safecross.mobile.ml

import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.ml.contract.ModelContractValidator
import kr.safecross.mobile.ml.contract.ModelManifest
import kr.safecross.mobile.ml.runtime.DeterministicTestModelRunner
import kr.safecross.mobile.ml.runtime.ExecutionBackend
import kr.safecross.mobile.ml.runtime.ModelRunner
import kr.safecross.mobile.ml.vision.VisionTransforms
import kr.safecross.mobile.perception.CrosswalkObservation
import kr.safecross.mobile.perception.CrosswalkSceneEstimator
import kr.safecross.mobile.perception.PointF
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 온디바이스 LiteRT 횡단보도 형상 및 영역 추정기 (SR-F-052, TRD 4.5).
 */
class LiteRtCrosswalkSceneEstimator(
    private val modelBytes: ByteArray,
    private val manifest: ModelManifest,
    private val labels: List<String>,
    private var primaryRunner: ModelRunner = DeterministicTestModelRunner(ExecutionBackend.CPU),
    private var cpuFallbackRunner: ModelRunner = DeterministicTestModelRunner(ExecutionBackend.CPU)
) : CrosswalkSceneEstimator, AutoCloseable {

    var activeBackend: ExecutionBackend = primaryRunner.backend
        private set

    var fallbackOccurred: Boolean = false
        private set

    val isContractValid: Boolean

    init {
        val validation = ModelContractValidator.validate(modelBytes, manifest, labels)
        isContractValid = validation.isValid
    }

    override suspend fun estimate(frame: FrameRef): CrosswalkObservation {
        // 계약 검증 실패 또는 킬스위치 시 안전 반환
        if (!isContractValid) {
            return CrosswalkObservation(
                hasCrosswalk = false,
                polygon = null,
                entrancePoint = null,
                directionDegrees = null,
                quality = 0.0f,
                confidence = 0.0f
            )
        }

        val transform = VisionTransforms.computeLetterboxTransform(
            srcWidth = frame.width,
            srcHeight = frame.height,
            dstWidth = manifest.inputTensor.shape.getOrElse(1) { 320 },
            dstHeight = manifest.inputTensor.shape.getOrElse(2) { 320 }
        )

        val inputBuffer = ByteBuffer.allocateDirect(1 * 320 * 320 * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        // 출력 버퍼 (polygon: [1, 8], entrance: [1, 2], direction: [1, 1], quality: [1, 1])
        val outPolygon = Array(1) { FloatArray(8) }
        val outEntrance = Array(1) { FloatArray(2) }
        val outDirection = Array(1) { FloatArray(1) }
        val outQuality = Array(1) { FloatArray(1) }

        val outputMap = mapOf<Int, Any>(
            0 to outPolygon,
            1 to outEntrance,
            2 to outDirection,
            3 to outQuality
        )

        try {
            primaryRunner.runInference(inputBuffer, outputMap)
            activeBackend = primaryRunner.backend
        } catch (accelEx: Exception) {
            fallbackOccurred = true
            try {
                cpuFallbackRunner.runInference(inputBuffer, outputMap)
                activeBackend = ExecutionBackend.CPU
            } catch (cpuEx: Exception) {
                activeBackend = ExecutionBackend.CPU
                return CrosswalkObservation(
                    hasCrosswalk = false,
                    polygon = null,
                    entrancePoint = null,
                    directionDegrees = null,
                    quality = 0.0f,
                    confidence = 0.0f
                )
            }
        }

        val quality = outQuality[0][0].coerceIn(0f, 1f)
        val rawEntrance = PointF(outEntrance[0][0], outEntrance[0][1])
        val rawPolygon = listOf(
            PointF(outPolygon[0][0], outPolygon[0][1]),
            PointF(outPolygon[0][2], outPolygon[0][3]),
            PointF(outPolygon[0][4], outPolygon[0][5]),
            PointF(outPolygon[0][6], outPolygon[0][7])
        )

        // 원본 프레임 좌표계로 역변환 복원
        val unletterboxedEntrance = VisionTransforms.unletterboxPoint(rawEntrance, transform)
        val unletterboxedPolygon = VisionTransforms.unletterboxPolygon(rawPolygon, transform)

        val hasCrosswalk = quality >= 0.4f

        return CrosswalkObservation(
            hasCrosswalk = hasCrosswalk,
            polygon = if (hasCrosswalk) unletterboxedPolygon else null,
            entrancePoint = if (hasCrosswalk) unletterboxedEntrance else null,
            directionDegrees = if (hasCrosswalk) outDirection[0][0] else null,
            quality = quality,
            confidence = quality
        )
    }

    override fun close() {
        primaryRunner.close()
        cpuFallbackRunner.close()
    }
}
