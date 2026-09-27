package kr.safecross.mobile.ui.screens.route

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
import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.repository.RouteRepository

class RouteSummaryViewModel(
    private val routeRepository: RouteRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RouteSummaryUiState())
    val uiState: StateFlow<RouteSummaryUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<RouteSummaryEffect>()
    val effects: SharedFlow<RouteSummaryEffect> = _effects.asSharedFlow()

    private var hasSpokenDisclaimer = false
    private var lastRequestedOriginGps: LocationPoint? = null

    fun loadRoute(
        origin: LocationPoint,
        destination: LocationPoint,
        originName: String = "출발지",
        destinationName: String = "목적지",
        excludeStairs: Boolean = true,
        isSilentUpdate: Boolean = false
    ) {
        lastRequestedOriginGps = origin
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = routeRepository.getPedestrianRoute(
                origin = origin,
                destination = destination,
                originName = originName,
                destinationName = destinationName,
                excludeStairs = excludeStairs
            )
            result.fold(
                onSuccess = { route ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            route = route,
                            originName = originName,
                            destinationName = destinationName,
                            disclaimerAcknowledged = false
                        )
                    }
                    // 최초 1회 및 비-무음(사용자 진입) 시에만 접근성 고지문 음성 낭독 요청
                    if (!hasSpokenDisclaimer && !isSilentUpdate) {
                        hasSpokenDisclaimer = true
                        _effects.emit(RouteSummaryEffect.SpeakDisclaimer(route.disclaimer))
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.localizedMessage ?: "경로를 불러오지 못했습니다."
                        )
                    }
                }
            )
        }
    }

    /**
     * GPS 위치가 실시간 갱신되어 기존 요청 출발점과 30m 이상 차이나는 경우,
     * 새 위치를 출발점으로 경로를 자동 재탐색합니다 (무음 모드로 고지문 중복 낭독 방지).
     */
    fun updateOriginIfGpsMoved(
        newGps: LocationPoint,
        newAddress: String? = null
    ) {
        val currentRoute = _uiState.value.route ?: return
        if (_uiState.value.isLoading) return

        // TMAP의 스냅된 첫 정점이 아닌, 실제 이전 요청에 사용된 GPS 좌표와 비교하여 영구 재요청 루프 방지
        val compareOrigin = lastRequestedOriginGps
            ?: currentRoute.fullGeometry.firstOrNull()
            ?: currentRoute.maneuvers.firstOrNull()?.location
            ?: return

        val distance = calculateDistanceMeters(compareOrigin, newGps)
        if (distance > 30.0) {
            val destPoint = currentRoute.fullGeometry.lastOrNull()
                ?: currentRoute.maneuvers.lastOrNull()?.location ?: return
            val destName = _uiState.value.destinationName
            val originName = newAddress ?: "현재 GPS 위치"

            loadRoute(
                origin = newGps,
                destination = destPoint,
                originName = originName,
                destinationName = destName,
                excludeStairs = currentRoute.excludeStairs,
                isSilentUpdate = true
            )
        }
    }

    private fun calculateDistanceMeters(p1: LocationPoint, p2: LocationPoint): Double {
        val r = 6371000.0
        val lat1Rad = Math.toRadians(p1.lat)
        val lat2Rad = Math.toRadians(p2.lat)
        val deltaLat = Math.toRadians(p2.lat - p1.lat)
        val deltaLon = Math.toRadians(p2.lon - p1.lon)

        val a = kotlin.math.sin(deltaLat / 2) * kotlin.math.sin(deltaLat / 2) +
                kotlin.math.cos(lat1Rad) * kotlin.math.cos(lat2Rad) *
                kotlin.math.sin(deltaLon / 2) * kotlin.math.sin(deltaLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }

    /**
     * 사용자가 "주의사항 다시 듣기" 버튼을 누르면 면책 문구를 다시 낭독
     */
    fun repeatDisclaimerSpeech() {
        val disclaimer = _uiState.value.route?.disclaimer
        if (!disclaimer.isNullOrBlank()) {
            viewModelScope.launch {
                _effects.emit(RouteSummaryEffect.SpeakDisclaimer(disclaimer))
            }
        }
    }

    /**
     * 경로 상태 및 고지문 래치를 초기화하여 잔여 데이터나 중복 발화를 방지
     */
    fun clearRoute() {
        hasSpokenDisclaimer = false
        lastRequestedOriginGps = null
        _uiState.update { RouteSummaryUiState() }
    }

    /**
     * 사용자가 주의사항을 확인하고 "확인 후 안내 시작"을 누르면 안내 화면으로 전이
     */
    fun onConfirmAndStartNavigation() {
        val route = _uiState.value.route ?: return
        _uiState.update { it.copy(disclaimerAcknowledged = true) }
        viewModelScope.launch {
            _effects.emit(RouteSummaryEffect.NavigateToNavigation(route))
        }
    }
}
