package kr.safecross.mobile.location

import kotlinx.coroutines.flow.Flow

/**
 * 위치 공급원 추상화 인터페이스.
 */
interface LocationSource {
    /**
     * 실시간 위치 샘플 스트림.
     */
    val locationUpdates: Flow<LocationSample>

    /**
     * 위치 추적 시작.
     */
    fun startTracking()

    /**
     * 위치 추적 중지.
     */
    fun stopTracking()

    /**
     * 단말 GPS 센서 활성화 여부.
     */
    fun isGpsEnabled(): Boolean

    /**
     * 정밀 위치(Fine Location) 권한 획득 여부.
     */
    fun hasFineLocationPermission(): Boolean
}
