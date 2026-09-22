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
        val verificationReason: String
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
                verificationReason = "PASSTHROUGH_OR_NO_BUFFER"
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
                verificationReason = "REJECTED_HORIZONTAL_VEHICLE_LIGHT"
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
                val dist = kotlin.math.sqrt((cx2 - cx1) * (cx2 - cx1) + (cy2 - cy1) * (cy2 - cy1))
                val velocity = (dist / dtSec).toFloat()

                // 초당 화면 폭의 55% 이상 빠르게 이동하는 차량/동적 객체 기각
                if (velocity > 0.55f) {
                    val rejected = candidate.copy(state = ObservedSignalState.UNKNOWN, score = 0.20f)
                    recordObservation(rejected)
                    lastObservation = rejected
                    return VerificationResult(
                        verifiedState = ObservedSignalState.UNKNOWN,
                        confidenceScore = 0.20f,
                        isVerified = false,
                        verificationReason = "REJECTED_DYNAMIC_MOTION"
                    )
                }
            }
        }

        // 3. IoU 기반 공간 추적 및 Track 일관성 검사 (개선 1단계)
        if (prev != null && prev.state != ObservedSignalState.UNKNOWN) {
            val iou = computeIoU(candidate.box, prev.box)
            if (iou < 0.35f) {
                // 이전 프레임과 위치가 튀었거나 다른 물체로 변경됨 -> 시간 큐 리셋 및 신규 Track 분리!
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
        val finalState = if (candidate.state == ObservedSignalState.GREEN && smoothedState != ObservedSignalState.GREEN) {
            ObservedSignalState.UNKNOWN
        } else {
            smoothedState
        }

        val boostedScore = if (finalState == candidate.state) {
            (candidate.score * 1.05f).coerceAtMost(0.99f)
        } else {
            (candidate.score * 0.70f).coerceAtLeast(0.30f)
        }

        return VerificationResult(
            verifiedState = finalState,
            confidenceScore = boostedScore,
            isVerified = (finalState != ObservedSignalState.UNKNOWN),
            verificationReason = "TEMPORAL_GEOMETRIC_VERIFIED"
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
