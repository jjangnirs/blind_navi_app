package kr.safecross.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.safecross.mobile.ui.screens.destination.DestinationEffect
import kr.safecross.mobile.ui.screens.destination.DestinationViewModel
import kr.safecross.mobile.ui.screens.destination.defaultDestinations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DestinationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: DestinationViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = DestinationViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onSearchQueryChanged filters destination list`() = runTest(testDispatcher) {
        assertEquals(4, viewModel.uiState.value.destinations.size)

        // "평화공원" 검색
        viewModel.onSearchQueryChanged("평화")
        val state = viewModel.uiState.value
        assertEquals(1, state.destinations.size)
        assertEquals("평화공원", state.destinations[0].name)

        // 빈 검색어로 복귀
        viewModel.onSearchQueryChanged("")
        assertEquals(4, viewModel.uiState.value.destinations.size)
    }

    @Test
    fun `selectDestination emits speech and NavigateToRouteSummary effect`() = runTest(testDispatcher) {
        val effects = mutableListOf<DestinationEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        val target = defaultDestinations[0]
        viewModel.selectDestination(target)
        advanceUntilIdle()

        assertEquals(target, viewModel.uiState.value.selectedDestination)
        assertEquals(2, effects.size)

        // 1. 음성 안내
        assertTrue(effects[0] is DestinationEffect.SpeakAnnouncement)
        val speech = (effects[0] as DestinationEffect.SpeakAnnouncement).text
        assertTrue(speech.contains("광주광역시청이(가) 선택되었습니다"))

        // 2. 경로 요약 화면 이동
        assertTrue(effects[1] is DestinationEffect.NavigateToRouteSummary)
        val nav = effects[1] as DestinationEffect.NavigateToRouteSummary
        assertEquals("광주광역시청", nav.destination.name)

        job.cancel()
    }

    @Test
    fun `openSettings emits NavigateToSettings effect`() = runTest(testDispatcher) {
        val effects = mutableListOf<DestinationEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        viewModel.openSettings()
        advanceUntilIdle()

        assertEquals(1, effects.size)
        assertTrue(effects[0] is DestinationEffect.NavigateToSettings)

        job.cancel()
    }

    @Test
    fun `updateCurrentLocation updates address and sets isGpsReady`() = runTest(testDispatcher) {
        viewModel.updateCurrentLocation(37.5665, 126.9780, "서울특별시 중구 세종대로 110")
        val state = viewModel.uiState.value

        assertTrue(state.isGpsReady)
        assertEquals("서울특별시 중구 세종대로 110", state.currentLocationAddress)
        // 4개 기본 목적지 + 1개 실기기 테스트 목적지 = 5개
        assertEquals(5, state.destinations.size)
        assertTrue(state.destinations[0].name.contains("실기기 테스트"))
    }

    @Test
    fun `onVoiceInputClicked emits StartVoiceInput effect`() = runTest(testDispatcher) {
        val effects = mutableListOf<DestinationEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        viewModel.onVoiceInputClicked()
        advanceUntilIdle()

        assertEquals(1, effects.size)
        assertTrue(effects[0] is DestinationEffect.StartVoiceInput)

        job.cancel()
    }

    @Test
    fun `onVoiceInputResult updates search query and emits announcement`() = runTest(testDispatcher) {
        val effects = mutableListOf<DestinationEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        viewModel.updateCurrentLocation(37.5665, 126.9780, "서울시청")
        viewModel.onVoiceInputResult("강남역")
        advanceUntilIdle()

        assertEquals("강남역", viewModel.uiState.value.searchQuery)
        assertTrue(effects.any { it is DestinationEffect.SpeakAnnouncement && (it as DestinationEffect.SpeakAnnouncement).text.contains("강남역") })

        job.cancel()
    }
}
