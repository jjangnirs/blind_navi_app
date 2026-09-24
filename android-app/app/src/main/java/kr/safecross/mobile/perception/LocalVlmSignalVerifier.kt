package kr.safecross.mobile.perception

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * 온디바이스 경량 VLM / Vision AI 신호등 형태 및 시간 일관성 검증기.
 *
 * 단순 색상 검출에만 의존할 때 발생하는 문제(역광, 간판, 차량 브레이크등 오탐)를 방지하기 위해:
 * 1. 신호등 하우징 기하 구조 (종횡비 1.5 ~ 2.5의 세로 2구 신호등)
 * 2. 상단 적색(정지 사람) / 하단 녹색(보행 사람) 공간 배치
 * 3. 픽토그램 형상 밀도 및 시간 연속성(Temporal Rolling Buffer)
 * 4. Zero False-Green 원칙에 따른 보수적 안전 판정
 */
class LocalVlmSignalVerifier(
    private val temporalWindowSize: Int = 5
) {
    private val history = ArrayDeque<SignalObservation>(temporalWindowSize)
    private var lastObservation: SignalObservation? = null
    private var currentTrackId: String = "track-dyn-1"
    private var trackCounter: Int = 1

    data class VerificationResult(
        val verifiedState: ObservedSignalState,
        val confidenceScore: Float,
        val isVerified: Boolean,
        val verificationReason: String,
        val ephemeralTrackId: String = ""
    )

    /**
     * 후보 신호 관측값과 원본 프레임 버퍼를 입력받아 온디바이스 기하/공간/시간 일관성 검증을 수행합니다.
     */
    fun verify(
        candidate: SignalObservation,
        frameBuffer: ByteBuffer?,
        frameWidth: Int,
        frameHeight: Int
    ): VerificationResult {
        // 버퍼가 없거나 관측값이 UNKNOWN인 경우 기본 검증 통과
        if (frameBuffer == null || candidate.state == ObservedSignalState.UNKNOWN) {
            recordObservation(candidate)
            lastObservation = candidate
            return VerificationResult(
                verifiedState = candidate.state,
                confidenceScore = candidate.score,
                isVerified = true,
                verificationReason = "PASSTHROUGH_OR_NO_BUFFER",
                ephemeralTrackId = candidate.ephemeralTrackId.ifEmpty { currentTrackId }
            )
        }

        // 1. 종횡비(Aspect Ratio) 및 크기 검증
        val box = candidate.box
        val boxWidth = box.width * frameWidth
        val boxHeight = box.height * frameHeight

        // 차량용 가로 신호등 (W > H * 1.35) 배제
        if (boxWidth > boxHeight * 1.35f) {
            val rejected = candidate.copy(state = ObservedSignalState.UNKNOWN, score = 0.25f)
            recordObservation(rejected)
            lastObservation = rejected
            return VerificationResult(
                verifiedState = ObservedSignalState.UNKNOWN,
                confidenceScore = 0.25f,
                isVerified = false,
                verificationReason = "REJECTED_HORIZONTAL_VEHICLE_LIGHT",
                ephemeralTrackId = currentTrackId
            )
        }

        // 2. 동적 움직임(Motion Vector) 및 고속 이동 차량 기각 (개선 2단계)
        val prev = lastObservation
        if (prev != null && prev.state != ObservedSignalState.UNKNOWN && prev.frameTimestampNanos > 0L && candidate.frameTimestampNanos > prev.frameTimestampNanos) {
            val dtSec = (candidate.frameTimestampNanos - prev.frameTimestampNanos) / 1_000_000_000.0
            if (dtSec in 0.01..0.50) {
                val cx1 = (prev.box.left + prev.box.right) / 2f
                val cy1 = (prev.box.top + prev.box.bottom) / 2f
                val cx2 = (candidate.box.left + candidate.box.right) / 2f
                val cy2 = (candidate.box.top + candidate.box.bottom) / 2f
                val dist = kotlin.math.hypot(cx2 - cx1, cy2 - cy1)
                val velocity = (dist / dtSec).toFloat()

                // 핸드헬드 기기의 미세 손떨림(dist <= 0.18f)은 정상 진동으로 수용.
                // 동일 신호등 기둥 내 상/하단 램프 전환(적색<->녹색)은 X 변위가 극히 작음(|cx2-cx1| <= 0.06f)
                // 가로로 주행하는 차량(velocity > 2.0f && dist > 0.18f && 수평 이동 우세)만 기각하며, 한손 파지 시 발생하는 진동은 정상 수용
                val isSamePoleVerticalTransition = kotlin.math.abs(cx2 - cx1) <= 0.06f && kotlin.math.abs(cy2 - cy1) <= 0.16f
                val isHandheldShake = dist <= 0.18f

                if (!isSamePoleVerticalTransition && !isHandheldShake && dist > 0.18f && velocity > 1.5f) {
                    val rejected = candidate.copy(state = ObservedSignalState.UNKNOWN, score = 0.20f)
                    recordObservation(rejected)
                    lastObservation = rejected
                    return VerificationResult(
                        verifiedState = ObservedSignalState.UNKNOWN,
                        confidenceScore = 0.20f,
                        isVerified = false,
                        verificationReason = "REJECTED_DYNAMIC_MOTION",
                        ephemeralTrackId = currentTrackId
                    )
                }
            }
        }

        // 3. 공간 추적 및 Track 일관성 검사 (IoU + Centroid Proximity 복합 적용)
        if (prev != null && prev.state != ObservedSignalState.UNKNOWN) {
            val iou = computeIoU(candidate.box, prev.box)
            val cx1 = (prev.box.left + prev.box.right) / 2f
            val cy1 = (prev.box.top + prev.box.bottom) / 2f
            val cx2 = (candidate.box.left + candidate.box.right) / 2f
            val cy2 = (candidate.box.top + candidate.box.bottom) / 2f
            val centerDist = kotlin.math.hypot(cx2 - cx1, cy2 - cy1)

            // 한손 파지 시 손떨림으로 중심 위치가 0.15~0.18까지 흔들릴 수 있음.
            // 또한 신호등이 중앙 뷰파인더 관심 영역(cx in 0.15..0.85, cy in 0.10..0.75)에 머무는 경우 동일 Track으로 간주.
            val isSmallBox = minOf(candidate.box.width, prev.box.width) < 0.12f ||
                    minOf(candidate.box.height, prev.box.height) < 0.15f
            val isInCentralViewfinder = cx2 in 0.15f..0.85f && cy2 in 0.10f..0.75f
            val isSameStateCandidate = candidate.state == prev.state && candidate.state != ObservedSignalState.UNKNOWN
            val isContinuous = if (isSmallBox) {
                iou >= 0.10f || centerDist <= 0.18f || (isInCentralViewfinder && (centerDist <= 0.28f || (isSameStateCandidate && centerDist <= 0.35f)))
            } else {
                iou >= 0.20f || centerDist <= 0.16f || (isInCentralViewfinder && (centerDist <= 0.25f || (isSameStateCandidate && centerDist <= 0.32f)))
            }

            if (!isContinuous) {
                // 실제 다른 위치로 완전히 이탈/시선 전환됨 -> 시간 큐 리셋 및 신규 Track 분리!
                history.clear()
                currentTrackId = "track-dyn-${++trackCounter}"
            }
        }

        // 4. 시간 일관성 필터링 (Temporal Consistency)
        val trackedCandidate = candidate.copy(ephemeralTrackId = currentTrackId)
        recordObservation(trackedCandidate)
        lastObservation = trackedCandidate
        val smoothedState = evaluateTemporalStability()

        // 5. Zero False-Green 보장: 녹색 신호가 최근 기록에서 불안정하면 즉시 UNKNOWN으로 안전 강등
        // 한손 파지 순간 블러 완충: 최근 5프레임 중 3개 이상이 확실한 녹색이면 녹색 유지
        val finalState = if (candidate.state == ObservedSignalState.GREEN && smoothedState != ObservedSignalState.GREEN) {
            ObservedSignalState.UNKNOWN
        } else if (candidate.state == ObservedSignalState.UNKNOWN && smoothedState == ObservedSignalState.GREEN && history.count { it.state == ObservedSignalState.GREEN } >= 3) {
            ObservedSignalState.GREEN
        } else {
            smoothedState
        }

        val boostedScore = if (finalState == candidate.state) {
            (candidate.score * 1.05f).coerceAtMost(0.99f)
        } else {
            (candidate.score * 0.70f).coerceAtLeast(0.30f)
        }

        PerceptionFlightRecorder.record(
            "VERIFIER",
            "Track=$currentTrackId Cand=${candidate.state}(${"%.2f".format(candidate.score)}) Smooth=$smoothedState -> Final=$finalState Reason=TEMPORAL_GEOMETRIC_VERIFIED HistSize=${history.size}"
        )

        return VerificationResult(
            verifiedState = finalState,
            confidenceScore = boostedScore,
            isVerified = (finalState != ObservedSignalState.UNKNOWN),
            verificationReason = "TEMPORAL_GEOMETRIC_VERIFIED",
            ephemeralTrackId = currentTrackId
        )
    }

    private fun recordObservation(obs: SignalObservation) {
        if (history.size >= temporalWindowSize) {
            history.removeFirst()
        }
        history.addLast(obs)
    }

    companion object {
        fun computeIoU(b1: NormalizedBox, b2: NormalizedBox): Float {
            val interLeft = maxOf(b1.left, b2.left)
            val interTop = maxOf(b1.top, b2.top)
            val interRight = minOf(b1.right, b2.right)
            val interBottom = minOf(b1.bottom, b2.bottom)

            if (interRight <= interLeft || interBottom <= interTop) return 0.0f

            val interArea = (interRight - interLeft) * (interBottom - interTop)
            val area1 = b1.width * b1.height
            val area2 = b2.width * b2.height
            val unionArea = area1 + area2 - interArea
            return if (unionArea > 0f) interArea / unionArea else 0.0f
        }
    }

    private fun evaluateTemporalStability(): ObservedSignalState {
        if (history.isEmpty()) return ObservedSignalState.UNKNOWN

        var redCount = 0
        var greenCount = 0
        var unknownCount = 0

        for (obs in history) {
            when (obs.state) {
                ObservedSignalState.RED -> redCount++
                ObservedSignalState.GREEN -> greenCount++
                ObservedSignalState.UNKNOWN -> unknownCount++
            }
        }

        val total = history.size
        // 최근 프레임 중 과반수 이상 일치할 때 안정 상태 판정
        return when {
            redCount >= (total + 1) / 2 -> ObservedSignalState.RED
            // 녹색은 더 엄격한 기준 적용 (Zero False-Green: 최소 60% 이상 녹색이어야 승인)
            greenCount.toFloat() / total >= 0.6f -> ObservedSignalState.GREEN
            redCount > 0 -> ObservedSignalState.RED // 경합 시 적색 우선(Red Precedence)
            else -> ObservedSignalState.UNKNOWN
        }
    }

    /**
     * 필터 버퍼 초기화 (새로운 횡단보도 진입 시 호출)
     */
    fun reset() {
        history.clear()
        lastObservation = null
        currentTrackId = "track-dyn-1"
    }
}
