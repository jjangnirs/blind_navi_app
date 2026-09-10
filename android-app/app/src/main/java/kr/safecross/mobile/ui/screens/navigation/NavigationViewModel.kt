package kr.safecross.mobile.ui.screens.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.safecross.mobile.domain.model.PedestrianRoute
import kr.safecross.mobile.domain.model.WalkingMode
import kr.safecross.mobile.guidance.ArbiterAction
import kr.safecross.mobile.guidance.GuidanceArbiter
import kr.safecross.mobile.guidance.GuidanceMessage
import kr.safecross.mobile.guidance.GuidancePriority
import kr.safecross.mobile.guidance.HapticFeedbackType
import kr.safecross.mobile.location.LocationQualityGate
import kr.safecross.mobile.location.LocationSample
import kr.safecross.mobile.location.LocationSource
import kr.safecross.mobile.navigation.crossing.CrossingApproachEngine
import kr.safecross.mobile.navigation.crossing.CrossingFacility
import kr.safecross.mobile.navigation.engine.RouteProgressEngine
import kr.safecross.mobile.service.NavigationForegroundService

/**
 * 실시간 보행 내비게이션 ViewModel (TRD 4.8, SR-F-070~076 준수).
 *
 * GuidanceArbiter를 통해 SAFETY > CROSSING > ROUTE > INFO 우선순위 관리,
 * 긴급 안전 경고 선점 및 큐 제거, 진동 어휘 연동 및 "다시 듣기"를 지원합니다.
 *
 * SR-NF-004 준수: 프로세스 복구 직후 초기 상태는 무조건 IDLE입니다.
 * SR-NF-022, SR-NF-041 준수: 좌표 부동소수점 원문은 내부 로그에 기록하지 않습니다.
 */
class NavigationViewModel(
    private var locationSource: LocationSource? = null,
    private var crossingFacilities: List<CrossingFacility> = emptyList(),
    val guidanceArbiter: GuidanceArbiter = GuidanceArbiter(),
    private var routeRepository: kr.safecross.mobile.domain.repository.RouteRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<NavigationEffect>()
    val effects: SharedFlow<NavigationEffect> = _effects.asSharedFlow()

    private var routeProgressEngine: RouteProgressEngine? = null
    private var crossingApproachEngine: CrossingApproachEngine? = null

    private var locationJob: Job? = null
    private var serviceStopJob: Job? = null

    // 분기점 사전 알림 추적 (30m, 15m 중복 방지)
    private var lastApproachAnnouncedManeuverIndex = -1
    private var lastApproachStage = 0

    init {
        serviceStopJob = viewModelScope.launch {
            NavigationForegroundService.stopEventFlow.collect {
                stopNavigation()
            }
        }
    }

    /**
     * 경로 설정 및 내비게이션 엔진 초기화.
     */
    fun setRoute(
        route: PedestrianRoute,
        source: LocationSource? = locationSource,
        facilities: List<CrossingFacility> = crossingFacilities
    ) {
        this.locationSource = source
        this.crossingFacilities = facilities

        routeProgressEngine = RouteProgressEngine(route)
        crossingApproachEngine = CrossingApproachEngine(facilities)
        guidanceArbiter.stopAll()
        lastApproachAnnouncedManeuverIndex = -1
        lastApproachStage = 0

        _uiState.update {
            it.copy(
                route = route,
                currentManeuverIndex = 0,
                walkingMode = WalkingMode.WALKING,
                distanceToNextManeuverMeters = route.segments.firstOrNull()?.distanceMeters ?: 50,
                distanceAlongRouteMeters = 0,
                remainingDistanceMeters = route.totalDistanceMeters,
                isOffRoute = false,
                isGpsDegraded = false,
                isFinished = false
            )
        }

        speakCurrentStep()
        startLocationTracking()
    }

    /**
     * 실시간 위치 추적 시작.
     */
    fun startLocationTracking() {
        val source = locationSource ?: return
        locationJob?.cancel()
        source.startTracking()

        locationJob = viewModelScope.launch {
            source.locationUpdates.collect { sample ->
                processLocationSample(sample)
            }
        }
    }

    /**
     * 위치 샘플 수신 및 엔진 갱신.
     */
    fun processLocationSample(
        sample: LocationSample,
        currentElapsedNanos: Long = System.nanoTime(),
        currentTimeMs: Long = System.currentTimeMillis()
    ) {
        if (_uiState.value.isFinished) return

        // 1. 경로 진행 엔진 갱신
        val progressEngine = routeProgressEngine
        if (progressEngine != null) {
            val progress = progressEngine.updateProgress(sample)

            val wasOffRoute = _uiState.value.isOffRoute
            if (progress.isOffRoute) {
                if (!wasOffRoute) {
                    viewModelScope.launch {
                        _effects.emit(NavigationEffect.ShowOffRouteAlert("경로를 벗어났습니다. 주변을 확인하세요."))
                    }
                    enqueueGuidance(
                        GuidanceMessage(
                            id = "off_route_${currentTimeMs}",
                            text = "경로를 벗어났습니다. 현재 위치 기준으로 경로를 다시 탐색합니다.",
                            priority = GuidancePriority.SAFETY,
                            category = "safety_off_route",
                            hapticType = HapticFeedbackType.SAFETY_WARNING
                        ),
                        currentTimeMs
                    )
                }
                recalculateRouteFromCurrentLocation(sample)
            } else {
                // 초기 출발 위치와 실제 수신 GPS가 20m 이상 차이나는 경우 즉시 새 출발점 기준 경로로 변환
                val currentRoute = _uiState.value.route
                if (currentRoute != null && _uiState.value.distanceAlongRouteMeters == 0 && _uiState.value.currentManeuverIndex == 0) {
                    val startPoint = currentRoute.fullGeometry.firstOrNull() ?: currentRoute.maneuvers.firstOrNull()?.location
                    if (startPoint != null) {
                        val distToStart = calculateDistanceMeters(startPoint, kr.safecross.mobile.domain.model.LocationPoint(sample.lat, sample.lon))
                        if (distToStart > 20.0) {
                            recalculateRouteFromCurrentLocation(sample)
                        }
                    }
                }
            }

            if (progress.isFinished) {
                _uiState.update {
                    it.copy(
                        isFinished = true,
                        walkingMode = WalkingMode.IDLE,
                        remainingDistanceMeters = 0
                    )
                }
                enqueueGuidance(
                    GuidanceMessage(
                        id = "arrival",
                        text = "목적지에 도착했습니다. 보행 안내가 종료되었습니다.",
                        priority = GuidancePriority.SAFETY,
                        category = "safety_arrival",
                        hapticType = HapticFeedbackType.GREEN_ESTIMATE
                    ),
                    currentTimeMs
                )
                viewModelScope.launch {
                    _effects.emit(NavigationEffect.NavigationFinished)
                }
                stopLocationTracking()
                return
            }

            val prevManeuverIndex = _uiState.value.currentManeuverIndex
            val isManeuverChanged = progress.currentManeuverIndex != prevManeuverIndex

            _uiState.update {
                it.copy(
                    currentManeuverIndex = progress.currentManeuverIndex,
                    distanceAlongRouteMeters = progress.distanceAlongRouteMeters.toInt(),
                    remainingDistanceMeters = progress.remainingDistanceMeters.toInt(),
                    distanceToNextManeuverMeters = progress.distanceToNextManeuverMeters.toInt(),
                    isOffRoute = progress.isOffRoute,
                    gpsSignalStrengthPercent = sample.signalStrengthPercent,
                    gpsAccuracyMeters = sample.accuracyMeters
                )
            }

            // 1-1. 방향 분기점 도달/변경 시 즉시 음성 안내 (SR-F-070, 즉시 발화)
            if (isManeuverChanged) {
                lastApproachAnnouncedManeuverIndex = progress.currentManeuverIndex
                lastApproachStage = 0
                val newManeuver = progress.currentManeuver
                if (newManeuver != null) {
                    val guidanceText = "${newManeuver.instruction}. ${_uiState.value.walkingMode.safetyGuidance}"
                    enqueueGuidance(
                        GuidanceMessage(
                            id = "maneuver_changed_${progress.currentManeuverIndex}_${currentTimeMs}",
                            text = guidanceText,
                            priority = GuidancePriority.ROUTE,
                            category = "maneuver_step",
                            hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                        ),
                        currentTimeMs
                    )
                }
            } else {
                // 1-2. 다음 분기점 사전 접근 안내 (30m 및 15m)
                val nextM = progress.nextManeuver
                val distToNext = progress.distanceToNextManeuverMeters
                if (nextM != null) {
                    val nextAction = kr.safecross.mobile.domain.model.DirectionAction.fromManeuver(nextM)
                    if (distToNext in 18.0..35.0 && (lastApproachAnnouncedManeuverIndex != progress.currentManeuverIndex || lastApproachStage < 30)) {
                        lastApproachAnnouncedManeuverIndex = progress.currentManeuverIndex
                        lastApproachStage = 30
                        val distInt = ((distToNext / 5.0).toInt() * 5).coerceAtLeast(20)
                        enqueueGuidance(
                            GuidanceMessage(
                                id = "approach_30m_${progress.currentManeuverIndex}_${currentTimeMs}",
                                text = "${distInt}미터 앞 ${nextAction.label}입니다.",
                                priority = GuidancePriority.ROUTE,
                                category = "maneuver_approach"
                            ),
                            currentTimeMs
                        )
                    } else if (distToNext in 5.0..18.0 && (lastApproachAnnouncedManeuverIndex != progress.currentManeuverIndex || lastApproachStage < 15)) {
                        lastApproachAnnouncedManeuverIndex = progress.currentManeuverIndex
                        lastApproachStage = 15
                        enqueueGuidance(
                            GuidanceMessage(
                                id = "approach_15m_${progress.currentManeuverIndex}_${currentTimeMs}",
                                text = "잠시 후 ${nextAction.label}입니다. 주변을 살피고 보행하세요.",
                                priority = GuidancePriority.ROUTE,
                                category = "maneuver_approach",
                                hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                            ),
                            currentTimeMs
                        )
                    }
                }
            }
        }

        // 2. 횡단보도 시설 접근 엔진 갱신
        val approachEngine = crossingApproachEngine
        if (approachEngine != null) {
            val alert = approachEngine.evaluateApproach(
                sample = sample,
                currentElapsedNanos = currentElapsedNanos,
                currentTimeMs = currentTimeMs,
                allowMock = sample.isMock
            )

            if (alert != null) {
                if (alert.isDegraded) {
                    _uiState.update {
                        it.copy(
                            isGpsDegraded = true,
                            walkingMode = WalkingMode.WALKING,
                            statusAnnouncement = alert.message
                        )
                    }
                    viewModelScope.launch {
                        _effects.emit(NavigationEffect.ShowGpsDegradedAlert(alert.message))
                    }
                    enqueueGuidance(
                        GuidanceMessage(
                            id = "gps_degraded_${currentTimeMs}",
                            text = alert.message,
                            priority = GuidancePriority.SAFETY,
                            category = "safety_gps",
                            hapticType = HapticFeedbackType.SAFETY_WARNING
                        ),
                        currentTimeMs
                    )
                } else {
                    _uiState.update {
                        it.copy(
                            isGpsDegraded = false,
                            walkingMode = alert.targetMode,
                            statusAnnouncement = alert.message
                        )
                    }
                    if (alert.isSpeechNeeded) {
                        val haptic = if (alert.targetMode == WalkingMode.CROSSING) {
                            HapticFeedbackType.UNKNOWN_CAUTION
                        } else {
                            HapticFeedbackType.UNKNOWN_CAUTION
                        }
                        enqueueGuidance(
                            GuidanceMessage(
                                id = "crossing_${alert.crossingId ?: "unknown"}",
                                text = alert.message,
                                priority = GuidancePriority.CROSSING,
                                category = "crossing_${alert.crossingId ?: "general"}",
                                hapticType = haptic
                            ),
                            currentTimeMs
                        )
                    }
                }
            }
        }
    }

    /**
     * GuidanceArbiter를 통해 우선순위 중재 및 발화.
     */
    fun enqueueGuidance(
        message: GuidanceMessage,
        currentTimeMs: Long = System.currentTimeMillis()
    ) {
        val decision = guidanceArbiter.enqueue(message, currentTimeMs)
        when (decision.action) {
            ArbiterAction.PLAY_IMMEDIATELY, ArbiterAction.PREEMPT_AND_PLAY -> {
                viewModelScope.launch {
                    _effects.emit(
                        NavigationEffect.SpeakGuidance(
                            text = message.text,
                            hapticType = message.hapticType,
                            queueFlush = true
                        )
                    )
                }
            }
            ArbiterAction.QUEUE -> {
                viewModelScope.launch {
                    _effects.emit(
                        NavigationEffect.SpeakGuidance(
                            text = message.text,
                            hapticType = message.hapticType,
                            queueFlush = false
                        )
                    )
                }
            }
            ArbiterAction.SUPPRESSED_COOLDOWN, ArbiterAction.DROPPED_EXPIRED -> {
                // 쿨다운 또는 만료로 차단됨
            }
        }
    }

    /**
     * 현재 스텝 안내 발화 (ROUTE 우선순위).
     */
    fun speakCurrentStep() {
        val state = _uiState.value
        val maneuver = state.currentManeuver
        val guidance = if (maneuver != null) {
            "${state.walkingMode.label}. ${maneuver.instruction}. ${state.walkingMode.safetyGuidance}"
        } else {
            "목적지에 도착했습니다. 안내를 종료합니다."
        }

        enqueueGuidance(
            GuidanceMessage(
                id = "maneuver_${state.currentManeuverIndex}",
                text = guidance,
                priority = GuidancePriority.ROUTE,
                category = "maneuver_step"
            )
        )
    }

    /**
     * "다시 듣기" 버튼 클릭 시 호출 (SR-F-076).
     */
    fun repeatCurrentGuidance() {
        val last = guidanceArbiter.repeatLastGuidance()
        if (last != null) {
            viewModelScope.launch {
                _effects.emit(
                    NavigationEffect.SpeakGuidance(
                        text = last.text,
                        hapticType = last.hapticType,
                        queueFlush = true
                    )
                )
            }
        } else {
            speakCurrentStep()
        }
    }

    /**
     * 신호 추정 상태 안내 (추정과 한계 고지문 필수 포함, SR-F-048).
     */
    fun announceSignalEstimate(state: String) {
        when (state.uppercase()) {
            "GREEN" -> {
                enqueueGuidance(
                    GuidanceMessage(
                        id = "signal_green",
                        text = "녹색으로 추정됩니다. 앱만으로 안전을 보장할 수 없습니다. 좌우 차량을 직접 확인하고 횡단하세요.",
                        priority = GuidancePriority.CROSSING,
                        category = "signal_estimate",
                        hapticType = HapticFeedbackType.GREEN_ESTIMATE
                    )
                )
            }
            "RED" -> {
                enqueueGuidance(
                    GuidanceMessage(
                        id = "signal_red",
                        text = "적색 신호로 추정됩니다. 횡단하지 말고 정지하세요.",
                        priority = GuidancePriority.SAFETY,
                        category = "signal_estimate",
                        hapticType = HapticFeedbackType.RED_STOP
                    )
                )
            }
            else -> {
                enqueueGuidance(
                    GuidanceMessage(
                        id = "signal_unknown",
                        text = "신호 상태를 확인할 수 없습니다. 주변 소리와 유도 인력에 주의하세요.",
                        priority = GuidancePriority.CROSSING,
                        category = "signal_estimate",
                        hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                    )
                )
            }
        }
    }

    /**
     * 다음 안내 지점으로 전이 (수동 시험 및 fallback 호환).
     */
    fun advanceToNextManeuver() {
        val current = _uiState.value
        val nextIdx = current.currentManeuverIndex + 1
        val maxIdx = current.route?.maneuvers?.size ?: 0

        if (nextIdx >= maxIdx) {
            _uiState.update { it.copy(isFinished = true, walkingMode = WalkingMode.IDLE) }
            enqueueGuidance(
                GuidanceMessage(
                    id = "arrival_advance",
                    text = "목적지에 도착했습니다. 보행 안내가 종료되었습니다.",
                    priority = GuidancePriority.SAFETY,
                    category = "safety_arrival",
                    hapticType = HapticFeedbackType.GREEN_ESTIMATE
                )
            )
            viewModelScope.launch {
                _effects.emit(NavigationEffect.NavigationFinished)
            }
            stopLocationTracking()
        } else {
            val nextManeuver = current.route?.maneuvers?.getOrNull(nextIdx)
            val nextMode = if (nextManeuver?.facilityType == "횡단보도") {
                WalkingMode.APPROACHING_CROSSING
            } else if (current.walkingMode == WalkingMode.APPROACHING_CROSSING) {
                WalkingMode.CROSSING
            } else {
                WalkingMode.WALKING
            }

            _uiState.update {
                it.copy(
                    currentManeuverIndex = nextIdx,
                    walkingMode = nextMode,
                    distanceToNextManeuverMeters = if (nextMode == WalkingMode.CROSSING) 20 else 80
                )
            }
            speakCurrentStep()
        }
    }

    fun stopNavigation() {
        _uiState.update { it.copy(isFinished = true, walkingMode = WalkingMode.IDLE) }
        guidanceArbiter.stopAll()
        stopLocationTracking()
        viewModelScope.launch {
            _effects.emit(
                NavigationEffect.SpeakGuidance(
                    text = "보행 안내를 종료합니다.",
                    queueFlush = true
                )
            )
            _effects.emit(NavigationEffect.NavigationFinished)
        }
    }

    private var isRerouting = false
    private var lastRerouteTimeMs = 0L

    /**
     * 현재 GPS 위치를 새로운 출발점으로 하여 목적지까지 경로를 자동 재탐색/변환합니다.
     */
    fun recalculateRouteFromCurrentLocation(sample: LocationSample) {
        val repo = routeRepository ?: return
        val currentRoute = _uiState.value.route ?: return
        val now = System.currentTimeMillis()
        if (isRerouting || (now - lastRerouteTimeMs) < 7000L) return

        val destPoint = currentRoute.fullGeometry.lastOrNull()
            ?: currentRoute.maneuvers.lastOrNull()?.location ?: return
        val destName = currentRoute.maneuvers.lastOrNull()?.instruction?.replace(" 도착", "") ?: "목적지"

        isRerouting = true
        lastRerouteTimeMs = now

        viewModelScope.launch {
            val result = repo.getPedestrianRoute(
                origin = kr.safecross.mobile.domain.model.LocationPoint(sample.lat, sample.lon),
                destination = destPoint,
                originName = "현재 위치",
                destinationName = destName,
                excludeStairs = currentRoute.excludeStairs
            )
            result.fold(
                onSuccess = { newRoute ->
                    routeProgressEngine = RouteProgressEngine(newRoute)
                    _uiState.update {
                        it.copy(
                            route = newRoute,
                            currentManeuverIndex = 0,
                            distanceAlongRouteMeters = 0,
                            remainingDistanceMeters = newRoute.totalDistanceMeters,
                            distanceToNextManeuverMeters = newRoute.segments.firstOrNull()?.distanceMeters ?: 50,
                            isOffRoute = false
                        )
                    }
                    enqueueGuidance(
                        GuidanceMessage(
                            id = "rerouted_${now}",
                            text = "현재 위치를 기준으로 경로를 다시 안내합니다.",
                            priority = GuidancePriority.SAFETY,
                            category = "route_reroute",
                            hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                        ),
                        now
                    )
                },
                onFailure = {}
            )
            isRerouting = false
        }
    }

    private fun calculateDistanceMeters(p1: kr.safecross.mobile.domain.model.LocationPoint, p2: kr.safecross.mobile.domain.model.LocationPoint): Double {
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

    private fun stopLocationTracking() {
        locationJob?.cancel()
        locationJob = null
        locationSource?.stopTracking()
        routeProgressEngine?.reset()
        crossingApproachEngine?.reset()
    }

    override fun onCleared() {
        super.onCleared()
        stopLocationTracking()
        serviceStopJob?.cancel()
        guidanceArbiter.stopAll()
    }
}
