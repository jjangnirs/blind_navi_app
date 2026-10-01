package kr.safecross.mobile.navigation.crossing

/**
 * 경로 위 횡단보도 지점 (경로 시작점부터의 경로상 거리).
 */
data class RouteCrosswalk(
    val maneuverIndex: Int,
    val alongRouteMeters: Double,
    val instruction: String
)

/**
 * 자동 전환 판정 결과.
 */
sealed interface CrossingAutoTriggerDecision {
    /** 카메라 신호 확인 화면으로 자동 전환 */
    data class Trigger(
        val crosswalk: RouteCrosswalk,
        val remainingMeters: Double,
        val reason: String
    ) : CrossingAutoTriggerDecision

    /** GPS 정확도가 낮아 자동 전환 대신 수동 전환을 음성으로 권유 */
    data class SuggestManual(
        val crosswalk: RouteCrosswalk,
        val remainingMeters: Double,
        val accuracyMeters: Float
    ) : CrossingAutoTriggerDecision
}

/**
 * 횡단보도 접근 시 카메라 신호 확인 화면 자동 전환 정책.
 *
 * - 경로상 남은 거리(직선거리가 아님) 15m 이내 진입 시 횡단보도당 1회 전환
 *   (전환·카메라 기동·휴대폰 들기·신호 확정까지 약 5~8초 ≈ 보행 6~10m, GPS 오차 감안)
 * - 30m 이내에서 2초 이상 멈춰 서면 즉시 전환 (GPS가 늦어도 연석에 선 사용자를 놓치지 않음)
 * - GPS 정확도가 25m보다 나쁘면 거리 판정을 믿을 수 없으므로 자동 전환하지 않고 수동 전환을 1회 권유
 * - 이미 지나친 횡단보도(5m 초과 통과)와 경로 이탈 중에는 동작하지 않음
 */
class CrossingAutoTriggerPolicy(
    private val crosswalks: List<RouteCrosswalk>,
    private val triggerDistanceMeters: Double = 15.0,
    private val stopTriggerDistanceMeters: Double = 30.0,
    private val stopSpeedMps: Float = 0.3f,
    private val stopDurationMs: Long = 2_000L,
    private val maxAccuracyMeters: Float = 25.0f,
    private val passedToleranceMeters: Double = 5.0
) {
    private val handled = mutableSetOf<Int>()
    private val suggested = mutableSetOf<Int>()
    private var stoppedSinceMs: Long? = null

    fun evaluate(
        userAlongRouteMeters: Double,
        speedMps: Float?,
        accuracyMeters: Float,
        isOffRoute: Boolean,
        nowMs: Long
    ): CrossingAutoTriggerDecision? {
        // 정지 지속 시간 추적 (속도 미제공 시 정지로 보지 않음)
        stoppedSinceMs = if (speedMps != null && speedMps < stopSpeedMps) (stoppedSinceMs ?: nowMs) else null

        if (isOffRoute) return null

        // 아직 처리하지 않았고 지나치지 않은 가장 가까운 전방 횡단보도
        val next = crosswalks
            .filter { it.maneuverIndex !in handled && it.alongRouteMeters - userAlongRouteMeters >= -passedToleranceMeters }
            .minByOrNull { it.alongRouteMeters }
            ?: return null
        val remaining = next.alongRouteMeters - userAlongRouteMeters

        val isWithinTriggerDistance = remaining <= triggerDistanceMeters
        val stoppedLongEnough = stoppedSinceMs?.let { nowMs - it >= stopDurationMs } == true
        val isStoppedNearCrosswalk = remaining <= stopTriggerDistanceMeters && stoppedLongEnough
        if (!isWithinTriggerDistance && !isStoppedNearCrosswalk) return null

        if (accuracyMeters > maxAccuracyMeters) {
            // 권유는 1회만, 이후 GPS가 좋아지면 같은 횡단보도도 자동 전환될 수 있도록 handled에는 넣지 않음
            if (suggested.add(next.maneuverIndex)) {
                return CrossingAutoTriggerDecision.SuggestManual(next, remaining, accuracyMeters)
            }
            return null
        }
        handled.add(next.maneuverIndex)
        return CrossingAutoTriggerDecision.Trigger(
            crosswalk = next,
            remainingMeters = remaining,
            reason = if (isWithinTriggerDistance) "DISTANCE_${triggerDistanceMeters.toInt()}M" else "STOPPED_NEAR_CROSSWALK"
        )
    }

    fun reset() {
        handled.clear()
        suggested.clear()
        stoppedSinceMs = null
    }
}
