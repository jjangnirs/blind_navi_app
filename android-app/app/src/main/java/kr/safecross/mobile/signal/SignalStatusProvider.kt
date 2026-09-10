package kr.safecross.mobile.signal

import kr.safecross.mobile.signal.model.ProviderConfig
import kr.safecross.mobile.signal.model.SignalFetchResult

/**
 * 공급자 독립적인 실시간 보행신호 어댑터 포트 인터페이스 (PR-F-016, TRD 4.7).
 */
interface SignalStatusProvider {
    val providerId: String
    val supportedRegions: Set<String>
    val config: ProviderConfig

    /**
     * 특정 교차로 및 방향(movement)의 실시간 보행신호 상태를 비동기로 조회합니다.
     */
    suspend fun fetchSignalStatus(
        intersectionId: String,
        movementId: String,
        currentElapsedRealtimeNanos: Long
    ): SignalFetchResult
}
