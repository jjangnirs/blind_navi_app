package kr.safecross.mobile

import kr.safecross.mobile.data.repository.FakeRouteRepository
import kr.safecross.mobile.domain.model.WalkingMode
import kr.safecross.mobile.location.FakeLocationSource
import kr.safecross.mobile.location.LocationSample
import kr.safecross.mobile.service.NavigationForegroundService
import kr.safecross.mobile.ui.screens.navigation.NavigationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationForegroundServiceContractTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testServiceConstantsAndActions() {
        assertEquals("kr.safecross.mobile.action.START_NAVIGATION", NavigationForegroundService.ACTION_START_NAVIGATION)
        assertEquals("kr.safecross.mobile.action.STOP_NAVIGATION", NavigationForegroundService.ACTION_STOP_NAVIGATION)
        assertEquals("safecross_navigation_channel", NavigationForegroundService.CHANNEL_ID)
        assertEquals(1001, NavigationForegroundService.NOTIFICATION_ID)
    }

    @Test
    fun testProcessRecoveryInitialStateIsIdle() {
        // SR-NF-004: 앱 프로세스 복구 후 횡단 상태는 무조건 IDLE에서 시작해야 함
        val freshVm = NavigationViewModel()
        assertEquals(
            "프로세스 복구 직후 초기 상태는 무조건 IDLE이어야 함",
            WalkingMode.IDLE,
            freshVm.uiState.value.walkingMode
        )
        assertFalse(freshVm.uiState.value.isFinished)
    }

    @Test
    fun testLocationTrackingUpdatesProgress() = runBlocking {
        val fakeRepo = FakeRouteRepository()
        val route = fakeRepo.getSampleRoute()
        val fakeLocationSource = FakeLocationSource(gpsEnabled = true, finePermissionGranted = true)

        val vm = NavigationViewModel()
        vm.setRoute(route, fakeLocationSource)

        assertEquals(WalkingMode.WALKING, vm.uiState.value.walkingMode)
        assertEquals(route.totalDistanceMeters, vm.uiState.value.remainingDistanceMeters)

        // 모의 위치 수신
        val sample = LocationSample(
            lat = 35.1595,
            lon = 126.8526,
            accuracyMeters = 5.0f
        )
        vm.processLocationSample(sample)

        assertNotNull(vm.uiState.value.currentManeuver)
        assertFalse(vm.uiState.value.isOffRoute)
    }
}
