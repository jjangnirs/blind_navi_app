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

    fun loadRoute(
        origin: LocationPoint,
        destination: LocationPoint,
        originName: String = "출발지",
        destinationName: String = "목적지",
        excludeStairs: Boolean = true
    ) {
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
                    // 화면 진입/경로 로드 성공 시 접근성 고지문 음성 낭독 요청
                    _effects.emit(RouteSummaryEffect.SpeakDisclaimer(route.disclaimer))
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
     * GPS 위치가 실시간 갱신되어 기존 출발점과 15m 이상 차이나는 경우,
     * 새 위치를 출발점으로 경로를 자동 재탐색하여 변환합니다.
     */
    fun updateOriginIfGpsMoved(
        newGps: LocationPoint,
        newAddress: String? = null
    ) {
        val currentRoute = _uiState.value.route ?: return
        val currentOrigin = currentRoute.fullGeometry.firstOrNull()
            ?: currentRoute.maneuvers.firstOrNull()?.location ?: return

        val distance = calculateDistanceMeters(currentOrigin, newGps)
        if (distance > 15.0 && !_uiState.value.isLoading) {
            val destPoint = currentRoute.fullGeometry.lastOrNull()
                ?: currentRoute.maneuvers.lastOrNull()?.location ?: return
            val destName = _uiState.value.destinationName
            val originName = newAddress ?: "현재 GPS 위치"

            loadRoute(
                origin = newGps,
                destination = destPoint,
                originName = originName,
                destinationName = destName,
                excludeStairs = currentRoute.excludeStairs
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
