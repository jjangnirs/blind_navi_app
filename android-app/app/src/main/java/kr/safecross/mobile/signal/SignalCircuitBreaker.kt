package kr.safecross.mobile.signal

/**
 * 실시간 신호 공급자 호출 보호를 위한 서킷 브레이커 (TRD 4.7).
 *
 * 연속 실패가 임계치(기본 3회)에 도달하면 OPEN 상태로 전이하여 쿨다운(30초) 동안
 * 외부 호출을 즉시 차단하고, 무제한 재시도 폭주를 방지합니다.
 */
class SignalCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val cooldownDurationNanos: Long = 30_000_000_000L // 30초
) {
    enum class State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private var state: State = State.CLOSED
    private var failureCount: Int = 0
    private var lastFailureTimeNanos: Long = 0L

    val currentState: State get() = state

    /**
     * 호출 가능 여부를 확인합니다.
     */
    fun canExecute(currentElapsedRealtimeNanos: Long): Boolean {
        return when (state) {
            State.CLOSED -> true
            State.OPEN -> {
                if (currentElapsedRealtimeNanos - lastFailureTimeNanos > cooldownDurationNanos) {
                    state = State.HALF_OPEN
                    true
                } else {
                    false
                }
            }
            State.HALF_OPEN -> true
        }
    }

    /**
     * 성공 시 호출
     */
    fun recordSuccess() {
        failureCount = 0
        state = State.CLOSED
    }

    /**
     * 실패 시 호출
     */
    fun recordFailure(currentElapsedRealtimeNanos: Long) {
        failureCount++
        lastFailureTimeNanos = currentElapsedRealtimeNanos
        if (failureCount >= failureThreshold || state == State.HALF_OPEN) {
            state = State.OPEN
        }
    }

    /**
     * 상태 초기화
     */
    fun reset() {
        state = State.CLOSED
        failureCount = 0
        lastFailureTimeNanos = 0L
    }
}
