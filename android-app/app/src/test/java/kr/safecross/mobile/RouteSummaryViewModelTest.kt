package kr.safecross.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.safecross.mobile.data.repository.FakeRouteRepository
import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.model.ROUTE_DISCLAIMER_TEXT
import kr.safecross.mobile.ui.screens.route.RouteSummaryEffect
import kr.safecross.mobile.ui.screens.route.RouteSummaryViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteSummaryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeRouteRepository
    private lateinit var viewModel: RouteSummaryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeRouteRepository()
        viewModel = RouteSummaryViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadRoute success populates route and emits SpeakDisclaimer effect`() = runTest(testDispatcher) {
        val effects = mutableListOf<RouteSummaryEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        viewModel.loadRoute(
            origin = LocationPoint(35.1595, 126.8526),
            destination = LocationPoint(35.1610, 126.8550),
            originName = "광주광역시청",
            destinationName = "평화공원",
            excludeStairs = true
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.route)
        assertEquals(500, state.route?.totalDistanceMeters)
        assertTrue(state.route?.excludeStairs == true)
        assertEquals(ROUTE_DISCLAIMER_TEXT, state.route?.disclaimer)
        assertFalse(state.disclaimerAcknowledged)

        // 음성 고지 effect 발생 확인
        assertTrue(effects.isNotEmpty())
        val firstEffect = effects.first() as RouteSummaryEffect.SpeakDisclaimer
        assertEquals(ROUTE_DISCLAIMER_TEXT, firstEffect.text)
        assertTrue(firstEffect.text.contains("안전 경로가 아닙니다"))
        assertTrue(firstEffect.text.contains("음향신호기"))
        assertTrue(firstEffect.text.contains("점자블록"))

        job.cancel()
    }

    @Test
    fun `repeatDisclaimerSpeech emits SpeakDisclaimer effect again`() = runTest(testDispatcher) {
        val effects = mutableListOf<RouteSummaryEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        viewModel.loadRoute(
            origin = LocationPoint(35.1595, 126.8526),
            destination = LocationPoint(35.1610, 126.8550)
        )
        advanceUntilIdle()

        effects.clear()
        viewModel.repeatDisclaimerSpeech()
        advanceUntilIdle()

        assertEquals(1, effects.size)
        assertTrue(effects[0] is RouteSummaryEffect.SpeakDisclaimer)
        assertEquals(ROUTE_DISCLAIMER_TEXT, (effects[0] as RouteSummaryEffect.SpeakDisclaimer).text)

        job.cancel()
    }

    @Test
    fun `onConfirmAndStartNavigation sets acknowledged and emits NavigateToNavigation`() = runTest(testDispatcher) {
        val effects = mutableListOf<RouteSummaryEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        viewModel.loadRoute(
            origin = LocationPoint(35.1595, 126.8526),
            destination = LocationPoint(35.1610, 126.8550)
        )
        advanceUntilIdle()

        viewModel.onConfirmAndStartNavigation()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.disclaimerAcknowledged)
        val navEffect = effects.last() as RouteSummaryEffect.NavigateToNavigation
        assertEquals(500, navEffect.route.totalDistanceMeters)

        job.cancel()
    }

    @Test
    fun `loadRoute failure updates error state`() = runTest(testDispatcher) {
        repository.shouldFail = true

        viewModel.loadRoute(
            origin = LocationPoint(35.1595, 126.8526),
            destination = LocationPoint(35.1610, 126.8550)
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.errorMessage)
    }
}
