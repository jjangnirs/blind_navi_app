package kr.safecross.mobile.navigation.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 순수 Kotlin 지리 기하 연산 유틸리티.
 *
 * Android 프레임워크나 외부 C++ 라이브러리 없이 순수 수식으로 작동하여
 * 로컬 JVM 단위 테스트에서 완벽하게 동작합니다.
 */
object GeoMath {

    const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * 두 WGS84 좌표 간의 대권 거리(Haversine Formula)를 미터 단위로 계산합니다.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * 시작점에서 목표점을 향하는 초기 방위각(Bearing)을 0~360° 범위로 계산합니다.
     */
    fun initialBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)

        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)
        val bearingRad = atan2(y, x)

        return (Math.toDegrees(bearingRad) + 360.0) % 360.0
    }

    /**
     * 두 방위각 간의 최소 각도 차이(0~180°)를 계산합니다.
     */
    fun bearingDifference(bearing1: Double, bearing2: Double): Double {
        val diff = abs(bearing1 - bearing2) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    /**
     * 선분 상의 점 투영 결과.
     */
    data class ProjectionResult(
        val projLat: Double,
        val projLon: Double,
        val crossTrackDistanceMeters: Double,
        val alongTrackDistanceMeters: Double,
        val segmentFraction: Double // 0.0 ~ 1.0
    )

    /**
     * WGS84 평면 근사(Equirectangular approximation)를 사용하여
     * 점 [pLat, pLon]을 선분 [startLat, startLon] -> [endLat, endLon]에 직교 투영합니다.
     */
    fun projectPointOnSegment(
        pLat: Double,
        pLon: Double,
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): ProjectionResult {
        val segmentLength = distanceMeters(startLat, startLon, endLat, endLon)
        if (segmentLength <= 0.0001) {
            val dist = distanceMeters(pLat, pLon, startLat, startLon)
            return ProjectionResult(
                projLat = startLat,
                projLon = startLon,
                crossTrackDistanceMeters = dist,
                alongTrackDistanceMeters = 0.0,
                segmentFraction = 0.0
            )
        }

        // 중심 위도 기준 국소 직교좌표계 (미터 단위) 변환
        val midLat = Math.toRadians((startLat + endLat) / 2.0)
        val mPerDegLat = 111132.92 - 559.82 * cos(2 * midLat) + 1.175 * cos(4 * midLat)
        val mPerDegLon = (PI / 180.0) * EARTH_RADIUS_METERS * cos(midLat)

        val ax = 0.0
        val ay = 0.0

        val bx = (endLon - startLon) * mPerDegLon
        val by = (endLat - startLat) * mPerDegLat

        val px = (pLon - startLon) * mPerDegLon
        val py = (pLat - startLat) * mPerDegLat

        val abx = bx - ax
        val aby = by - ay
        val abLenSq = abx * abx + aby * aby

        val apx = px - ax
        val apy = py - ay

        val t = if (abLenSq > 0) {
            ((apx * abx + apy * aby) / abLenSq).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val projX = ax + t * abx
        val projY = ay + t * aby

        val projLon = startLon + projX / mPerDegLon
        val projLat = startLat + projY / mPerDegLat

        val crossTrack = distanceMeters(pLat, pLon, projLat, projLon)
        val alongTrack = segmentLength * t

        return ProjectionResult(
            projLat = projLat,
            projLon = projLon,
            crossTrackDistanceMeters = crossTrack,
            alongTrackDistanceMeters = alongTrack,
            segmentFraction = t
        )
    }
}
