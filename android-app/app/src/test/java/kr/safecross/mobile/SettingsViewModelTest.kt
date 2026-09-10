package kr.safecross.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.safecross.mobile.ui.screens.settings.SettingsEffect
import kr.safecross.mobile.ui.screens.settings.SettingsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = SettingsViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `toggleHighContrast updates state and emits feedback speech`() = runTest(testDispatcher) {
        val effects = mutableListOf<SettingsEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        assertTrue(viewModel.uiState.value.isHighContrastEnabled)
        viewModel.toggleHighContrast(false)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isHighContrastEnabled)
        assertEquals(1, effects.size)
        assertTrue(effects[0] is SettingsEffect.SpeakAnnouncement)
        assertTrue((effects[0] as SettingsEffect.SpeakAnnouncement).text.contains("고대비 모드가 꺼졌습니다"))

        job.cancel()
    }

    @Test
    fun `setSpeechRate updates speechRate step without sliders`() = runTest(testDispatcher) {
        val effects = mutableListOf<SettingsEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        viewModel.setSpeechRate(1.2f)
        advanceUntilIdle()

        assertEquals(1.2f, viewModel.uiState.value.speechRate, 0.01f)
        assertTrue(effects.any { it is SettingsEffect.SpeakAnnouncement && (it as SettingsEffect.SpeakAnnouncement).text.contains("빠르게") })

        job.cancel()
    }

    @Test
    fun `showDisclaimerDialog toggles dialog visibility state`() = runTest(testDispatcher) {
        assertFalse(viewModel.uiState.value.showDisclaimerDialog)

        viewModel.showDisclaimerDialog(true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showDisclaimerDialog)

        viewModel.showDisclaimerDialog(false)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showDisclaimerDialog)
    }

    @Test
    fun `saveAndClose emits NavigateBack effect`() = runTest(testDispatcher) {
        val effects = mutableListOf<SettingsEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        viewModel.saveAndClose()
        advanceUntilIdle()

        assertTrue(effects.any { it is SettingsEffect.NavigateBack })

        job.cancel()
    }

    @Test
    fun `toggleVibration and setVibrationIntensity update state and emit speech`() = runTest(testDispatcher) {
        val effects = mutableListOf<SettingsEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        assertTrue(viewModel.uiState.value.isVibrationEnabled)
        viewModel.toggleVibration(false)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isVibrationEnabled)
        assertTrue(effects.any { (it as? SettingsEffect.SpeakAnnouncement)?.text?.contains("진동 피드백이 꺼졌습니다") == true })

        viewModel.setVibrationIntensity(kr.safecross.mobile.accessibility.VibrationIntensity.HIGH)
        advanceUntilIdle()
        assertEquals(kr.safecross.mobile.accessibility.VibrationIntensity.HIGH, viewModel.uiState.value.vibrationIntensity)
        assertTrue(effects.any { (it as? SettingsEffect.SpeakAnnouncement)?.text?.contains("강하게") == true })

        job.cancel()
    }
}
