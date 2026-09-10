package kr.safecross.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.safecross.mobile.ui.screens.onboarding.OnboardingEffect
import kr.safecross.mobile.ui.screens.onboarding.OnboardingViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = OnboardingViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onScreenStarted emits SpeakAnnouncement effect`() = runTest(testDispatcher) {
        val effects = mutableListOf<OnboardingEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        viewModel.onScreenStarted()
        advanceUntilIdle()

        assertEquals(1, effects.size)
        assertTrue(effects[0] is OnboardingEffect.SpeakAnnouncement)
        val text = (effects[0] as OnboardingEffect.SpeakAnnouncement).text
        assertTrue(text.contains("Safe Cross KR에 오신 것을 환영합니다"))
        assertTrue(text.contains("TalkBack"))

        job.cancel()
    }

    @Test
    fun `completeOnboarding updates state and emits NavigateToDestination`() = runTest(testDispatcher) {
        val effects = mutableListOf<OnboardingEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        assertFalse(viewModel.uiState.value.isCompleted)
        viewModel.completeOnboarding()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isCompleted)
        assertEquals(1, effects.size)
        assertTrue(effects[0] is OnboardingEffect.NavigateToDestination)

        job.cancel()
    }
}
