package kr.safecross.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.safecross.mobile.data.repository.FakeRouteRepository
import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.location.LocationSample
import kr.safecross.mobile.navigation.engine.GeoMath
import kr.safecross.mobile.ui.screens.navigation.NavigationViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 카메라 신호 확인 화면에서 횡단보도를 다 건너면 길안내 화면으로 자동 복귀하는 판정 테스트.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossingReturnToNavigationTest {

    private val testDispatcher = StandardTestDispatcher()

    // 가짜 경로: 출발 -> 중간 지점 횡단보도(turnType 211) -> 도착 (각 구간 약 138m)
    private val origin = LocationPoint(35.1595, 126.8526)
    private val destination = LocationPoint(35.1610, 126.8550)
    private val route = FakeRouteRepository().fakeRoute()
    private val crosswalk = route.maneuvers[1].location

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 횡단보도 시작점 기준 경로상 [offsetMeters] 위치 (음수 = 앞, 양수 = 건너편 방향) */
    private fun pointAt(offsetMeters: Double): LocationPoint {
        val (a, b) = if (offsetMeters <= 0) origin to crosswalk else crosswalk to destination
        val len = GeoMath.distanceMeters(a.lat, a.lon, b.lat, b.lon)
        val t = if (offsetMeters <= 0) (len + offsetMeters) / len else offsetMeters / len
        return LocationPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    private fun sample(offsetMeters: Double, speed: Float, accuracy: Float = 6f): LocationSample {
        val p = pointAt(offsetMeters)
        return LocationSample(lat = p.lat, lon = p.lon, accuracyMeters = accuracy, speedMps = speed, elapsedRealtimeNanos = System.nanoTime())
    }

    private fun FakeRouteRepository.fakeRoute() = kotlinx.coroutines.runBlocking {
        getPedestrianRoute(origin = LocationPoint(35.1595, 126.8526), destination = LocationPoint(35.1610, 126.8550)).getOrThrow()
    }

    @Test
    fun returnsToNavigationAfterReachingFarSideOnce() = runTest(testDispatcher) {
        val vm = NavigationViewModel()
        val events = mutableListOf<String>()
        val job = launch { vm.crossingCompleted.collect { events.add(it) } }
        vm.setRoute(route)
        advanceUntilIdle()

        vm.processLocationSample(sample(-10.0, 1.1f))
        vm.onCrossingAssistOpened()
        vm.onCrossingGreenConfirmed()

        // 횡단 중 (건너편 끝 = 시작점 + 20m)
        vm.processLocationSample(sample(8.0, 1.1f))
        advanceUntilIdle()
        assertTrue("아직 건너는 중에는 복귀하지 않음", events.isEmpty())

        // 건너편 도착 2회 연속
        vm.processLocationSample(sample(18.0, 1.0f))
        vm.processLocationSample(sample(21.0, 1.0f))
        advanceUntilIdle()
        assertEquals(listOf("횡단보도를 건넜습니다. 길안내로 돌아갑니다."), events)

        // 이후 샘플에서 다시 발생하지 않음
        vm.processLocationSample(sample(26.0, 1.0f))
        advanceUntilIdle()
        assertEquals(1, events.size)
        job.cancel()
    }

    @Test
    fun doesNotReturnWhileStandingWithoutGreenEvenIfGpsJumpsAcross() = runTest(testDispatcher) {
        val vm = NavigationViewModel()
        val events = mutableListOf<String>()
        val job = launch { vm.crossingCompleted.collect { events.add(it) } }
        vm.setRoute(route)
        advanceUntilIdle()

        vm.processLocationSample(sample(-5.0, 0.0f))
        vm.onCrossingAssistOpened()

        // 적색 대기 중 GPS가 건너편으로 튄 경우: 녹색 미확정 + 정지 상태이면 복귀하지 않음
        repeat(3) { vm.processLocationSample(sample(22.0, 0.1f)) }
        advanceUntilIdle()
        assertTrue(events.isEmpty())
        job.cancel()
    }

    @Test
    fun manualCloseStopsWatching() = runTest(testDispatcher) {
        val vm = NavigationViewModel()
        val events = mutableListOf<String>()
        val job = launch { vm.crossingCompleted.collect { events.add(it) } }
        vm.setRoute(route)
        advanceUntilIdle()

        vm.processLocationSample(sample(-10.0, 1.1f))
        vm.onCrossingAssistOpened()
        vm.onCrossingAssistClosed()
        vm.processLocationSample(sample(20.0, 1.0f))
        vm.processLocationSample(sample(22.0, 1.0f))
        advanceUntilIdle()
        assertTrue(events.isEmpty())
        job.cancel()
    }

    @Test
    fun reenteringNavigationScreenKeepsProgress() = runTest(testDispatcher) {
        val vm = NavigationViewModel()
        vm.setRoute(route)
        advanceUntilIdle()
        vm.processLocationSample(sample(-10.0, 1.1f))
        vm.processLocationSample(sample(5.0, 1.1f))
        val alongBefore = vm.uiState.value.distanceAlongRouteMeters
        assertTrue(alongBefore > 100)

        // 카메라 화면에서 돌아와 길안내 화면이 다시 그려질 때
        vm.ensureRoute(route)
        assertEquals(alongBefore, vm.uiState.value.distanceAlongRouteMeters)
    }
}
