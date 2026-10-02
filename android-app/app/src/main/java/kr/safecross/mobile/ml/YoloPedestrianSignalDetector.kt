package kr.safecross.mobile.ml

import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.perception.FrameQuality
import kr.safecross.mobile.perception.NormalizedBox
import kr.safecross.mobile.perception.ObservedSignalState
import kr.safecross.mobile.perception.PedestrianSignalEstimator
import kr.safecross.mobile.perception.PerceptionFlightRecorder
import kr.safecross.mobile.perception.SignalObservation
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Ultralytics YOLOv8 계열 보행신호등 검출기 (Tier 1 위치 제안기).
 *
 * - 입력: [1, 3, S, S] NCHW Float32 (RGB, 0~1 정규화, 114 회색 letterbox)
 * - 출력: [1, 4 + C, N] (cx, cy, w, h는 입력 대비 0~1 정규화, 이후 C개 클래스 점수)
 * - 클래스 이름은 모델에 내장된 Ultralytics metadata.json의 "names"에서 읽는다
 *   (예: {"0": "pedestrian_green", "1": "pedestrian_red"}).
 *
 * 실측 결과 이 모델의 단독 신뢰도는 0.3~0.5 수준으로 낮으므로, 색상 판정의 최종 근거로 쓰지 않고
 * 신호등 위치(ROI) 제안 용도로만 사용한다. 색상은 TwoTierHybridSignalEstimator에서
 * HSV/OpenCV 분석과 교차 검증한다.
 */
class YoloPedestrianSignalDetector private constructor(
    private val interpreter: Interpreter,
    private val inputSize: Int,
    private val numAnchors: Int,
    private val classStates: List<ObservedSignalState>,
    private val confThreshold: Float,
    private val iouThreshold: Float,
    private val maxDetections: Int
) : PedestrianSignalEstimator, AutoCloseable {

    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(3 * inputSize * inputSize * 4)
        .order(ByteOrder.nativeOrder())
    private val inputFloats: FloatBuffer = inputBuffer.asFloatBuffer()
    private val output = Array(1) { Array(4 + classStates.size) { FloatArray(numAnchors) } }
    private val lock = Any()
    private var closed = false

    override suspend fun estimate(frame: FrameRef): List<SignalObservation> {
        val buffer = frame.rgbaBuffer ?: return emptyList()
        if (frame.width <= 0 || frame.height <= 0 || buffer.capacity() < frame.width * frame.height * 4) {
            return emptyList()
        }

        synchronized(lock) {
            if (closed) return emptyList()
            val startNs = System.nanoTime()

            val scale = min(inputSize.toFloat() / frame.width, inputSize.toFloat() / frame.height)
            val contentW = (frame.width * scale).toInt().coerceIn(1, inputSize)
            val contentH = (frame.height * scale).toInt().coerceIn(1, inputSize)
            val padX = (inputSize - contentW) / 2
            val padY = (inputSize - contentH) / 2
            fillLetterboxedInput(buffer, frame.width, frame.height, scale, contentW, contentH, padX, padY)

            return try {
                inputBuffer.rewind()
                interpreter.run(inputBuffer, output)
                val detections = decode(frame, scale, padX, padY)
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                PerceptionFlightRecorder.record(
                    "YOLO",
                    "n=${detections.size} ${elapsedMs}ms " + detections.take(3).joinToString(" ") {
                        "${it.state}(${"%.2f".format(it.score)})@[${"%.2f".format(it.box.left)},${"%.2f".format(it.box.top)},${"%.2f".format(it.box.right)},${"%.2f".format(it.box.bottom)}]"
                    }
                )
                detections
            } catch (e: Exception) {
                PerceptionFlightRecorder.record("YOLO", "INFERENCE_FAILED ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
        }
    }

    /** RGBA 프레임을 letterbox(최근접 샘플링)하여 NCHW RGB 0~1 입력 텐서로 채운다. */
    private fun fillLetterboxedInput(
        src: ByteBuffer,
        srcW: Int,
        srcH: Int,
        scale: Float,
        contentW: Int,
        contentH: Int,
        padX: Int,
        padY: Int
    ) {
        val plane = inputSize * inputSize
        val pad = 114f / 255f
        for (i in 0 until 3 * plane) inputFloats.put(i, pad)

        val invScale = 1f / scale
        for (dy in 0 until contentH) {
            val sy = min((dy * invScale).toInt(), srcH - 1)
            val srcRow = sy * srcW * 4
            val dstRow = (dy + padY) * inputSize + padX
            for (dx in 0 until contentW) {
                val sx = min((dx * invScale).toInt(), srcW - 1)
                val o = srcRow + sx * 4
                val d = dstRow + dx
                inputFloats.put(d, (src.get(o).toInt() and 0xFF) / 255f)
                inputFloats.put(plane + d, (src.get(o + 1).toInt() and 0xFF) / 255f)
                inputFloats.put(2 * plane + d, (src.get(o + 2).toInt() and 0xFF) / 255f)
            }
        }
    }

    private fun decode(frame: FrameRef, scale: Float, padX: Int, padY: Int): List<SignalObservation> {
        val rows = output[0]
        val numClasses = classStates.size

        // 좌표가 0~1 정규화인지 픽셀 단위인지 판별 (Ultralytics 버전에 따라 다름)
        var maxCoord = 0f
        for (i in 0 until numAnchors) maxCoord = max(maxCoord, rows[0][i])
        val coordScale = if (maxCoord <= 2f) inputSize.toFloat() else 1f

        val candidates = ArrayList<Candidate>()
        for (i in 0 until numAnchors) {
            var bestClass = 0
            var bestScore = rows[4][i]
            for (c in 1 until numClasses) {
                val s = rows[4 + c][i]
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (bestScore < confThreshold) continue

            val cx = rows[0][i] * coordScale
            val cy = rows[1][i] * coordScale
            val w = rows[2][i] * coordScale
            val h = rows[3][i] * coordScale
            // letterbox 역변환 후 원본 프레임 정규화 좌표로 복원
            val left = ((cx - w / 2f - padX) / scale / frame.width).coerceIn(0f, 1f)
            val right = ((cx + w / 2f - padX) / scale / frame.width).coerceIn(0f, 1f)
            val top = ((cy - h / 2f - padY) / scale / frame.height).coerceIn(0f, 1f)
            val bottom = ((cy + h / 2f - padY) / scale / frame.height).coerceIn(0f, 1f)
            if (right <= left || bottom <= top) continue

            candidates.add(Candidate(NormalizedBox(left, top, right, bottom), bestScore, bestClass))
        }

        // 클래스 무관 NMS: 같은 신호등에 적/녹 박스가 겹치면 점수가 높은 쪽만 남긴다
        candidates.sortByDescending { it.score }
        val kept = ArrayList<Candidate>()
        for (c in candidates) {
            if (kept.size >= maxDetections) break
            if (kept.none { iou(it.box, c.box) > iouThreshold }) kept.add(c)
        }

        return kept.mapIndexed { idx, c ->
            SignalObservation(
                ephemeralTrackId = "trk-yolo-${idx + 1}",
                state = classStates[c.classIndex],
                score = c.score,
                box = c.box,
                frameTimestampNanos = frame.timestampNanos,
                quality = FrameQuality(lighting = 0.85f, blur = 0.90f, isUsable = true),
                modelVersion = MODEL_VERSION
            )
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                interpreter.close()
            }
        }
    }

    private data class Candidate(val box: NormalizedBox, val score: Float, val classIndex: Int)

    companion object {
        const val MODEL_VERSION = "ped-signal-yolov8"

        private fun iou(a: NormalizedBox, b: NormalizedBox): Float {
            val iw = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
            val ih = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
            val inter = iw * ih
            val union = a.width * a.height + b.width * b.height - inter
            return if (union <= 0f) 0f else inter / union
        }

        /**
         * 모델 바이트에 내장된 Ultralytics metadata의 "names"를 클래스 인덱스 순서의 신호 상태로 변환한다.
         * 이름을 찾지 못하거나 녹색/적색 클래스를 식별할 수 없으면 null (색상 매핑을 추측하지 않는다).
         */
        fun parseClassStates(modelBytes: ByteArray): List<ObservedSignalState>? {
            val text = extractMetadataJson(modelBytes) ?: String(modelBytes, Charsets.ISO_8859_1)
            val namesBlock = Regex("\"names\"\\s*:\\s*\\{([^}]*)\\}").find(text)?.groupValues?.get(1) ?: return null
            val entries = Regex("\"(\\d+)\"\\s*:\\s*\"([^\"]*)\"").findAll(namesBlock)
                .map { it.groupValues[1].toInt() to it.groupValues[2].lowercase() }
                .sortedBy { it.first }
                .toList()
            if (entries.isEmpty() || entries.mapIndexed { i, e -> e.first == i }.any { !it }) return null

            val states = entries.map { (_, name) ->
                when {
                    "green" in name || "walk" in name -> ObservedSignalState.GREEN
                    "red" in name || "stop" in name -> ObservedSignalState.RED
                    else -> ObservedSignalState.UNKNOWN
                }
            }
            if (ObservedSignalState.GREEN !in states || ObservedSignalState.RED !in states) return null
            return states
        }

        /**
         * Ultralytics TFLite export는 metadata.json을 모델 파일 끝에 ZIP 아카이브로 덧붙여 저장한다(압축됨).
         * ZIP 로컬 파일 헤더("PK\u0003\u0004")를 찾아 metadata.json 내용을 꺼낸다.
         */
        private fun extractMetadataJson(modelBytes: ByteArray): String? {
            val signature = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
            var offset = modelBytes.size - signature.size
            while (offset >= 0) {
                if (modelBytes[offset] == signature[0] && modelBytes[offset + 1] == signature[1] &&
                    modelBytes[offset + 2] == signature[2] && modelBytes[offset + 3] == signature[3]
                ) {
                    try {
                        java.util.zip.ZipInputStream(
                            java.io.ByteArrayInputStream(modelBytes, offset, modelBytes.size - offset)
                        ).use { zip ->
                            var entry = zip.nextEntry
                            while (entry != null) {
                                if (entry.name.endsWith("metadata.json")) {
                                    return zip.readBytes().toString(Charsets.UTF_8)
                                }
                                entry = zip.nextEntry
                            }
                        }
                    } catch (_: Exception) {
                        // 우연히 서명과 같은 바이트열 — 계속 앞쪽을 탐색
                    }
                }
                offset--
            }
            return null
        }

        /**
         * YOLO 형식([1,3,S,S] 입력, [1,4+C,N] 출력) 모델이면 검출기를 생성하고, 아니면 null을 반환한다.
         */
        fun createOrNull(
            modelBytes: ByteArray,
            numThreads: Int = 4,
            confThreshold: Float = 0.25f,
            iouThreshold: Float = 0.45f,
            maxDetections: Int = 10
        ): YoloPedestrianSignalDetector? {
            val classStates = parseClassStates(modelBytes)
            if (classStates == null) {
                PerceptionFlightRecorder.record("YOLO", "DISABLED: class names metadata not found")
                return null
            }

            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder()).apply {
                put(modelBytes)
                rewind()
            }
            val interpreter = try {
                Interpreter(modelBuffer, Interpreter.Options().setNumThreads(numThreads))
            } catch (e: Exception) {
                PerceptionFlightRecorder.record("YOLO", "DISABLED: interpreter init failed ${e.message}")
                return null
            }

            val inShape = interpreter.getInputTensor(0).shape()
            val outShape = interpreter.getOutputTensor(0).shape()
            val isYoloLayout = inShape.size == 4 && inShape[0] == 1 && inShape[1] == 3 && inShape[2] == inShape[3] &&
                    outShape.size == 3 && outShape[0] == 1 && outShape[1] == 4 + classStates.size
            if (!isYoloLayout) {
                PerceptionFlightRecorder.record(
                    "YOLO",
                    "DISABLED: unexpected tensor layout in=${inShape.contentToString()} out=${outShape.contentToString()}"
                )
                interpreter.close()
                return null
            }

            PerceptionFlightRecorder.record(
                "YOLO",
                "ENABLED in=${inShape.contentToString()} out=${outShape.contentToString()} classes=$classStates"
            )
            return YoloPedestrianSignalDetector(
                interpreter = interpreter,
                inputSize = inShape[2],
                numAnchors = outShape[2],
                classStates = classStates,
                confThreshold = confThreshold,
                iouThreshold = iouThreshold,
                maxDetections = maxDetections
            )
        }
    }
}
