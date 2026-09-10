package kr.safecross.mobile.ui.screens.settings

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
import kr.safecross.mobile.accessibility.VibrationIntensity

class SettingsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SettingsEffect>()
    val effects: SharedFlow<SettingsEffect> = _effects.asSharedFlow()

    fun onScreenStarted() {
        viewModelScope.launch {
            _effects.emit(SettingsEffect.SpeakAnnouncement("환경설정 화면입니다. 고대비 테마, 음성 속도, 음향신호기 알림 및 진동 알림을 설정할 수 있습니다."))
        }
    }

    fun toggleHighContrast(enabled: Boolean) {
        _uiState.update { it.copy(isHighContrastEnabled = enabled) }
        val msg = if (enabled) "고대비 모드가 켜졌습니다." else "고대비 모드가 꺼졌습니다."
        viewModelScope.launch {
            _effects.emit(SettingsEffect.SpeakAnnouncement(msg))
        }
    }

    fun setSpeechRate(rate: Float) {
        _uiState.update { it.copy(speechRate = rate) }
        val label = when (rate) {
            0.8f -> "느리게"
            1.2f -> "빠르게"
            else -> "보통"
        }
        viewModelScope.launch {
            _effects.emit(SettingsEffect.SpeakAnnouncement("음성 속도가 $label(으)로 설정되었습니다."))
        }
    }

    fun toggleAcousticSignalAlert(enabled: Boolean) {
        _uiState.update { it.copy(isAcousticSignalAlertEnabled = enabled) }
        val msg = if (enabled) "음향신호기 자동 알림이 켜졌습니다." else "음향신호기 자동 알림이 꺼졌습니다."
        viewModelScope.launch {
            _effects.emit(SettingsEffect.SpeakAnnouncement(msg))
        }
    }

    fun toggleVibration(enabled: Boolean) {
        _uiState.update { it.copy(isVibrationEnabled = enabled) }
        val msg = if (enabled) "진동 피드백이 켜졌습니다." else "진동 피드백이 꺼졌습니다."
        viewModelScope.launch {
            _effects.emit(SettingsEffect.SpeakAnnouncement(msg))
        }
    }

    fun setVibrationIntensity(intensity: VibrationIntensity) {
        _uiState.update { it.copy(vibrationIntensity = intensity) }
        viewModelScope.launch {
            _effects.emit(SettingsEffect.SpeakAnnouncement("진동 세기가 ${intensity.label}(으)로 설정되었습니다."))
        }
    }

    fun showDisclaimerDialog(show: Boolean) {
        _uiState.update { it.copy(showDisclaimerDialog = show) }
        if (show) {
            viewModelScope.launch {
                _effects.emit(SettingsEffect.SpeakAnnouncement("법적 고지 및 면책 전문 대화상자가 열렸습니다."))
            }
        }
    }

    fun saveAndClose() {
        viewModelScope.launch {
            _effects.emit(SettingsEffect.SpeakAnnouncement("설정이 저장되었습니다."))
            _effects.emit(SettingsEffect.NavigateBack)
        }
    }
}
