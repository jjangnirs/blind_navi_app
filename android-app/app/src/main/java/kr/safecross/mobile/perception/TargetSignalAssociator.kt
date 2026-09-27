package kr.safecross.mobile.perception

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 횡단보도 문맥, 기기 자세, 인식된 횡단보도 형상 및 신호 박스를 결합하여
 * 목표 보행신호기를 1:1로 확정하는 연결기 인터페이스 (SR-F-053, SR-F-054, TRD 4.6).
 */
interface TargetSignalAssociator {
    fun associate(
        crossing: VerifiedCrossingContext?,
        devicePose: DevicePose,
        crosswalk: CrosswalkObservation,
        signals: List<SignalObservation>
    ): TargetSignalAssociation
}

/**
 * 단일 타겟 Lock-on 및 Tracking 연결기.
 *
 * 프레임마다 여러 신호등이 검출되어도 최초 1회만 화면 중앙성/크기로 타겟을 고정(Lock-on)하고,
 * 이후에는 직전 프레임 타겟과의 위치 유사도(Stickiness)로 동일 물체를 추종한다.
 * 타겟이 일정 프레임 이상 완전히 사라지거나 궤적이 과도하게 벗어나면 Lock을 해제하고
 * 같은 프레임 내에서 즉시 재탐색(Initial Target Scoring)한다.
 *
 * 내부에 Lock 상태를 보관하는 stateful 클래스이므로, 하나의 카메라 파이프라인에 대해
 * 반드시 하나의 인스턴스를 재사용해야 한다. 프레임마다 새 인스턴스를 생성하면 추적이
 * 매번 초기화되어 이 클래스를 도입한 의미가 없어진다.
 */
class LockOnSignalAssociator(
    private val config: Config = Config()
) : TargetSignalAssociator {

    data class Config(
        // --- 1. Initial Target Scoring 가중치 (합이 1이 되도록 권장) ---
        val centerWeight: Float = 0.5f,
        val sizeWeight: Float = 0.5f,

        // --- 2. Tracking Stickiness ---
        // 직전 타겟과 위치 유사도가 이 값 이상이어야 "같은 신호등"으로 인정한다.
        val minTrackingSimilarity: Float = 0.15f,
        // 중심점 거리가 이 값(정규화 대각선 비율) 이상 벌어지면 유사도를 0으로 간주한다.
        val centroidToleranceNorm: Float = 0.35f,
        // 유사도 게이트를 통과했더라도 중심점 거리가 이 값을 넘으면 궤적 이탈로 간주한다.
        val maxCentroidJumpNorm: Float = 0.45f,

        // --- 3. Target Loss Recovery ---
        // 매칭 실패(또는 신호 없음/방향 불일치)가 연속으로 이 프레임 수에 도달하면 Lock 해제.
        val maxMissedFrames: Int = 5
    )

    private data class LockState(
        val trackId: String,
        var lastBox: NormalizedBox,
        var missedFrames: Int = 0
    )

    private var lock: LockState? = null

    override fun associate(
        crossing: VerifiedCrossingContext?,
        devicePose: DevicePose,
        crosswalk: CrosswalkObservation,
        signals: List<SignalObservation>
    ): TargetSignalAssociation {
        if (crossing == null) {
            registerMiss()
            return TargetSignalAssociation(false, null, "NO_CROSSING_CONTEXT", 0.0f)
        }

        if (signals.isEmpty()) {
            registerMiss()
            return TargetSignalAssociation(false, null, "NO_SIGNALS_DETECTED", 0.0f)
        }

        val cwDir = crosswalk.directionDegrees
        if (cwDir != null && abs(cwDir) > 45.0f) {
            registerMiss()
            return TargetSignalAssociation(false, null, "CROSSWALK_DIRECTION_MISMATCH", 0.25f)
        }

        val currentLock = lock

        // 5. 화면 측면 주변부 신호 배제 (cx < 0.28 또는 cx > 0.72는 전방 신호등이 아닌 인도변 간판/차량 신호)
        val candidateSignals = signals.filterNot { sig ->
            val cx = (sig.box.left + sig.box.right) / 2f
            sig.state == ObservedSignalState.GREEN && (cx < 0.28f || cx > 0.72f)
        }

        if (candidateSignals.isEmpty()) {
            registerMiss()
            return TargetSignalAssociation(false, null, "PERIPHERAL_SIGNAL_MISMATCH", 0.20f)
        }

        if (currentLock == null) {
            return lockOnNewTarget(candidateSignals, reason = "LOCK_ON_INITIAL_TARGET")
        }

        val best = candidateSignals
            .map { it to trackingSimilarity(currentLock.lastBox, it.box) }
            .maxByOrNull { it.second }

        val isAcceptableMatch = best != null &&
                best.second >= config.minTrackingSimilarity &&
                centroidDistance(currentLock.lastBox, best.first.box) <= config.maxCentroidJumpNorm

        if (!isAcceptableMatch) {
            currentLock.missedFrames += 1
            if (currentLock.missedFrames < config.maxMissedFrames) {
                // 손떨림/일시적 가림으로 판단 -> 다른 신호로 즉시 갈아타지 않고 유지 대기.
                return TargetSignalAssociation(false, null, "TARGET_TEMPORARILY_OCCLUDED", 0.0f)
            }
            // N프레임 이상 궤적 상실 -> Lock 해제 후 같은 프레임에서 즉시 재탐색.
            lock = null
            return lockOnNewTarget(candidateSignals, reason = "LOCK_RESET_AND_REACQUIRED")
        }

        val (target, similarity) = best!!
        currentLock.lastBox = target.box
        currentLock.missedFrames = 0

        val confidence = ((target.score + similarity) / 2f).coerceIn(0f, 1f)
        return TargetSignalAssociation(true, target, "LOCK_MAINTAINED_BY_TRACKING", confidence)
    }

    private fun lockOnNewTarget(
        signals: List<SignalObservation>,
        reason: String
    ): TargetSignalAssociation {
        val target = signals.maxByOrNull(::initialScore)!!
        lock = LockState(trackId = target.ephemeralTrackId, lastBox = target.box)
        return TargetSignalAssociation(true, target, reason, target.score.coerceIn(0f, 1f))
    }

    private fun registerMiss() {
        val currentLock = lock ?: return
        currentLock.missedFrames += 1
        if (currentLock.missedFrames >= config.maxMissedFrames) {
            lock = null
        }
    }

    /** Center Proximity + Size(Depth Proxy) 조합 점수. 값이 클수록 화면 중앙에 가깝고 큰 박스. */
    private fun initialScore(signal: SignalObservation): Float {
        val box = signal.box
        val dx = box.centerX - 0.5f
        val dy = box.centerY - 0.5f
        val centerDistance = sqrt(dx * dx + dy * dy)
        // 화면 중심 -> 모서리까지 최대 거리(sqrt(0.5^2+0.5^2)) 로 정규화
        val centerScore = 1f - (centerDistance / 0.70710678f).coerceIn(0f, 1f)
        val sizeScore = (box.width * box.height).coerceIn(0f, 1f)
        return config.centerWeight * centerScore + config.sizeWeight * sizeScore
    }

    private fun centroidDistance(a: NormalizedBox, b: NormalizedBox): Float {
        val dx = a.centerX - b.centerX
        val dy = a.centerY - b.centerY
        return sqrt(dx * dx + dy * dy)
    }

    private fun iou(a: NormalizedBox, b: NormalizedBox): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interArea = (interRight - interLeft).coerceAtLeast(0f) *
                (interBottom - interTop).coerceAtLeast(0f)
        val unionArea = a.width * a.height + b.width * b.height - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    /** 중심점 근접도와 IoU를 절반씩 반영한 0~1 유사도. 직전 타겟과 같은 물체일 가능성. */
    private fun trackingSimilarity(prevBox: NormalizedBox, candidateBox: NormalizedBox): Float {
        val centroidSimilarity = (1f - centroidDistance(prevBox, candidateBox) / config.centroidToleranceNorm)
            .coerceIn(0f, 1f)
        val overlapSimilarity = iou(prevBox, candidateBox)
        return (centroidSimilarity + overlapSimilarity) / 2f
    }
}