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
                "facilityType": "15"
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
    fun testMapFacilityType_AdheresToOfficialTmapSpec() {
        val sampleGeoJson = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "geometry": { "type": "Point", "coordinates": [126.85, 35.15] },
              "properties": { "index": 0, "pointIndex": 0, "name": "보도", "facilityType": "11" }
            },
            {
              "type": "Feature",
              "geometry": { "type": "Point", "coordinates": [126.86, 35.16] },
              "properties": { "index": 1, "pointIndex": 1, "name": "육교", "facilityType": "12" }
            },
            {
              "type": "Feature",
              "geometry": { "type": "Point", "coordinates": [126.87, 35.17] },
              "properties": { "index": 2, "pointIndex": 2, "name": "횡단보도", "facilityType": "15" }
            }
          ]
        }
        """.trimIndent()

        val route = repository.parseTmapGeoJson(sampleGeoJson, excludeStairs = true)
        // 11은 보행로 (기존 버그였던 '보도육교'가 아님을 보장)
        assertEquals("보행로", route.maneuvers[0].facilityType)
        // 12는 실제 육교
        assertEquals("육교", route.maneuvers[1].facilityType)
        // 15는 횡단보도
        assertEquals("횡단보도", route.maneuvers[2].facilityType)
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
            baseUrl = "http://127.0.0.1:59999/non_existent",
            backendUrl = "http://127.0.0.1:59999/non_existent"
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

    @Test
    fun testParseBackendRouteJson_Success() {
        val backendJson = """
        {
          "dataVersion": "0.1.0",
          "provider": "TMAP",
          "totalDistanceMeters": 282,
          "totalDurationSeconds": 235,
          "excludeStairs": true,
          "fullGeometry": [
            { "lat": 35.1500, "lon": 126.8500 },
            { "lat": 35.1510, "lon": 126.8520 }
          ],
          "maneuvers": [
            {
              "index": 0,
              "pointIndex": 0,
              "location": { "lat": 35.1500, "lon": 126.8500 },
              "instruction": "금남로를 따라 120m 직진하세요",
              "turnType": 200,
              "facilityType": "보행로"
            }
          ],
          "segments": [
            {
              "index": 0,
              "name": "금남로",
              "distanceMeters": 120,
              "durationSeconds": 96,
              "geometry": [
                { "lat": 35.1500, "lon": 126.8500 }
              ],
              "facilityType": "보행로"
            }
          ],
          "disclaimer": "안전 고지문"
        }
        """.trimIndent()

        val route = repository.parseBackendRouteJson(backendJson)
        assertEquals("TMAP", route.provider)
        assertEquals(282, route.totalDistanceMeters)
        assertEquals(235, route.totalDurationSeconds)
        assertTrue(route.excludeStairs)
        assertEquals(1, route.maneuvers.size)
        assertEquals("보행로", route.maneuvers[0].facilityType)
        assertEquals(2, route.fullGeometry.size)
    }
}
