package kr.safecross.mobile.location

import kr.safecross.mobile.navigation.engine.GeoMath

/**
 * 보행자 기구학(Kinematics) 기반 GPS 이상치 및 기지국 튐 기각 필터 (SRD 3.1, TRD 4.2 준수).
 *
 * 9월 26일 포렌식 로그에서 규명된 3대 결함 차단:
 * 1. 선행 양호 위치(정확도 <= 15m) 확보 후 불시에 유입되는 단발성 기지국 픽스(정확도 > 45m) 차단
 * 2. 보행 환경에서 물리적으로 불가능한 속도(> 10m/s = 36km/h) 및 25m 이상 순간이동(Teleport) 차단
 * 3. 연속 [maxConsecutiveOutliers]회 이상 이상치 지속 시 데드락 방지를 위해 신규 위치 강제 수용 (차량/대중교통 탑승 대비)
 */
class LocationOutlierFilter(
    private val maxWalkingSpeedMps: Double = 10.0,
    private val maxTeleportDistM: Double = 25.0,
    private val maxAcceptableAccuracyM: Float = 45.0f,
    private val maxConsecutiveOutliers: Int = 4
) {
    var lastAcceptedLocation: LocationSample? = null
        private set
    var consecutiveOutlierCount: Int = 0
        private set
    private var lastKnownGoodAccuracy: Float = Float.MAX_VALUE

    fun filter(sample: LocationSample): LocationSample? {
        val last = lastAcceptedLocation
        if (last == null) {
            accept(sample)
            return sample
        }

        // 1. 단발성 극단 저정밀도(기지국/다중경로 반사파) 기각
        if (lastKnownGoodAccuracy <= 15.0f && sample.accuracyMeters > maxAcceptableAccuracyM) {
            consecutiveOutlierCount++
            if (consecutiveOutlierCount < maxConsecutiveOutliers) {
                return null
            }
        }

        // 2. 보행자 기구학 순간이동(Teleport) 기각
        val deltaNanos = sample.elapsedRealtimeNanos - last.elapsedRealtimeNanos
        val deltaSec = if (deltaNanos in 1L..5_000_000_000L) {
            deltaNanos / 1_000_000_000.0
        } else {
            val wallDiff = (sample.timestampEpochMs - last.timestampEpochMs) / 1000.0
            if (wallDiff in 0.001..5.0) wallDiff else null
        }

        if (deltaSec != null && deltaSec > 0.05) {
            val distMeters = GeoMath.distanceMeters(
                last.lat, last.lon, sample.lat, sample.lon
            )
            val speedMps = distMeters / deltaSec

            val isTeleport = (distMeters > maxTeleportDistM && speedMps > maxWalkingSpeedMps && sample.accuracyMeters > 15.0f) ||
                             (distMeters > 50.0 && speedMps > 15.0)

            if (isTeleport) {
                consecutiveOutlierCount++
                if (consecutiveOutlierCount < maxConsecutiveOutliers) {
                    return null
                }
            }
        }

        accept(sample)
        return sample
    }

    private fun accept(sample: LocationSample) {
        consecutiveOutlierCount = 0
        lastAcceptedLocation = sample
        if (sample.accuracyMeters <= 20.0f) {
            lastKnownGoodAccuracy = sample.accuracyMeters
        }
    }

    fun reset() {
        lastAcceptedLocation = null
        consecutiveOutlierCount = 0
        lastKnownGoodAccuracy = Float.MAX_VALUE
    }
}
