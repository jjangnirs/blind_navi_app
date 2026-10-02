package kr.safecross.vpsprobe

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * VPS 측정용 순수 계산 함수 (단위 테스트 대상).
 */
object ProbeMath {

    /**
     * ARCore GeospatialPose의 East-Up-South(EUS) 회전 쿼터니언(x, y, z, w)으로부터
     * 카메라가 바라보는 방향의 방위각(0~360°, 북=0, 동=90)을 구한다.
     * 카메라 좌표계에서 정면은 -Z 이다.
     */
    fun cameraHeadingFromEusQuaternion(qx: Float, qy: Float, qz: Float, qw: Float): Double {
        // v = q * (0, 0, -1) * q^-1
        val vx = -(2.0 * (qx * qz + qw * qy))
        val vz = -(1.0 - 2.0 * (qx * qx + qy * qy))
        // EUS: x=동, z=남 → 북 성분 = -z
        val east = vx
        val north = -vz
        val deg = Math.toDegrees(atan2(east, north))
        return (deg + 360.0) % 360.0
    }

    /** 두 방위각의 부호 있는 차이 (-180~180) */
    fun headingDiff(a: Double, b: Double): Double = ((a - b) % 360.0 + 540.0) % 360.0 - 180.0

    /** 두 좌표 간 거리(m), 짧은 거리용 평면 근사 */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1) * 6_371_000.0
        val dLon = Math.toRadians(lon2 - lon1) * 6_371_000.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    /** 최근 구간 통계 */
    data class Stats(val count: Int, val median: Double, val p90: Double)

    fun stats(values: List<Double>): Stats? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        return Stats(sorted.size, pct(0.5), pct(0.9))
    }
}
