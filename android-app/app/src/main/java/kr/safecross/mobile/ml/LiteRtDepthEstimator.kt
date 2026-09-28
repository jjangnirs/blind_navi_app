package kr.safecross.mobile.ml

import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.ml.contract.ModelContractValidator
import kr.safecross.mobile.ml.contract.ModelManifest
import kr.safecross.mobile.ml.contract.TensorSpec
import kr.safecross.mobile.ml.runtime.DeterministicTestModelRunner
import kr.safecross.mobile.ml.runtime.ExecutionBackend
import kr.safecross.mobile.ml.runtime.ModelRunner
import kr.safecross.mobile.ml.runtime.TfliteModelRunner
import kr.safecross.mobile.perception.DepthEstimator
import kr.safecross.mobile.perception.DepthObservation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * 온디바이스 LiteRT MiDaS 기반 상대 깊이(depth) 추정기 (best_depth_model_fp16.tflite).
 *
 * 입력/출력 규격은 export_depth_model_to_tflite.py로 변환된 모델 파일에서 직접 추출한 값과
 * 반드시 일치해야 한다: 입력 [1,384,640,3] NHWC float32(0~1 스케일, mean/std 정규화 없음),
 * 출력 [1,384,640,1] float32(ReLU 적용, 항상 0 이상인 상대 깊이값. 클수록 카메라에 더 가까움).
 * 전처리는 학습/변환 스크립트와 동일하게 종횡비를 무시한 단순 리사이즈(bilinear)를 사용한다
 * (letterbox가 아님 - compare_pytorch_vs_tflite_depth.py의 PIL BILINEAR resize와 동일 규약).
 */
class LiteRtDepthEstimator(
    private val modelBytes: ByteArray,
    private val manifest: ModelManifest,
    private var primaryRunner: ModelRunner = DeterministicTestModelRunner(ExecutionBackend.CPU),
    private var cpuFallbackRunner: ModelRunner = DeterministicTestModelRunner(ExecutionBackend.CPU)
) : DepthEstimator, AutoCloseable {

    var activeBackend: ExecutionBackend = primaryRunner.backend
        private set

    var fallbackOccurred: Boolean = false
        private set

    val isContractValid: Boolean

    private val modelHeight = manifest.inputTensor.shape.getOrElse(1) { 384 }
    private val modelWidth = manifest.inputTensor.shape.getOrElse(2) { 640 }

    init {
        val validation = ModelContractValidator.validate(modelBytes, manifest, emptyList())
        isContractValid = validation.isValid
    }

    override suspend fun estimate(frame: FrameRef): DepthObservation {
        val srcBuffer = frame.rgbaBuffer
        if (!isContractValid || srcBuffer == null || frame.width <= 0 || frame.height <= 0) {
            return DepthObservation.unavailable()
        }

        val inputBuffer = ByteBuffer.allocateDirect(1 * modelHeight * modelWidth * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        resizeRgbaToNormalizedRgb(srcBuffer, frame.width, frame.height, modelWidth, modelHeight, inputBuffer)
        inputBuffer.rewind()

        val output = Array(1) { Array(modelHeight) { Array(modelWidth) { FloatArray(1) } } }
        val outputMap = mapOf<Int, Any>(0 to output)

        try {
            primaryRunner.runInference(inputBuffer, outputMap)
            activeBackend = primaryRunner.backend
        } catch (accelEx: Exception) {
            fallbackOccurred = true
            try {
                inputBuffer.rewind()
                cpuFallbackRunner.runInference(inputBuffer, outputMap)
                activeBackend = ExecutionBackend.CPU
            } catch (cpuEx: Exception) {
                activeBackend = ExecutionBackend.CPU
                return DepthObservation.unavailable()
            }
        }

        val depthMap = FloatArray(modelWidth * modelHeight)
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (y in 0 until modelHeight) {
            val row = output[0][y]
            for (x in 0 until modelWidth) {
                val v = row[x][0]
                depthMap[y * modelWidth + x] = v
                if (v < min) min = v
                if (v > max) max = v
            }
        }

        return DepthObservation(
            isAvailable = true,
            depthMap = depthMap,
            mapWidth = modelWidth,
            mapHeight = modelHeight,
            minDepth = min,
            maxDepth = max,
            quality = 1.0f
        )
    }

    override fun close() {
        primaryRunner.close()
        cpuFallbackRunner.close()
    }

    companion object {
        private const val MODEL_ASSET_PATH = "models/best_depth_model_fp16.tflite"

        /**
         * 실기기용 러너를 실제로 연결한 프로덕션 인스턴스를 생성합니다.
         * assets 로드 실패, 모델 계약 검증 실패 등 어떤 이유로든 안전하게 사용할 수 없으면 null을 반환합니다.
         */
        fun createDefault(context: android.content.Context): LiteRtDepthEstimator? {
            val modelBytes = try {
                context.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
            } catch (_: Exception) {
                null
            } ?: return null

            if (modelBytes.size < 1024) return null

            return try {
                val sha256 = ModelContractValidator.computeSha256(modelBytes)
                val manifest = ModelManifest(
                    modelName = "best_depth_model",
                    modelVersion = "fp16-1.0.0",
                    sha256 = sha256,
                    minAppVersion = "0.1.0",
                    disabled = false,
                    inputTensor = TensorSpec("serving_default_input:0", listOf(1, 384, 640, 3), "FLOAT32"),
                    outputTensors = listOf(
                        TensorSpec("PartitionedCall:0", listOf(1, 384, 640, 1), "FLOAT32")
                    ),
                    labelsOrder = emptyList()
                )
                val runner = TfliteModelRunner.createFromBytes(modelBytes, ExecutionBackend.CPU, numThreads = 4)
                LiteRtDepthEstimator(
                    modelBytes = modelBytes,
                    manifest = manifest,
                    primaryRunner = runner,
                    cpuFallbackRunner = runner
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * RGBA8888 원본 버퍼를 모델 입력 해상도로 쌍선형(bilinear) 리샘플링하며
         * 0~1 정규화된 float32 NHWC 텐서로 채운다 (알파 채널 제외).
         * src는 duplicate() + 절대 인덱스(get(index))로만 읽어 원본 프레임 버퍼의
         * position/limit을 변경하지 않는다 (같은 프레임을 읽는 다른 추정기와 안전하게 공유).
         */
        private fun resizeRgbaToNormalizedRgb(
            src: ByteBuffer,
            srcWidth: Int,
            srcHeight: Int,
            dstWidth: Int,
            dstHeight: Int,
            dst: ByteBuffer
        ) {
            val srcDup = src.duplicate()
            val xRatio = srcWidth.toFloat() / dstWidth
            val yRatio = srcHeight.toFloat() / dstHeight

            fun readChannel(px: Int, py: Int, channel: Int): Int {
                val cx = px.coerceIn(0, srcWidth - 1)
                val cy = py.coerceIn(0, srcHeight - 1)
                val idx = (cy * srcWidth + cx) * 4 + channel
                return srcDup.get(idx).toInt() and 0xFF
            }

            for (dy in 0 until dstHeight) {
                val srcYf = (dy + 0.5f) * yRatio - 0.5f
                val y0 = floor(srcYf).toInt()
                val fy = srcYf - y0
                for (dx in 0 until dstWidth) {
                    val srcXf = (dx + 0.5f) * xRatio - 0.5f
                    val x0 = floor(srcXf).toInt()
                    val fx = srcXf - x0

                    for (c in 0 until 3) {
                        val p00 = readChannel(x0, y0, c)
                        val p10 = readChannel(x0 + 1, y0, c)
                        val p01 = readChannel(x0, y0 + 1, c)
                        val p11 = readChannel(x0 + 1, y0 + 1, c)
                        val top = p00 + (p10 - p00) * fx
                        val bottom = p01 + (p11 - p01) * fx
                        val value = top + (bottom - top) * fy
                        dst.putFloat(value / 255f)
                    }
                }
            }
        }
    }
}
