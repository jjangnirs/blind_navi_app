package kr.safecross.mobile

import kr.safecross.mobile.data.repository.FakeRouteRepository
import kr.safecross.mobile.location.LocationSample
import kr.safecross.mobile.navigation.engine.RouteProgressEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProgressEngineTest {

    private val fakeRepo = FakeRouteRepository()

    @Test
    fun testNormalRouteProgressAndManeuverAdvance() = runBlocking {
        val route = fakeRepo.getSampleRoute()
        val engine = RouteProgressEngine(
            route = route,
            offRouteThresholdMeters = 30.0,
            maneuverAdvanceDistanceMeters = 15.0
        )

        // 1. 출발점 (광주광역시청 앞)
        val sample1 = LocationSample(
            lat = 35.1595,
            lon = 126.8526,
            accuracyMeters = 5.0f
        )
        val progress1 = engine.updateProgress(sample1)
        assertEquals(0, progress1.currentManeuverIndex)
        assertFalse(progress1.isOffRoute)
        assertTrue("진행 거리는 0m 부근이어야 함", progress1.distanceAlongRouteMeters < 5.0)

        // 2. 250m 전진 (첫 번째 전환 지점 Maneuver 1 부근 도달)
        val m1 = route.maneuvers[1]
        val sample2 = LocationSample(
            lat = m1.location.lat,
            lon = m1.location.lon,
            accuracyMeters = 5.0f
        )
        val progress2 = engine.updateProgress(sample2)
        assertEquals("Maneuver 지점 도달 시 다음 스텝(1)으로 전진해야 함", 1, progress2.currentManeuverIndex)
        assertFalse(progress2.isOffRoute)

        // 3. 목적지 도착
        val lastPoint = route.fullGeometry.last()
        val sampleDest = LocationSample(
            lat = lastPoint.lat,
            lon = lastPoint.lon,
            accuracyMeters = 3.0f
        )
        val progressDest = engine.updateProgress(sampleDest)
        assertTrue("목적지 도착 시 isFinished가 true여야 함", progressDest.isFinished)
        assertEquals(0.0, progressDest.remainingDistanceMeters, 0.001)
    }

    @Test
    fun testOffRouteDetection() = runBlocking {
        val route = fakeRepo.getSampleRoute()
        val engine = RouteProgressEngine(
            route = route,
            offRouteThresholdMeters = 30.0
        )

        // 1. 정상 위치
        val normalSample = LocationSample(lat = 35.1595, lon = 126.8526, accuracyMeters = 5.0f)
        val p1 = engine.updateProgress(normalSample)
        assertFalse(p1.isOffRoute)

        // 2. 경로로부터 50m 이상 벗어난 지점 (1회차)
        val offSample1 = LocationSample(lat = 35.1590, lon = 126.8540, accuracyMeters = 5.0f)
        val p2 = engine.updateProgress(offSample1)
        assertTrue("수직 이탈 거리가 30m 초과여야 함", p2.crossTrackErrorMeters > 30.0)

        // 3. 경로로부터 50m 이상 벗어난 지점 (2회차 연속)
        val offSample2 = LocationSample(lat = 35.1588, lon = 126.8542, accuracyMeters = 5.0f)
        val p3 = engine.updateProgress(offSample2)
        assertTrue("연속 2회 이탈 시 isOffRoute가 true여야 함", p3.isOffRoute)
    }
}
