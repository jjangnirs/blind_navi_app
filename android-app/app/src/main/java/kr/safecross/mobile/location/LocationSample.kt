package kr.safecross.mobile.location

/**
 * 위치 샘플 데이터 모델.
 *
 * SR-NF-022, SR-NF-041 준수:
 * 개인정보 보호 및 위치 기밀성을 위해 [toString]은 위도/경도 원문을 직접 노출하지 않고
 * 정확도, 경과 시간, Mock 여부 등 안전 판정 메타데이터만 출력합니다.
 *
 * TRD 4.2 준수:
 * 안전 판정은 시스템 시계(wall-clock) 조작이나 시간대 변경의 영향을 배제하기 위해
 * 단조 증가 시계([elapsedRealtimeNanos])를 사용하여 샘플 신선도를 계산합니다.
 */
data class LocationSample(
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Float,
    val bearingDegrees: Float? = null,
    val speedMps: Float? = null,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val elapsedRealtimeNanos: Long = 0L,
    val isMock: Boolean = false,
    val signalStrengthPercent: Int = calculateSignalStrengthPercent(accuracyMeters),
    val satelliteCount: Int = 0
) {
    /**
     * 지정된 단조 시간([currentElapsedNanos]) 기준 샘플 경과 시간(초).
     * System.nanoTime()과 Android Location의 elapsedRealtimeNanos(SystemClock 기준) 간
     * 클록 베이스 불일치(음수이거나 60초 초과) 발생 시 실제 수신 시각(wall-clock)으로 안전하게 폴백합니다.
     */
    fun ageSeconds(currentElapsedNanos: Long = 0L): Double {
        val nowMs = System.currentTimeMillis()
        val wallDiffSec = (nowMs - timestampEpochMs).coerceAtLeast(0L) / 1000.0

        if (elapsedRealtimeNanos <= 0L || currentElapsedNanos <= 0L) {
            return wallDiffSec
        }
        val diffNanos = currentElapsedNanos - elapsedRealtimeNanos
        if (diffNanos < 0L || diffNanos > 60_000_000_000L) {
            return wallDiffSec
        }
        return diffNanos / 1_000_000_000.0
    }

    /**
     * 개인정보 보호를 위해 위경도 원문을 마스킹한 안전 문자열.
     */
    override fun toString(): String {
        return "LocationSample(lat=[REDACTED], lon=[REDACTED], accuracy=${accuracyMeters}m, bearing=$bearingDegrees, speed=$speedMps, isMock=$isMock)"
    }

    /**
     * 내부 디버깅 시에도 안전한 포맷.
     */
    fun toSafeSummary(currentElapsedNanos: Long): String {
        val age = String.format("%.1f", ageSeconds(currentElapsedNanos))
        return "accuracy=${accuracyMeters}m, signal=${signalStrengthPercent}%, age=${age}s, bearing=${bearingDegrees ?: "N/A"}, mock=$isMock"
    }
}

/**
 * GPS 정확도(미터) 및 위성 수를 기반으로 0~100% 수신 강도를 환산합니다.
 */
fun calculateSignalStrengthPercent(accuracyMeters: Float, satelliteCount: Int = 0): Int {
    if (accuracyMeters <= 0f) return 10
    val baseScore = when {
        accuracyMeters <= 3.0f -> 100
        accuracyMeters <= 5.0f -> 95
        accuracyMeters <= 8.0f -> 90
        accuracyMeters <= 12.0f -> 80
        accuracyMeters <= 16.0f -> 70
        accuracyMeters <= 22.0f -> 55
        accuracyMeters <= 35.0f -> 40
        accuracyMeters <= 50.0f -> 25
        else -> 10
    }
    val satBonus = (satelliteCount * 1).coerceAtMost(10)
    return (baseScore + satBonus).coerceIn(5, 100)
}
