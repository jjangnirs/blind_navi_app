package kr.safecross.mobile.ui.screens.destination

import kr.safecross.mobile.domain.model.DestinationItem
import kr.safecross.mobile.domain.model.LocationPoint

data class DestinationUiState(
    val searchQuery: String = "",
    val isListeningVoice: Boolean = false,
    val destinations: List<DestinationItem> = defaultDestinations,
    val selectedDestination: DestinationItem? = null,
    val currentLocationAddress: String? = null,
    val isGpsReady: Boolean = false,
    val gpsSignalStrengthPercent: Int = 0,
    val gpsAccuracyMeters: Float = 0f,
    val isSearching: Boolean = false,
    val searchError: String? = null
)

val defaultDestinations = listOf(
    DestinationItem(
        id = "dest_1",
        name = "광주광역시청",
        address = "광주광역시 서구 내방로 111",
        location = LocationPoint(35.1595, 126.8526),
        isFavorite = true
    ),
    DestinationItem(
        id = "dest_2",
        name = "평화공원",
        address = "광주광역시 서구 상무평화로 100",
        location = LocationPoint(35.1610, 126.8550),
        isFavorite = true
    ),
    DestinationItem(
        id = "dest_3",
        name = "상무역 4번 출구",
        address = "광주광역시 서구 상무중앙로 7",
        location = LocationPoint(35.1465, 126.8575),
        isFavorite = false
    ),
    DestinationItem(
        id = "dest_4",
        name = "광주세광학교 (시각특수)",
        address = "광주광역시 서구 화정로 123",
        location = LocationPoint(35.1480, 126.8850),
        isFavorite = true
    )
)

sealed interface DestinationEffect {
    data class SpeakAnnouncement(val text: String) : DestinationEffect
    data class NavigateToRouteSummary(val destination: DestinationItem) : DestinationEffect
    data object NavigateToSettings : DestinationEffect
    data object StartVoiceInput : DestinationEffect
}
