package kr.safecross.mobile.ui.screens.route

import kr.safecross.mobile.domain.model.PedestrianRoute

data class RouteSummaryUiState(
    val isLoading: Boolean = false,
    val route: PedestrianRoute? = null,
    val originName: String = "현재 위치",
    val destinationName: String = "목적지",
    val disclaimerAcknowledged: Boolean = false,
    val errorMessage: String? = null
)

sealed interface RouteSummaryEffect {
    data class SpeakDisclaimer(val text: String) : RouteSummaryEffect
    data class NavigateToNavigation(val route: PedestrianRoute) : RouteSummaryEffect
    data class ShowToast(val message: String) : RouteSummaryEffect
}
