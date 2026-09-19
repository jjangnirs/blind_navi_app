package kr.safecross.mobile.ui.screens.destination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.safecross.mobile.data.repository.TmapPoiRepository
import kr.safecross.mobile.domain.model.DestinationItem
import kr.safecross.mobile.domain.model.LocationPoint

class DestinationViewModel(
    private val poiRepository: TmapPoiRepository = TmapPoiRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(DestinationUiState())
    val uiState: StateFlow<DestinationUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<DestinationEffect>()
    val effects: SharedFlow<DestinationEffect> = _effects.asSharedFlow()

    private var currentGpsPoint: LocationPoint? = null
    private var searchJob: Job? = null

    fun onScreenStarted() {
        viewModelScope.launch {
            _effects.emit(
                DestinationEffect.SpeakAnnouncement(
                    "목적지 선택 화면입니다. 검색창에 목적지를 입력하거나 음성으로 말씀하세요."
                )
            )
        }
    }

    fun updateCurrentLocation(
        lat: Double,
        lon: Double,
        address: String? = null,
        signalPercent: Int = 0,
        accuracyMeters: Float = 0f
    ) {
        currentGpsPoint = LocationPoint(lat, lon)
        val displayAddress = address ?: "GPS (${String.format("%.4f", lat)}, ${String.format("%.4f", lon)})"
        val nearbyTestDest = DestinationItem(
            id = "dest_current_ahead",
            name = "📍 현재 위치 전방 300m 보행로 (실기기 테스트)",
            address = "$displayAddress 기준 전방 약 300m",
            location = LocationPoint(lat + 0.0027, lon),
            isFavorite = true
        )
        _uiState.update { current ->
            val existing = current.destinations.filterNot { it.id == "dest_current_ahead" }
            val updatedList = if (current.searchQuery.isBlank()) {
                listOf(nearbyTestDest) + existing
            } else {
                current.destinations
            }
            current.copy(
                currentLocationAddress = displayAddress,
                isGpsReady = true,
                gpsSignalStrengthPercent = signalPercent,
                gpsAccuracyMeters = accuracyMeters,
                destinations = updatedList
            )
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    isSearching = false,
                    searchError = null,
                    destinations = buildDefaultList()
                )
            }
            return
        }

        // 1. 로컬 추천/즐겨찾기 목록에서 즉시 필터링 (반응성 0ms)
        val localMatches = buildDefaultList().filter {
            it.name.contains(query, ignoreCase = true) ||
            it.address.contains(query, ignoreCase = true)
        }
        if (localMatches.isNotEmpty()) {
            _uiState.update { it.copy(destinations = localMatches) }
        }

        // 2. 디바운스 후 실제 전국 TMAP POI 검색 실행
        searchJob = viewModelScope.launch(ioDispatcher) {
            delay(300)
            executePoiSearch(query)
        }
    }

    /**
     * 실제 TMAP POI 및 Geocoder 검색 실행
     */
    fun executePoiSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isSearching = true, searchError = null) }

            val result = poiRepository.searchPoi(
                keyword = trimmed,
                centerLat = currentGpsPoint?.lat,
                centerLon = currentGpsPoint?.lon,
                count = 20
            )

            result.fold(
                onSuccess = { poiList ->
                    val finalResults = if (poiList.isNotEmpty()) {
                        poiList
                    } else {
                        // TMAP POI 결과가 없을 때 로컬 기본 리스트에서 텍스트 매칭
                        val localMatches = defaultDestinations.filter {
                            it.name.contains(trimmed, ignoreCase = true) ||
                            it.address.contains(trimmed, ignoreCase = true)
                        }
                        if (localMatches.isNotEmpty()) {
                            localMatches
                        } else if (currentGpsPoint != null) {
                            listOf(
                                DestinationItem(
                                    id = "dest_custom_search",
                                    name = "📍 $trimmed (현재 위치 전방 목적지)",
                                    address = "입력한 목적지 ($trimmed) - 현재 위치 기준 보행로 연결",
                                    location = LocationPoint(
                                        currentGpsPoint!!.lat + 0.0020,
                                        currentGpsPoint!!.lon
                                    ),
                                    isFavorite = false
                                )
                            )
                        } else {
                            emptyList()
                        }
                    }

                    _uiState.update {
                        it.copy(
                            destinations = finalResults,
                            isSearching = false,
                            searchError = if (finalResults.isEmpty()) "'$trimmed' 검색 결과가 없습니다." else null
                        )
                    }

                    if (finalResults.isNotEmpty()) {
                        _effects.emit(
                            DestinationEffect.SpeakAnnouncement(
                                "'$trimmed' 검색 결과 ${finalResults.size}건이 있습니다."
                            )
                        )
                    } else {
                        _effects.emit(
                            DestinationEffect.SpeakAnnouncement(
                                "'$trimmed'에 대한 검색 결과가 없습니다. 다른 검색어를 입력해 보세요."
                            )
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchError = error.localizedMessage ?: "검색 중 오류가 발생했습니다."
                        )
                    }
                }
            )
        }
    }

    private fun buildDefaultList(): List<DestinationItem> {
        val baseList = mutableListOf<DestinationItem>()
        currentGpsPoint?.let { pt ->
            val displayAddr = _uiState.value.currentLocationAddress ?: "현재 위치"
            baseList.add(
                DestinationItem(
                    id = "dest_current_ahead",
                    name = "📍 현재 위치 전방 300m 보행로 (실기기 테스트)",
                    address = "$displayAddr 기준 전방 약 300m",
                    location = LocationPoint(pt.lat + 0.0027, pt.lon),
                    isFavorite = true
                )
            )
        }
        baseList.addAll(defaultDestinations)
        return baseList
    }

    fun onVoiceInputClicked() {
        viewModelScope.launch {
            _effects.emit(DestinationEffect.StartVoiceInput)
        }
    }

    fun onVoiceInputResult(spokenText: String) {
        val trimmed = spokenText.trim()
        if (trimmed.isNotBlank()) {
            _uiState.update { it.copy(searchQuery = trimmed) }
            // 음성 인식 직후 즉시 안내 음성 발화 (SR-F-030)
            viewModelScope.launch {
                _effects.emit(
                    DestinationEffect.SpeakAnnouncement("음성 인식 결과: ${trimmed}입니다. 아래 목록에서 목적지를 선택하세요.")
                )
            }
            executePoiSearch(trimmed)
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
