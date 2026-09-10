package kr.safecross.mobile.signal.model

import kr.safecross.mobile.decision.model.OfficialSignalState

/**
 * 공식 실시간 보행신호 정규화 결과 (PR-F-016, SR-F-056, TRD 4.7).
 */
data class NormalizedSignalStatus(
    val provider: String,
    val providerIntersectionId: String,
    val movementId: String,
    val state: OfficialSignalState,
    val sourceTimestampEpochMs: Long,
    val receivedElapsedRealtimeNanos: Long,
    val optionalRemainingSeconds: Int? = null,
    val qualityFlags: List<String> = emptyList()
)

/**
 * 신호 상태(RED/GREEN)와 엄격히 분리된 에러 및 비정상 사유 (SR-F-057).
 */
enum class SignalErrorReason(val code: String, val description: String) {
    UNSUPPORTED_REGION("UNSUPPORTED_REGION", "공식 실시간 신호 미지원 지역입니다."),
    UNAUTHORIZED("UNAUTHORIZED", "공식 신호 제공자 인증 실패 또는 계약이 만료되었습니다."),
    UNMAPPED_MOVEMENT("UNMAPPED_MOVEMENT", "현장 검증된 crossing link에 매핑되지 않은 방향입니다."),
    STALE("STALE", "신호 데이터가 최대 허용 나이를 초과하여 만료되었습니다."),
    CLOCK_SKEW("CLOCK_SKEW", "신호 시계와 기기 시계 간의 비정상 왜곡 또는 역행이 감지되었습니다."),
    TIMEOUT("TIMEOUT", "공식 신호 응답 시간이 초과되었습니다."),
    MAINTENANCE("MAINTENANCE", "신호제어기 또는 관제 센터가 점검 중입니다."),
    MALFORMED("MALFORMED", "공식 신호 데이터 형식이 올바르지 않습니다."),
    CIRCUIT_OPEN("CIRCUIT_OPEN", "연속 실패로 인해 서킷 브레이커가 활성화되었습니다."),
    KILLED("KILLED", "원격 또는 로컬 킬스위치에 의해 실시간 신호가 비활성화되었습니다.")
}

/**
 * 신호 조회 결과 봉투 (Result Envelope)
 */
sealed class SignalFetchResult {
    data class Success(val status: NormalizedSignalStatus) : SignalFetchResult()
    data class Failure(val reason: SignalErrorReason, val message: String) : SignalFetchResult()

    val isSuccess: Boolean get() = this is Success
}

/**
 * 공급자별 신선도 및 허용 오차 설정 (버전 관리)
 */
data class ProviderConfig(
    val providerId: String,
    val maxAgeNanos: Long = 5_000_000_000L, // 5초
    val maxClockSkewNanos: Long = 1_000_000_000L, // 1초
    val timeoutMs: Long = 3000L,
    val version: String = "1.0.0"
)

/**
 * 어댑터·지역·전체 3단계 킬스위치 설정 (SR-F-051)
 */
data class SignalKillSwitchConfig(
    val globalKill: Boolean = false,
    val disabledProviders: Set<String> = emptySet(),
    val disabledRegions: Set<String> = emptySet()
) {
    fun isKilled(providerId: String, regionCode: String): Boolean {
        if (globalKill) return true
        if (disabledProviders.contains(providerId)) return true
        if (disabledRegions.contains(regionCode)) return true
        return false
    }
}

/**
 * 비식별 관측 메트릭 로그 (SR-NF-022, SR-NF-041)
 *
 * 위경도 좌표 및 통신 원문 페이로드를 일체 포함하지 않고,
 * 공급자 ID, 상태 코드, 지연시간 버킷만 안전하게 기록합니다.
 */
data class SignalMetricLog(
    val provider: String,
    val statusCode: String,
    val latencyBucket: String,
    val isSuccess: Boolean
) {
    companion object {
        fun computeLatencyBucket(latencyMs: Long): String = when {
            latencyMs < 100 -> "<100ms"
            latencyMs < 300 -> "100-300ms"
            latencyMs < 500 -> "300-500ms"
            else -> ">500ms"
        }
    }
}
