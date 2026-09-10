package kr.safecross.mobile

import kr.safecross.mobile.navigation.engine.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    @Test
    fun testDistanceCalculation() {
        // 광주광역시청 부근 두 지점 간 거리 (약 150m)
        val lat1 = 35.1595
        val lon1 = 126.8526
        val lat2 = 35.1605
        val lon2 = 126.8535

        val dist = GeoMath.distanceMeters(lat1, lon1, lat2, lon2)
        assertTrue("거리는 약 130~150m 범위여야 함 (실제: $dist)", dist in 130.0..150.0)
    }

    @Test
    fun testInitialBearing() {
        // 정북 방향 (0도)
        val bearingNorth = GeoMath.initialBearingDegrees(35.0, 126.0, 36.0, 126.0)
        assertEquals(0.0, bearingNorth, 1.0)

        // 정동 방향 (90도)
        val bearingEast = GeoMath.initialBearingDegrees(35.0, 126.0, 35.0, 127.0)
        assertEquals(90.0, bearingEast, 2.0)
    }

    @Test
    fun testBearingDifference() {
        // 45도와 50도 -> 차이 5도
        assertEquals(5.0, GeoMath.bearingDifference(45.0, 50.0), 0.001)

        // 10도와 350도 -> 차이 20도
        assertEquals(20.0, GeoMath.bearingDifference(10.0, 350.0), 0.001)

        // 0도와 180도 -> 차이 180도
        assertEquals(180.0, GeoMath.bearingDifference(0.0, 180.0), 0.001)
    }

    @Test
    fun testProjectPointOnSegment() {
        // 수평 선분 (lat=35.0, lon=126.0 -> lon=126.002)
        val p1Lat = 35.0
        val p1Lon = 126.0
        val p2Lat = 35.0
        val p2Lon = 126.002

        // 선분 중앙에서 북쪽으로 10m 떨어진 점
        val midLat = 35.0001
        val midLon = 126.001

        val proj = GeoMath.projectPointOnSegment(
            pLat = midLat,
            pLon = midLon,
            startLat = p1Lat,
            startLon = p1Lon,
            endLat = p2Lat,
            endLon = p2Lon
        )

        assertTrue("수직 편차는 약 10~12m여야 함 (실제: ${proj.crossTrackDistanceMeters})", proj.crossTrackDistanceMeters in 9.0..13.0)
        assertTrue("선분 중간(fraction 0.45~0.55)에 투영되어야 함", proj.segmentFraction in 0.45..0.55)
    }
}
