package kr.safecross.mobile.ui.screens.destination

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
import kr.safecross.mobile.domain.model.DestinationItem

class DestinationViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DestinationUiState())
    val uiState: StateFlow<DestinationUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<DestinationEffect>()
    val effects: SharedFlow<DestinationEffect> = _effects.asSharedFlow()

    fun onScreenStarted() {
        viewModelScope.launch {
            _effects.emit(
                DestinationEffect.SpeakAnnouncement(
                    "목적지 선택 화면입니다. 검색창을 이용하거나 아래 즐겨찾기 목록에서 목적지를 선택하세요."
                )
            )
        }
    }

    private var currentGpsPoint: kr.safecross.mobile.domain.model.LocationPoint? = null

    fun updateCurrentLocation(
        lat: Double,
        lon: Double,
        address: String? = null,
        signalPercent: Int = 0,
        accuracyMeters: Float = 0f
    ) {
        currentGpsPoint = kr.safecross.mobile.domain.model.LocationPoint(lat, lon)
        val displayAddress = address ?: "GPS (${String.format("%.4f", lat)}, ${String.format("%.4f", lon)})"
        val nearbyTestDest = DestinationItem(
            id = "dest_current_ahead",
            name = "📍 현재 위치 전방 300m 보행로 (실기기 테스트)",
            address = "$displayAddress 기준 전방 약 300m",
            location = kr.safecross.mobile.domain.model.LocationPoint(lat + 0.0027, lon),
            isFavorite = true
        )
        _uiState.update { current ->
            val existing = current.destinations.filterNot { it.id == "dest_current_ahead" }
            current.copy(
                currentLocationAddress = displayAddress,
                isGpsReady = true,
                gpsSignalStrengthPercent = signalPercent,
                gpsAccuracyMeters = accuracyMeters,
                destinations = listOf(nearbyTestDest) + existing
            )
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { current ->
            val baseList = mutableListOf<DestinationItem>()
            currentGpsPoint?.let { pt ->
                val displayAddr = current.currentLocationAddress ?: "현재 위치"
                baseList.add(
                    DestinationItem(
                        id = "dest_current_ahead",
                        name = "📍 현재 위치 전방 300m 보행로 (실기기 테스트)",
                        address = "$displayAddr 기준 전방 약 300m",
                        location = kr.safecross.mobile.domain.model.LocationPoint(pt.lat + 0.0027, pt.lon),
                        isFavorite = true
                    )
                )
            }
            baseList.addAll(defaultDestinations)

            val filtered = if (query.isBlank()) {
                baseList
            } else {
                val matches = baseList.filter {
                    it.name.contains(query, ignoreCase = true) ||
                    it.address.contains(query, ignoreCase = true)
                }
                // 일치하는 항목이 없을 때도 사용자가 음성이나 텍스트로 입력한 장소를 바로 목적지로 갈 수 있도록 가상 목적지 추가
                if (matches.isEmpty() && currentGpsPoint != null) {
                    listOf(
                        DestinationItem(
                            id = "dest_custom_search",
                            name = "📍 $query (현재 위치 기준 검색)",
                            address = "입력한 목적지 ($query) - 현재 위치에서 보행 경로 생성",
                            location = kr.safecross.mobile.domain.model.LocationPoint(
                                (currentGpsPoint?.lat ?: 35.1595) + 0.0020,
                                currentGpsPoint?.lon ?: 126.8526
                            ),
                            isFavorite = true
                        )
                    )
                } else {
                    matches
                }
            }
            current.copy(searchQuery = query, destinations = filtered)
        }
    }

    /**
     * 음성 인식 시작 요청:
     * Activity/Screen에서 시스템 음성인식 다이얼로그(RecognizerIntent)를 실행하도록 이펙트 전달
     */
    fun onVoiceInputClicked() {
        viewModelScope.launch {
            _effects.emit(DestinationEffect.StartVoiceInput)
        }
    }

    /**
     * 음성 인식 완료 콜백 처리:
     * 인식된 단어를 검색창에 반영하고 피드백 낭독
     */
    fun onVoiceInputResult(spokenText: String) {
        val trimmed = spokenText.trim()
        if (trimmed.isNotBlank()) {
            onSearchQueryChanged(trimmed)
            viewModelScope.launch {
                _effects.emit(DestinationEffect.SpeakAnnouncement("음성 인식 결과: ${trimmed}입니다. 아래 목록에서 목적지를 선택하세요."))
            }
        }
    }

    fun selectDestination(item: DestinationItem) {
        _uiState.update { it.copy(selectedDestination = item) }
        viewModelScope.launch {
            _effects.emit(DestinationEffect.SpeakAnnouncement("${item.name}이(가) 선택되었습니다. 경로 요약으로 이동합니다."))
            _effects.emit(DestinationEffect.NavigateToRouteSummary(item))
        }
    }

    fun openSettings() {
        viewModelScope.launch {
            _effects.emit(DestinationEffect.NavigateToSettings)
        }
    }
}
