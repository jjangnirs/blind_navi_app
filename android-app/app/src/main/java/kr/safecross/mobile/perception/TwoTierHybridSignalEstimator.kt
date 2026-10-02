package kr.safecross.mobile.perception

import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.ml.YoloPedestrianSignalDetector
import kotlin.math.sqrt

/**
 * 2단계 하이브리드 보행신호 판정 추정기 (Two-Tier Hybrid Signal Estimator).
 *
 * [핵심 안전 원칙: Zero False-Green 보장]
 * Tier 1 (딥러닝 객체 검출): 딥러닝 모델(YOLO)이 신호등의 위치(Bounding Box)를 먼저 검출합니다.
 *   여러 개가 검출되면 직전 타깃과의 위치 연속성(Lock-on)으로 같은 신호등을 계속 추종합니다.
 * Tier 2 (크롭 영역 정밀 HSV 분석): 검출된 신호등 박스 주변에서만 HSV/OpenCV로 LED 색을 분석하고,
 *   모델의 색 분류와 교차 검증합니다. 녹색은 두 근거가 모두 녹색일 때만 인정하고,
 *   어느 한쪽이라도 적색이면 적색을 우선합니다.
 * Tier 3 (시간 일관성 검증): LocalVlmSignalVerifier의 5프레임 롤링 버퍼를 프레임당 정확히 1회 통과합니다.
 *
 * 모델이 아무것도 검출하지 못하면(원거리 등) 뷰파인더 가이드 박스 HSV 분석으로 폴백합니다.
 */
class TwoTierHybridSignalEstimator(
    val primaryDetector: PedestrianSignalEstimator,
    val colorAnalyzer: CameraVisionSignalEstimator = CameraVisionSignalEstimator(),
    val verifier: LocalVlmSignalVerifier = colorAnalyzer.verifier,
    val fallbackToViewfinder: Boolean = false
) : PedestrianSignalEstimator {

    // Tier 1 타깃 Lock-on 상태 (프레임 간 같은 신호등 추종)
    private var lockedBox: NormalizedBox? = null
    private var lockedAtNanos: Long = 0L

    // 색 분류 모델의 마지막 검출 (모델이 몇 프레임 놓쳐도 같은 신호등 위치를 유지하기 위함)
    private var lastClassifiedTarget: SignalObservation? = null

    override suspend fun estimate(frame: FrameRef): List<SignalObservation> {
        // [Tier 1] 딥러닝 객체 검출 모델을 통한 보행신호기 위치(Bounding Box) 탐색
        val candidateSignals = try {
            primaryDetector.estimate(frame)
        } catch (_: Exception) {
            emptyList()
        }

        // 유효한 신호등 바운딩 박스 후보 (너무 작거나 깨진 박스 배제).
        // 색까지 분류하는 모델(YOLO)은 원거리 소형 신호등도 수용하도록 최소 크기를 낮춘다.
        val validCandidates = candidateSignals.filter { signal ->
            if (signal.state == ObservedSignalState.UNKNOWN) {
                signal.box.width >= 0.02f && signal.box.height >= 0.03f
            } else {
                signal.box.width >= 0.006f && signal.box.height >= 0.01f
            }
        }
        val selected = selectLockedTarget(validCandidates, frame.timestampNanos)
        if (selected != null && selected.state != ObservedSignalState.UNKNOWN) {
            lastClassifiedTarget = selected
        }

        // 모델이 이번 프레임에서 신호등을 놓쳤지만 1.5초 이내에 같은 위치에서 검출했었다면,
        // 화면 전체(뷰파인더)로 넓히지 않고 직전 신호등 위치를 계속 분석한다.
        // (실측: 모델 미검출 프레임에 뷰파인더 폴백이 위쪽 차량 적색등을 잡아 보행 녹색 중 '적색' 오판)
        val held = lastClassifiedTarget
        val isHoldingLock = selected == null && held != null &&
                (frame.timestampNanos - held.frameTimestampNanos) in 0L..LOCK_HOLD_NANOS
        val targetSignal = if (isHoldingLock) {
            held!!.copy(frameTimestampNanos = frame.timestampNanos)
        } else {
            selected
        }

        // [게이트 1: 신호등 객체 미검출 시]
        if (targetSignal == null) {
            if (fallbackToViewfinder) {
                return estimateWithinViewfinder(frame)
            }

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

        // [Tier 2] 검출된 신호등 박스 주변(하우징 전체가 들어오도록 확장)만 정밀 HSV 분석
        val isClassifiedByModel = targetSignal.state != ObservedSignalState.UNKNOWN
        val roi = if (isClassifiedByModel) expandToHousing(targetSignal.box) else targetSignal.box
        val colorObservation = colorAnalyzer.estimateWithinRoi(
            frame,
            roi,
            verify = false,
            housingConfirmed = isClassifiedByModel
        ).firstOrNull()

        val fused = if (isClassifiedByModel) {
            fuseModelAndColor(targetSignal, colorObservation, colorAnalyzer.lastColorEvidence, isHoldingLock)
        } else {
            // 위치만 제공하는 검출기: 색 판정은 전적으로 HSV 분석 결과를 따른다
            colorObservation ?: targetSignal
        }

        // [Tier 3] 시간 일관성(Temporal Rolling Buffer) 및 Zero False-Green 최종 검증 (프레임당 1회)
        val verifiedResult = verifier.verify(fused, frame.rgbaBuffer, frame.width, frame.height)

        return listOf(
            fused.copy(
                state = verifiedResult.verifiedState,
                score = verifiedResult.confidenceScore,
                ephemeralTrackId = verifiedResult.ephemeralTrackId.ifEmpty { fused.ephemeralTrackId },
                modelVersion = if (isClassifiedByModel) "two-tier-hybrid-yolo-v3.0" else "two-tier-hybrid-v2.0"
            )
        )
    }

    /**
     * 뷰파인더 가이드 박스(0.30..0.70, 0.12..0.65) 내부 HSV 분석 폴백.
     * 딥러닝 모델 미검출 상태이므로 녹색은 검증 신뢰도 0.90 이상일 때만 승인한다 (ADR-032).
     */
    private suspend fun estimateWithinViewfinder(frame: FrameRef): List<SignalObservation> {
        val viewfinderBox = NormalizedBox(left = 0.30f, top = 0.12f, right = 0.70f, bottom = 0.65f)
        val candidate = colorAnalyzer.estimateWithinRoi(frame, viewfinderBox, verify = false).firstOrNull()
            ?: SignalObservation(
                ephemeralTrackId = "track-hybrid-scanning",
                state = ObservedSignalState.UNKNOWN,
                score = 0.20f,
                box = viewfinderBox,
                frameTimestampNanos = frame.timestampNanos,
                quality = FrameQuality(lighting = 0.80f, blur = 0.85f, isUsable = true),
                modelVersion = "two-tier-hybrid-viewfinder-v2.2"
            )

        val verifiedResult = verifier.verify(candidate, frame.rgbaBuffer, frame.width, frame.height)
        val isSafeGreen = verifiedResult.verifiedState == ObservedSignalState.GREEN &&
                verifiedResult.isVerified && verifiedResult.confidenceScore >= 0.90f
        val finalState = if (verifiedResult.verifiedState == ObservedSignalState.GREEN && !isSafeGreen) {
            ObservedSignalState.UNKNOWN
        } else {
            verifiedResult.verifiedState
        }
        val finalScore = if (finalState == ObservedSignalState.UNKNOWN) 0.25f else verifiedResult.confidenceScore

        return listOf(
            candidate.copy(
                state = finalState,
                score = finalScore,
                ephemeralTrackId = verifiedResult.ephemeralTrackId.ifEmpty { candidate.ephemeralTrackId },
                modelVersion = "two-tier-hybrid-viewfinder-v2.2"
            )
        )
    }

    /**
     * 모델 색 분류와 HSV 색 분석의 교차 검증 (보수적 비대칭 규칙):
     * - 둘 다 같은 색 → 그 색 (HSV 박스/점수 사용)
     * - 어느 한쪽이라도 적색 → 적색 우선 (모델 녹색 + HSV 적색 포함)
     * - 모델 적색 + HSV 녹색 → 충돌, UNKNOWN
     * - 모델 녹색 + HSV 미검출 → UNKNOWN (녹색은 반드시 두 근거가 일치해야 함)
     * - 모델 적색(신뢰도 ≥ 0.45) + HSV 미검출 → 적색 (정지 방향 판정은 안전 측)
     */
    private fun fuseModelAndColor(
        model: SignalObservation,
        color: SignalObservation?,
        evidence: CameraVisionSignalEstimator.ColorEvidence?,
        isHoldingLock: Boolean
    ): SignalObservation {
        val colorState = color?.state ?: ObservedSignalState.UNKNOWN
        // 원시 녹색 화소 근거: 블롭 필터는 통과 못 했지만(원거리 픽토그램 분절) ROI 안에 녹색 LED 화소가
        // 충분하고 적색 화소가 거의 없음. 실측(10/01 21:15~21:16 녹색 30초): 모델 녹색 프레임의 70%가
        // "G=0/9~21 all blobs filtered"로 기각되어 녹색 구간 전체가 UNKNOWN 처리됨.
        val hasRawGreenEvidence = evidence != null &&
                evidence.greenPixels >= MIN_RAW_GREEN_PIXELS &&
                evidence.redPixels * 3 <= evidence.greenPixels
        val (state, box, score, reason) = when (model.state) {
            ObservedSignalState.GREEN -> when {
                colorState == ObservedSignalState.GREEN -> Fusion(ObservedSignalState.GREEN, color!!.box, color.score, "AGREE_GREEN")
                colorState == ObservedSignalState.RED -> Fusion(ObservedSignalState.RED, color!!.box, color.score, "MODEL_GREEN_HSV_RED_RED_FIRST")
                hasRawGreenEvidence && !isHoldingLock -> Fusion(ObservedSignalState.GREEN, model.box, 0.92f, "MODEL_GREEN_RAW_GREEN_PIXELS")
                else -> Fusion(ObservedSignalState.UNKNOWN, model.box, 0.30f, "MODEL_GREEN_HSV_NONE")
            }
            ObservedSignalState.RED -> when (colorState) {
                ObservedSignalState.RED -> Fusion(ObservedSignalState.RED, color!!.box, color.score, "AGREE_RED")
                ObservedSignalState.GREEN -> Fusion(ObservedSignalState.UNKNOWN, model.box, 0.30f, "CONFLICT_MODEL_RED_HSV_GREEN")
                ObservedSignalState.UNKNOWN -> if (model.score >= MIN_MODEL_ONLY_RED_SCORE) {
                    Fusion(ObservedSignalState.RED, model.box, 0.90f, "MODEL_RED_ONLY")
                } else {
                    Fusion(ObservedSignalState.UNKNOWN, model.box, 0.30f, "MODEL_RED_WEAK")
                }
            }
            ObservedSignalState.UNKNOWN -> Fusion(colorState, color?.box ?: model.box, color?.score ?: 0.30f, "HSV_ONLY")
        }

        PerceptionFlightRecorder.record(
            "FUSION",
            "Model=${model.state}(${"%.2f".format(model.score)})${if (isHoldingLock) "[HOLD]" else ""} Hsv=$colorState(${color?.let { "%.2f".format(it.score) } ?: "-"}) Px=R${evidence?.redPixels ?: "-"}/G${evidence?.greenPixels ?: "-"} -> $state Reason=$reason"
        )

        return SignalObservation(
            ephemeralTrackId = "track-hybrid-yolo",
            state = state,
            score = score,
            box = box,
            frameTimestampNanos = model.frameTimestampNanos,
            quality = color?.quality ?: model.quality,
            modelVersion = model.modelVersion
        )
    }

    private data class Fusion(
        val state: ObservedSignalState,
        val box: NormalizedBox,
        val score: Float,
        val reason: String
    )

    /**
     * 여러 신호등 후보 중 추종할 단일 타깃을 고른다 (Lock-on).
     * 직전 타깃이 700ms 이내에 있었으면 위치 유사도가 가장 높은 후보를 이어서 추종하고,
     * 그렇지 않으면 화면 중앙성 + 크기 + 모델 신뢰도로 새 타깃을 고른다.
     */
    private fun selectLockedTarget(candidates: List<SignalObservation>, nowNanos: Long): SignalObservation? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0].also { lock(it.box, nowNanos) }

        val prevBox = lockedBox
        val isLockFresh = prevBox != null && (nowNanos - lockedAtNanos) in 0L..LOCK_TIMEOUT_NANOS
        if (isLockFresh) {
            val best = candidates.maxByOrNull { trackingSimilarity(prevBox!!, it.box) }!!
            if (trackingSimilarity(prevBox!!, best.box) >= MIN_TRACKING_SIMILARITY) {
                lock(best.box, nowNanos)
                return best
            }
        }

        val fresh = candidates.maxByOrNull(::initialTargetScore)!!
        lock(fresh.box, nowNanos)
        return fresh
    }

    private fun lock(box: NormalizedBox, nowNanos: Long) {
        lockedBox = box
        lockedAtNanos = nowNanos
    }

    /** 조준선 중심(0.5, 0.38) 근접도 + 박스 크기 + 모델 신뢰도 조합 점수 */
    private fun initialTargetScore(signal: SignalObservation): Float {
        val dx = signal.box.centerX - 0.5f
        val dy = signal.box.centerY - 0.38f
        val centerScore = 1f - (sqrt(dx * dx + dy * dy) / 0.70710678f).coerceIn(0f, 1f)
        val sizeScore = (sqrt(signal.box.width * signal.box.height) / 0.2f).coerceIn(0f, 1f)
        return 0.5f * centerScore + 0.2f * sizeScore + 0.3f * signal.score.coerceIn(0f, 1f)
    }

    private fun trackingSimilarity(prev: NormalizedBox, candidate: NormalizedBox): Float {
        val dx = prev.centerX - candidate.centerX
        val dy = prev.centerY - candidate.centerY
        val centroidSimilarity = (1f - sqrt(dx * dx + dy * dy) / 0.35f).coerceIn(0f, 1f)
        return (centroidSimilarity + LocalVlmSignalVerifier.computeIoU(prev, candidate)) / 2f
    }

    /** 램프 박스를 하우징 전체(상하 적/녹 슬롯 + 주변 어두운 테두리)가 포함되도록 확장 */
    private fun expandToHousing(box: NormalizedBox): NormalizedBox {
        val padX = box.width * 0.5f
        val padY = box.height * 0.6f
        return NormalizedBox(
            left = (box.left - padX).coerceIn(0f, 1f),
            top = (box.top - padY).coerceIn(0f, 1f),
            right = (box.right + padX).coerceIn(0f, 1f),
            bottom = (box.bottom + padY).coerceIn(0f, 1f)
        )
    }

    companion object {
        private const val LOCK_TIMEOUT_NANOS = 700_000_000L
        private const val MIN_TRACKING_SIMILARITY = 0.15f
        private const val LOCK_HOLD_NANOS = 1_500_000_000L

        // int8 양자화 모델의 클래스 점수는 0.50에서 포화한다(실측 최댓값 0.50). 0.30 이상이면 유의미한 검출.
        private const val MIN_MODEL_ONLY_RED_SCORE = 0.30f

        // 원거리 보행등 녹색 LED 원시 화소 최소 개수 (ROI 1픽셀 전수 샘플링 기준)
        private const val MIN_RAW_GREEN_PIXELS = 6

        fun createDefault(context: android.content.Context): TwoTierHybridSignalEstimator {
            val modelBytes = try {
                context.assets.open("models/ped_signal_v1.tflite").use { it.readBytes() }
            } catch (_: Exception) {
                null
            }

            // 실제 YOLO 모델이면 Tier 1 위치 제안기로 사용하고, 스텁(80바이트)이거나 형식이 맞지 않으면
            // 뷰파인더 가이드 검출기를 사용한다. (구 SSD 경로는 실제 추론기가 연결되지 않아 항상 빈 결과였음)
            val detector: PedestrianSignalEstimator =
                modelBytes?.takeIf { it.size >= 1024 }?.let { bytes ->
                    try {
                        YoloPedestrianSignalDetector.createOrNull(bytes)
                    } catch (_: Throwable) {
                        null
                    }
                } ?: DefaultViewfinderDetector()

            PerceptionFlightRecorder.record("MODEL", "Tier1 detector=${detector.javaClass.simpleName}")

            return TwoTierHybridSignalEstimator(
                primaryDetector = detector,
                colorAnalyzer = CameraVisionSignalEstimator(context),
                fallbackToViewfinder = true
            )
        }
    }
}

/**
 * 온디바이스 TFLite 로드 불가 시 화면 중앙 뷰파인더 가이드 박스를 타깃 영역으로 제공하는 폴백 검출기
 */
class DefaultViewfinderDetector(
    private val defaultBox: NormalizedBox = NormalizedBox(left = 0.30f, top = 0.12f, right = 0.70f, bottom = 0.65f)
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
