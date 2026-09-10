package kr.safecross.mobile.ui.screens.settings

import kr.safecross.mobile.accessibility.VibrationIntensity

/**
 * 환경설정 UI 상태.
 */
data class SettingsUiState(
    val isHighContrastEnabled: Boolean = true,
    val speechRate: Float = 1.0f,
    val isAcousticSignalAlertEnabled: Boolean = true,
    val isVibrationEnabled: Boolean = true,
    val vibrationIntensity: VibrationIntensity = VibrationIntensity.MEDIUM,
    val showDisclaimerDialog: Boolean = false,
    val appVersion: String = "v0.1.0"
)

sealed interface SettingsEffect {
    data class SpeakAnnouncement(val text: String) : SettingsEffect
    data object NavigateBack : SettingsEffect
}
