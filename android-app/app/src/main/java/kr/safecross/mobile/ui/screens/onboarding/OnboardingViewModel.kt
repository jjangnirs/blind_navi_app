package kr.safecross.mobile.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OnboardingViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<OnboardingEffect>()
    val effects: SharedFlow<OnboardingEffect> = _effects.asSharedFlow()

    fun onScreenStarted() {
        viewModelScope.launch {
            _effects.emit(OnboardingEffect.SpeakAnnouncement(_uiState.value.welcomeMessage))
        }
    }

    fun repeatSpeech() {
        viewModelScope.launch {
            _effects.emit(OnboardingEffect.SpeakAnnouncement(_uiState.value.welcomeMessage))
        }
    }

    fun completeOnboarding() {
        _uiState.update { it.copy(isCompleted = true) }
        viewModelScope.launch {
            _effects.emit(OnboardingEffect.NavigateToDestination)
        }
    }
}
