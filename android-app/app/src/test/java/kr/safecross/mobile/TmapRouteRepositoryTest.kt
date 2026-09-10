package kr.safecross.mobile

import kotlinx.coroutines.test.runTest
import kr.safecross.mobile.data.repository.TmapRouteRepository
import kr.safecross.mobile.domain.model.LocationPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmapRouteRepositoryTest {

    private val repository = TmapRouteRepository(appKey = "test_key")

    @Test
    fun testParseTmapGeoJson_Success() {
        val sampleGeoJson = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "geometry": {
                "type": "Point",
                "coordinates": [126.8526, 35.1595]
              },
              "properties": {
                "index": 0,
                "pointIndex": 0,
                "name": "광주광역시청",
                "description": "광주광역시청에서 출발",
                "turnType": 200,
                "totalDistance": 450,
                "totalTime": 360
              }
            },
            {
              "type": "Feature",
              "geometry": {
                "type": "LineString",
                "coordinates": [
                  [126.8526, 35.1595],
                  [126.8535, 35.1600]
                ]
              },
              "properties": {
                "index": 1,
                "name": "내방로 보행로",
                "distance": 120,
                "time": 90,
                "facilityType": "보행로"
              }
            },
            {
              "type": "Feature",
              "geometry": {
                "type": "Point",
                "coordinates": [126.8535, 35.1600]
              },
              "properties": {
                "index": 2,
                "pointIndex": 1,
                "name": "횡단보도",
                "description": "횡단보도 이용",
                "turnType": 211,
                "facilityType": "1"
              }
            }
          ]
        }
        """.trimIndent()

        val route = repository.parseTmapGeoJson(sampleGeoJson, excludeStairs = true)

        assertEquals("TMAP", route.provider)
        assertEquals(450, route.totalDistanceMeters)
        assertEquals(360, route.totalDurationSeconds)
        assertTrue(route.excludeStairs)
        assertEquals(2, route.maneuvers.size)
        assertEquals("횡단보도", route.maneuvers[1].facilityType)
        assertEquals(1, route.segments.size)
        assertEquals(2, route.fullGeometry.size)
    }

    @Test
    fun testGenerateFallbackRoute_CreatesValidRoute() {
        val origin = LocationPoint(37.5665, 126.9780) // 서울시청
        val destination = LocationPoint(37.5700, 126.9820) // 종로

        val route = repository.generateFallbackRoute(
            origin = origin,
            destination = destination,
            originName = "서울시청",
            destinationName = "종로",
            excludeStairs = true
        )

        assertTrue(route.provider.contains("Fallback"))
        assertTrue(route.totalDistanceMeters > 0)
        assertTrue(route.totalDurationSeconds > 0)
        assertEquals(3, route.maneuvers.size)
        assertEquals("횡단보도", route.maneuvers[1].facilityType)
        assertEquals(2, route.segments.size)
        assertEquals(3, route.fullGeometry.size)
    }

    @Test
    fun testGetPedestrianRoute_FallbackOnNetworkFailure() = runTest {
        // 존재하지 않는 URL로 호출하여 네트워크 오류 유도 -> Fallback으로 안전 반환 확인
        val badRepo = TmapRouteRepository(
            appKey = "invalid",
            baseUrl = "http://127.0.0.1:59999/non_existent"
        )
        val result = badRepo.getPedestrianRoute(
            origin = LocationPoint(35.1595, 126.8526),
            destination = LocationPoint(35.1610, 126.8550),
            originName = "출발지",
            destinationName = "목적지",
            excludeStairs = true
        )

        assertTrue(result.isSuccess)
        val route = result.getOrThrow()
        assertNotNull(route)
        assertTrue(route.provider.contains("Fallback"))
    }
}
