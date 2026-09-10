package kr.safecross.mobile.navigation.crossing

import kr.safecross.mobile.domain.model.WalkingMode
import kr.safecross.mobile.location.LocationQualityGate
import kr.safecross.mobile.location.LocationSample
import kr.safecross.mobile.navigation.engine.GeoMath

/**
 * 횡단보도 접근 알림 이벤트.
 */
data class CrossingAlertEvent(
    val message: String,
    val isSpeechNeeded: Boolean,
    val targetMode: WalkingMode,
    val isDegraded: Boolean = false,
    val crossingId: String? = null
)

/**
 * 횡단보도 접근 및 안전 상태 판정 엔진 (TRD 4.3, SR-F-030~033 준수).
 */
class CrossingApproachEngine(
    private val facilities: List<CrossingFacility>,
    private val cooldownTracker: AlertCooldownTracker = AlertCooldownTracker(),
    private val advanceAlertDistanceMeters: Double = 40.0,
    private val stopPrepareDistanceMeters: Double = 15.0,
    private val crossingEnterDistanceMeters: Double = 8.0,
    private val maxBearingDiffDegrees: Double = 60.0
) {

    /**
     * 현재 위치 샘플을 기반으로 접근 중인 횡단보도를 분석하고 적절한 알림 이벤트를 생성합니다.
     */
    fun evaluateApproach(
        sample: LocationSample,
        currentElapsedNanos: Long = System.nanoTime(),
        currentTimeMs: Long = System.currentTimeMillis(),
        allowMock: Boolean = false
    ): CrossingAlertEvent? {
        // 1. GPS 품질 게이트 검증 (SR-F-031, TRD 4.2)
        val isGpsUsable = LocationQualityGate.isLocationUsable(
            sample = sample,
            maxAccuracyM = 25.0f,
            maxAgeSeconds = 15.0,
            allowMock = allowMock,
            currentElapsedNanos = currentElapsedNanos
        )

        // GPS 정확도가 불량한 경우: 정밀 횡단 진입 차단 및 Degraded 경고 반환
        if (!isGpsUsable) {
            val degradedKey = "GPS_DEGRADED"
            if (cooldownTracker.shouldAlert(degradedKey, null, currentTimeMs)) {
                return CrossingAlertEvent(
                    message = "GPS 신호가 약하여 정밀 횡단 안내를 일시 중지합니다 (수신 강도 ${sample.signalStrengthPercent}%).",
                    isSpeechNeeded = true,
                    targetMode = WalkingMode.WALKING,
                    isDegraded = true
                )
            }
            return null
        }

        // 2. 반경 50m 내 횡단보도 후보 탐색
        var nearestFacility: CrossingFacility? = null
        var minDistance = Double.MAX_VALUE

        for (facility in facilities) {
            val dist = GeoMath.distanceMeters(sample.lat, sample.lon, facility.lat, facility.lon)
            if (dist <= advanceAlertDistanceMeters && dist < minDistance) {
                // 진행 방위각 비교 (진행 방위각이 있고 시설 방위각이 있는 경우)
                val userBearing = sample.bearingDegrees
                val facilityBearing = facility.approachBearingDeg

                if (userBearing != null && facilityBearing != null) {
                    val diff = GeoMath.bearingDifference(userBearing.toDouble(), facilityBearing)
                    // 반대편 도로이거나 방위각 차이가 60도 초과 시 무시
                    if (diff > maxBearingDiffDegrees) {
                        continue
                    }
                }

                minDistance = dist
                nearestFacility = facility
            }
        }

        if (nearestFacility == null) {
            return null
        }

        // 3. 거리별 상태 및 메시지 판정
        val facility = nearestFacility
        val distInt = minDistance.toInt().coerceAtLeast(1)
        val userBearing = sample.bearingDegrees?.toDouble()

        // 쿨다운 검증: 동일 시설, 동일 방향 반복 폭주 차단 (SR-F-032)
        val shouldAlert = cooldownTracker.shouldAlert(facility.id, userBearing, currentTimeMs)

        return when {
            // A. 횡단보도 건너는 중 (8m 이내)
            minDistance <= crossingEnterDistanceMeters -> {
                if (shouldAlert) {
                    val msg = if (facility.isFieldVerified) {
                        "횡단보도 진입 중입니다. 좌우를 살피며 전방을 주시하세요."
                    } else {
                        "횡단보도 추정 구역입니다. 주변을 주의 깊게 확인하세요."
                    }
                    CrossingAlertEvent(
                        message = msg,
                        isSpeechNeeded = true,
                        targetMode = WalkingMode.CROSSING,
                        crossingId = facility.id
                    )
                } else null
            }

            // B. 정지 준비 구간 (15m 이내)
            minDistance <= stopPrepareDistanceMeters -> {
                if (shouldAlert) {
                    val msg = if (facility.isFieldVerified) {
                        buildVerifiedStopMessage(facility, distInt)
                    } else {
                        "약 ${distInt}미터 앞 전방에 횡단보도가 있습니다. 미검증 정보이므로 직접 확인하세요."
                    }
                    CrossingAlertEvent(
                        message = msg,
                        isSpeechNeeded = true,
                        targetMode = WalkingMode.APPROACHING_CROSSING,
                        crossingId = facility.id
                    )
                } else null
            }

            // C. 사전 알림 구간 (40m 이내)
            minDistance <= advanceAlertDistanceMeters -> {
                if (shouldAlert) {
                    val msg = if (facility.isFieldVerified) {
                        "약 ${distInt}미터 앞 횡단보도 접근 중입니다."
                    } else {
                        "약 ${distInt}미터 앞 횡단보도가 있을 수 있습니다 (미검증 정보)."
                    }
                    CrossingAlertEvent(
                        message = msg,
                        isSpeechNeeded = true,
                        targetMode = WalkingMode.APPROACHING_CROSSING,
                        crossingId = facility.id
                    )
                } else null
            }

            else -> null
        }
    }

    private fun buildVerifiedStopMessage(facility: CrossingFacility, distInt: Int): String {
        val sb = StringBuilder("약 ${distInt}미터 앞 횡단보도 정지 준비.")
        if (facility.acousticSignal == true) {
            sb.append(" 시각장애인 음향신호기가 설치되어 있습니다.")
        } else if (facility.acousticSignal == false) {
            sb.append(" 음향신호기 미설치 구간입니다.")
        }
        if (facility.curbCut == true) {
            sb.append(" 턱낮춤 설치됨.")
        }
        return sb.toString()
    }

    fun reset() {
        cooldownTracker.reset()
    }
}
