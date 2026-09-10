package kr.safecross.mobile.ui.screens.onboarding

data class OnboardingUiState(
    val isCompleted: Boolean = false,
    val welcomeMessage: String = (
        "Safe Cross KR에 오신 것을 환영합니다. " +
        "본 앱은 시각장애인과 저시력자를 위한 보행 보조 내비게이션입니다. " +
        "모든 화면은 TalkBack 음성 안내와 고대비 화면으로 최적화되어 있습니다. " +
        "횡단보도 접근 시 음성 및 햅틱으로 위험을 알려드립니다."
    )
)

sealed interface OnboardingEffect {
    data class SpeakAnnouncement(val text: String) : OnboardingEffect
    data object NavigateToDestination : OnboardingEffect
}
