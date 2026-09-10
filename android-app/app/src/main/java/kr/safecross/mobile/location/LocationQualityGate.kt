package kr.safecross.mobile.location

/**
 * 위치 품질 상태 판정 결과.
 */
enum class LocationQualityStatus {
    USABLE,
    ACCURACY_TOO_LOW,
    STALE_SAMPLE,
    MOCK_NOT_ALLOWED,
    OUT_OF_BOUNDS
}

/**
 * 위치 품질 게이트 (TRD 4.2 준수).
 *
 * GPS 정확도 및 샘플 신선도를 엄격히 검사하여, 신뢰할 수 없는 위치로 인한
 * 정밀 횡단보도 진입 및 오작동을 방지합니다.
 */
object LocationQualityGate {

    const val DEFAULT_MAX_ACCURACY_METERS = 20.0f
    const val DEFAULT_MAX_AGE_SECONDS = 10.0

    // 대한민국 지리적 범위 (SRD 3.1)
    const val MIN_LAT = 32.0
    const val MAX_LAT = 39.0
    const val MIN_LON = 124.0
    const val MAX_LON = 132.0

    /**
     * 위치 샘플의 품질 상태를 평가합니다.
     */
    fun evaluateQuality(
        sample: LocationSample,
        maxAccuracyM: Float = DEFAULT_MAX_ACCURACY_METERS,
        maxAgeSeconds: Double = DEFAULT_MAX_AGE_SECONDS,
        allowMock: Boolean = false,
        currentElapsedNanos: Long = System.nanoTime()
    ): LocationQualityStatus {
        // 1. 대한민국 지리적 범위 검증
        if (sample.lat < MIN_LAT || sample.lat > MAX_LAT ||
            sample.lon < MIN_LON || sample.lon > MAX_LON
        ) {
            return LocationQualityStatus.OUT_OF_BOUNDS
        }

        // 2. 모의 위치(Mock) 차단 (테스트 모드가 아닌 경우)
        if (sample.isMock && !allowMock) {
            return LocationQualityStatus.MOCK_NOT_ALLOWED
        }

        // 3. 정확도 반경 검증
        if (sample.accuracyMeters <= 0f || sample.accuracyMeters > maxAccuracyM) {
            return LocationQualityStatus.ACCURACY_TOO_LOW
        }

        // 4. 단조 시계 기준 샘플 만료 여부 검증
        val ageSec = sample.ageSeconds(currentElapsedNanos)
        if (ageSec > maxAgeSeconds) {
            return LocationQualityStatus.STALE_SAMPLE
        }

        return LocationQualityStatus.USABLE
    }

    /**
     * 위치 샘플을 정밀 보행 안내에 사용할 수 있는지 판정합니다.
     */
    fun isLocationUsable(
        sample: LocationSample,
        maxAccuracyM: Float = DEFAULT_MAX_ACCURACY_METERS,
        maxAgeSeconds: Double = DEFAULT_MAX_AGE_SECONDS,
        allowMock: Boolean = false,
        currentElapsedNanos: Long = System.nanoTime()
    ): Boolean {
        return evaluateQuality(
            sample = sample,
            maxAccuracyM = maxAccuracyM,
            maxAgeSeconds = maxAgeSeconds,
            allowMock = allowMock,
            currentElapsedNanos = currentElapsedNanos
        ) == LocationQualityStatus.USABLE
    }
}
