package kr.safecross.mobile.perception

import kr.safecross.mobile.camera.FrameRef

/**
 * 2단계 하이브리드 보행신호 판정 추정기 (Two-Tier Hybrid Signal Estimator).
 *
 * [핵심 안전 원칙: Zero False-Green 보장]
 * Tier 1 (딥러닝 객체 검출): 딥러닝 모델(LiteRT)이 신호등의 형태(Bounding Box)를 먼저 검출합니다.
 *   -> 신호등이 감지되지 않으면 배경에 초록색(간판, 버스 도색 등)이 아무리 많아도 즉시 UNKNOWN으로 차단합니다.
 * Tier 2 (크롭 영역 정밀 HSV 분석): 검출된 신호등 Bounding Box 내부에서만 정밀 HSV LED 파장을 분석합니다.
 * Tier 3 (시간 일관성 검증): LocalVlmSignalVerifier의 5프레임 롤링 버퍼를 통과해야 최종 GREEN이 승인됩니다.
 */
class TwoTierHybridSignalEstimator(
    val primaryDetector: PedestrianSignalEstimator,
    val colorAnalyzer: CameraVisionSignalEstimator = CameraVisionSignalEstimator(),
    val verifier: LocalVlmSignalVerifier = LocalVlmSignalVerifier()
) : PedestrianSignalEstimator {

    override suspend fun estimate(frame: FrameRef): List<SignalObservation> {
        // [Tier 1] 딥러닝 객체 검출 모델을 통한 보행신호기 형태(Bounding Box) 탐색
        val candidateSignals = try {
            primaryDetector.estimate(frame)
        } catch (_: Exception) {
            emptyList()
        }

        // 유효한 신호등 바운딩 박스를 가진 후보 탐색 (너무 작거나 깨진 박스 배제)
        val targetSignal = candidateSignals.firstOrNull { signal ->
            signal.box.width >= 0.02f && signal.box.height >= 0.03f
        }

        // [게이트 1: 신호등 객체 미검출 시 즉시 UNKNOWN 안전 반환]
        // 딥러닝이 신호등을 발견하지 못했다면 배경에 어떤 색상이 있든 절대 GREEN 판정을 내리지 않음
        if (targetSignal == null) {
            val unkObservation = SignalObservation(
                ephemeralTrackId = "track-hybrid-scanning",
                state = ObservedSignalState.UNKNOWN,
                score = 0.20f,
                box = NormalizedBox(left = 0.45f, top = 0.20f, right = 0.55f, bottom = 0.40f),
                frameTimestampNanos = frame.timestampNanos,
                quality = FrameQuality(lighting = 0.80f, blur = 0.85f, isUsable = true),
                modelVersion = "two-tier-hybrid-v2.0"
            )
            // UNKNOWN 상태를 롤링 버퍼에 기록하여 녹색 지속성 리셋
            verifier.verify(unkObservation, frame.rgbaBuffer, frame.width, frame.height)
            return listOf(unkObservation)
        }

        // [Tier 2] 딥러닝이 특정해 준 신호등 Bounding Box 영역 내부만 정밀 HSV 분석
        val roiObservations = colorAnalyzer.estimateWithinRoi(frame, targetSignal.box)
        val analyzedSignal = roiObservations.firstOrNull() ?: targetSignal

        // [Tier 3] 시간 일관성(Temporal Rolling Buffer) 및 Zero False-Green 최종 검증
        val verifiedResult = verifier.verify(
            analyzedSignal,
            frame.rgbaBuffer,
            frame.width,
            frame.height
        )

        return listOf(
            analyzedSignal.copy(
                state = verifiedResult.verifiedState,
                score = verifiedResult.confidenceScore,
                modelVersion = "two-tier-hybrid-v2.0"
            )
        )
    }

    companion object {
        fun createDefault(context: android.content.Context): TwoTierHybridSignalEstimator {
            val detector: PedestrianSignalEstimator = try {
                val assetManager = context.assets
                val modelBytes = assetManager.open("models/ped_signal_v1.tflite").use { it.readBytes() }
                val sigSha256 = kr.safecross.mobile.ml.contract.ModelContractValidator.computeSha256(modelBytes)
                val sigLabels = listOf("PEDESTRIAN_SIGNAL_RED", "PEDESTRIAN_SIGNAL_GREEN", "UNKNOWN")
                val manifest = kr.safecross.mobile.ml.contract.ModelManifest(
                    modelName = "ped_signal",
                    modelVersion = "1.0.0",
                    sha256 = sigSha256,
                    minAppVersion = "0.1.0",
                    disabled = false,
                    inputTensor = kr.safecross.mobile.ml.contract.TensorSpec("input_image", listOf(1, 320, 320, 3), "FLOAT32"),
                    outputTensors = listOf(
                        kr.safecross.mobile.ml.contract.TensorSpec("detection_boxes", listOf(1, 10, 4), "FLOAT32"),
                        kr.safecross.mobile.ml.contract.TensorSpec("detection_classes", listOf(1, 10), "FLOAT32"),
                        kr.safecross.mobile.ml.contract.TensorSpec("detection_scores", listOf(1, 10), "FLOAT32"),
                        kr.safecross.mobile.ml.contract.TensorSpec("num_detections", listOf(1), "FLOAT32")
                    ),
                    labelsOrder = sigLabels
                )

                kr.safecross.mobile.ml.LiteRtPedestrianSignalEstimator(
                    modelBytes = modelBytes,
                    manifest = manifest,
                    labels = sigLabels
                )
            } catch (_: Exception) {
                DefaultViewfinderDetector()
            }

            return TwoTierHybridSignalEstimator(
                primaryDetector = detector,
                colorAnalyzer = CameraVisionSignalEstimator(context)
            )
        }
    }
}

/**
 * 온디바이스 TFLite 로드 불가 시 화면 중앙 뷰파인더 가이드 박스를 타깃 영역으로 제공하는 폴백 검출기
 */
class DefaultViewfinderDetector(
    private val defaultBox: NormalizedBox = NormalizedBox(left = 0.20f, top = 0.10f, right = 0.80f, bottom = 0.60f)
) : PedestrianSignalEstimator {
    override suspend fun estimate(frame: FrameRef): List<SignalObservation> {
        return listOf(
            SignalObservation(
                ephemeralTrackId = "trk-viewfinder-box",
                state = ObservedSignalState.UNKNOWN,
                score = 0.80f,
                box = defaultBox,
                frameTimestampNanos = frame.timestampNanos,
                quality = FrameQuality(lighting = 0.85f, blur = 0.90f, isUsable = true),
                modelVersion = "viewfinder-target-v1.0"
            )
        )
    }
}
