package kr.safecross.mobile.navigation.crossing

import kotlin.math.roundToInt

/**
 * 시설 접근 알림 중복 방지 쿨다운 트래커 (SR-F-032 준수).
 *
 * 같은 횡단보도에 머무르거나 동일 방향으로 배회할 때 알림이 반복 폭주하는 것을 차단합니다.
 * 방향이 다르거나(예: 반대 방향에서 접근) 쿨다운 시간(기본 60초)이 경과하면 다시 알림을 허용합니다.
 */
class AlertCooldownTracker(
    private val cooldownMs: Long = 60_000L
) {
    // Key: "${crossingId}_${bearingBucket}", Value: timestampMs
    private val alertTimestamps = mutableMapOf<String, Long>()

    /**
     * 알림 허용 여부를 검사하고, 허용 시 알림 시각을 기록합니다.
     */
    fun shouldAlert(
        crossingId: String,
        bearingDeg: Double?,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        val key = buildKey(crossingId, bearingDeg)
        val lastTime = alertTimestamps[key]

        if (lastTime != null && (currentTimeMs - lastTime) < cooldownMs) {
            return false
        }

        alertTimestamps[key] = currentTimeMs
        return true
    }

    /**
     * 특정 키의 쿨다운을 초기화합니다.
     */
    fun clearAlert(crossingId: String, bearingDeg: Double?) {
        val key = buildKey(crossingId, bearingDeg)
        alertTimestamps.remove(key)
    }

    /**
     * 모든 쿨다운 기록을 초기화합니다.
     */
    fun reset() {
        alertTimestamps.clear()
    }

    private fun buildKey(crossingId: String, bearingDeg: Double?): String {
        return if (bearingDeg != null) {
            val bucket = ((bearingDeg / 45.0).roundToInt() * 45) % 360
            "${crossingId}_${bucket}"
        } else {
            "${crossingId}_NO_BEARING"
        }
    }
}
