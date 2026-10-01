package kr.safecross.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.model.Maneuver
import kr.safecross.mobile.domain.model.PedestrianRoute
import kr.safecross.mobile.domain.model.RouteSegment
import kr.safecross.mobile.location.LocationSample
import kr.safecross.mobile.navigation.engine.GeoMath
import kr.safecross.mobile.navigation.engine.RouteProgressEngine
import kr.safecross.mobile.perception.DevicePose
import kr.safecross.mobile.ui.screens.navigation.NavigationViewModel
import kr.safecross.mobile.ui.screens.navigation.components.clockDirectionText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 저시력 방향 가이드 화살표(몸 기준 상대 방향), 경로 이탈 시 복귀 방향, 지도 회전 안정화 테스트.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectionGuidanceStabilityTest {

    private val testDispatcher = StandardTestDispatcher()

    // 정북 방향 약 200m 직선 경로
    private val start = LocationPoint(35.0000, 127.0000)
    private val end = LocationPoint(35.0018, 127.0000)
    private val route = PedestrianRoute(
        totalDistanceMeters = 200,
        totalDurationSeconds = 160,
        fullGeometry = listOf(start, end),
        maneuvers = listOf(
            Maneuver(index = 0, pointIndex = 0, location = start, instruction = "보행자도로를 따라 200m 이동", turnType = 11),
            Maneuver(index = 1, pointIndex = 1, location = end, instruction = "도착", turnType = 201)
        ),
        segments = listOf(RouteSegment(index = 0, name = "직진", distanceMeters = 200, durationSeconds = 160, geometry = listOf(start, end)))
    )

    private fun eastOf(lat: Double, meters: Double) = 127.0 + meters / (111_320.0 * Math.cos(Math.toRadians(lat)))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun pointAtDistanceInterpolatesAlongRoute() {
        val engine = RouteProgressEngine(route)
        val mid = engine.pointAtDistance(engine.totalRouteDistanceMeters / 2)!!
        assertEquals(35.0009, mid.lat, 1e-5)
        assertEquals(end, engine.pointAtDistance(engine.totalRouteDistanceMeters + 50))
        assertEquals(start, engine.pointAtDistance(-10.0))
    }

    @Test
    fun arrowPointsBackToRouteWhenUserDriftsAway() {
        val vm = NavigationViewModel()
        vm.setRoute(route)

        // 경로에서 동쪽으로 20m 벗어난 위치 (경로 진행 약 50m 지점)
        val lat = 35.00045
        vm.processLocationSample(
            LocationSample(lat = lat, lon = eastOf(lat, 20.0), accuracyMeters = 5f, speedMps = 0f, elapsedRealtimeNanos = 1L)
        )
        val bearing = vm.calculateTargetBearing()!!
        // 북쪽 전방 경로 지점을 향해 서쪽으로 비스듬히 복귀 (북서쪽, 약 340도)
        assertTrue("복귀 방위각($bearing)은 북서쪽이어야 함", bearing in 320.0..355.0)

        // 정북을 보고 있으면 화살표는 왼쪽 앞(11시 방향)을 가리켜야 함
        vm.processDevicePose(DevicePose(pitchDegrees = -70f, rollDegrees = 0f, headingDegrees = 0f), currentTimeMs = 1_000L)
        val relative = vm.uiState.value.relativeDirectionDegrees
        assertNotNull(relative)
        assertTrue("상대 각도($relative)는 왼쪽(음수)이어야 함", relative!! in -40f..-5f)
        assertEquals("11시 방향", clockDirectionText(relative))
    }

    @Test
    fun arrowFollowsBodyRotation() {
        val vm = NavigationViewModel()
        vm.setRoute(route)
        vm.processLocationSample(
            LocationSample(lat = 35.0002, lon = 127.0, accuracyMeters = 5f, speedMps = 0f, elapsedRealtimeNanos = 1L)
        )
        // 경로는 정북. 동쪽(90도)을 보고 서 있으면 가야 할 방향은 왼쪽 90도(9시)
        var t = 0L
        repeat(20) {
            t += 100L
            vm.processDevicePose(DevicePose(pitchDegrees = -70f, rollDegrees = 0f, headingDegrees = 90f), currentTimeMs = t)
        }
        val relative = vm.uiState.value.relativeDirectionDegrees!!
        assertEquals(-90f, relative, 5f)
        assertEquals("9시 방향", clockDirectionText(relative))
    }

    @Test
    fun mapHeadingIgnoresCompassJitter() {
        val vm = NavigationViewModel()
        vm.setRoute(route)

        // 보행 중 팔 흔들림: 나침반이 70도 ↔ 110도로 0.1초마다 흔들림 (평균 90도)
        var t = 0L
        val shownMapHeadings = mutableListOf<Float>()
        repeat(60) { i ->
            t += 100L
            val heading = if (i % 2 == 0) 70f else 110f
            vm.processDevicePose(DevicePose(pitchDegrees = -70f, rollDegrees = 0f, headingDegrees = heading), currentTimeMs = t)
            if (i >= 30) shownMapHeadings.add(vm.uiState.value.mapHeadingDegrees)
        }

        // 안정화 이후 지도 헤딩은 평균(90도) 근처에 머물러야 함 (±40도 진동이 지도에 그대로 반영되지 않음)
        shownMapHeadings.forEach { assertEquals(90f, it, 8f) }
    }

    @Test
    fun clockDirectionTextCoversAllSectors() {
        assertEquals("12시 방향", clockDirectionText(0f))
        assertEquals("3시 방향", clockDirectionText(90f))
        assertEquals("6시 방향", clockDirectionText(180f))
        assertEquals("6시 방향", clockDirectionText(-180f))
        assertEquals("9시 방향", clockDirectionText(-90f))
        assertEquals("1시 방향", clockDirectionText(25f))
    }

    @Test
    fun mapLocationSnapsToRouteWhenOnRoute() {
        val vm = NavigationViewModel()
        vm.setRoute(route)
        val lat = 35.0006
        vm.processLocationSample(
            LocationSample(lat = lat, lon = eastOf(lat, 6.0), accuracyMeters = 8f, speedMps = 1.2f, elapsedRealtimeNanos = 1L)
        )
        val shown = vm.uiState.value.mapLocation!!
        // 경로선(경도 127.0) 위로 맞춰 표시
        assertTrue(GeoMath.distanceMeters(shown.lat, shown.lon, lat, 127.0) < 1.0)
    }
}
