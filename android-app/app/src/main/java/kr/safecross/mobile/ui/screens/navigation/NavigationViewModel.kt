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
import kr.safecross.mobile.domain.model.LocationPoint
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
import kr.safecross.mobile.guidance.BlindGuidanceFormatter
import kr.safecross.mobile.navigation.engine.GeoMath
import kr.safecross.mobile.perception.DevicePose
import kr.safecross.mobile.sensor.DevicePoseTracker
import kr.safecross.mobile.service.NavigationForegroundService
import kr.safecross.mobile.navigation.NavigationFlightRecorder
import java.util.Locale

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
    private var routeRepository: kr.safecross.mobile.domain.repository.RouteRepository? = null,
    private var devicePoseTracker: DevicePoseTracker? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<NavigationEffect>()
    val effects: SharedFlow<NavigationEffect> = _effects.asSharedFlow()

    private var routeProgressEngine: RouteProgressEngine? = null
    private var crossingApproachEngine: CrossingApproachEngine? = null

    private var locationJob: Job? = null
    private var poseJob: Job? = null
    private var serviceStopJob: Job? = null
    private var lastPoseLogTimeMs = 0L

    // 분기점 사전 알림 추적 (30m, 15m 중복 방지)
    private var lastApproachAnnouncedManeuverIndex = -1
    private var lastApproachStage = 0
    private var lastAlignmentTimeMs = 0L

    // 헤딩 및 GPS 보행 융합 추적 (보행 시 팔 흔들림 억제 및 UI 스로틀링)
    private var lastValidGpsBearing: Float? = null
    private var lastSpeedMps: Float = 0f
    private var lastHeadingUiUpdateTimeMs = 0L
    private var lastUiHeading = 0f

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
        facilities: List<CrossingFacility> = crossingFacilities,
        poseTracker: DevicePoseTracker? = devicePoseTracker
    ) {
        this.locationSource = source
        this.crossingFacilities = facilities
        this.devicePoseTracker = poseTracker

        routeProgressEngine = RouteProgressEngine(route, offRouteThresholdMeters = 35.0, minConsecutiveOffRoute = 4)
        crossingApproachEngine = CrossingApproachEngine(facilities)
        guidanceArbiter.stopAll()
        lastApproachAnnouncedManeuverIndex = -1
        lastApproachStage = 0
        lastAlignmentTimeMs = 0L
        hasCalibratedInitialStart = false

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
                isFinished = false,
                isOrientationAligned = true,
                alignmentPromptMessage = "",
                currentLocation = route.fullGeometry.firstOrNull() ?: route.maneuvers.firstOrNull()?.location
            )
        }

        val originName = route.maneuvers.firstOrNull()?.instruction ?: "출발지"
        val destinationName = route.maneuvers.lastOrNull()?.instruction ?: "목적지"
        NavigationFlightRecorder.recordRouteStart(route, originName, destinationName)

        speakCurrentStep()
        startLocationTracking()
        startPoseTracking()
    }

    /**
     * 기기 자세/나침반 센서 추적기 설정 및 가동.
     */
    fun setDevicePoseTracker(tracker: DevicePoseTracker) {
        this.devicePoseTracker = tracker
        startPoseTracking()
    }

    /**
     * 실시간 헤딩 및 자세 추적 시작.
     */
    fun startPoseTracking() {
        val tracker = devicePoseTracker ?: return
        poseJob?.cancel()
        tracker.startTracking()
        poseJob = viewModelScope.launch {
            tracker.currentPose.collect { pose ->
                processDevicePose(pose)
            }
        }
    }

    /**
     * 기기 헤딩 자세 수신 및 경로 정대(Orientation Alignment) 분석.
     */
    fun processDevicePose(pose: DevicePose, currentTimeMs: Long = System.currentTimeMillis()) {
        val heading = pose.headingDegrees

        // 1. 보행 중(속도 >= 0.65m/s) GPS 이동 궤적(Course) 65% + 나침반 35% 상보 필터 융합 (팔 흔들림/발걸음 진자 운동 억제)
        val gpsBrg = lastValidGpsBearing
        val effectiveHeading: Float = if (lastSpeedMps >= 0.65f && gpsBrg != null) {
            val deltaGps = ((gpsBrg - heading + 540.0) % 360.0) - 180.0
            if (kotlin.math.abs(deltaGps) <= 80.0) {
                ((heading + (deltaGps * 0.65) + 360.0) % 360.0).toFloat()
            } else {
                heading
            }
        } else {
            heading
        }

        // 2. UI 갱신 주기 스로틀링 (초당 약 11회/90ms, 8도 이상 급격한 회전은 즉각 반영)
        val deltaUi = kotlin.math.abs(((effectiveHeading - lastUiHeading + 540.0) % 360.0) - 180.0)
        if (currentTimeMs - lastHeadingUiUpdateTimeMs >= 90L || deltaUi >= 8.0) {
            lastHeadingUiUpdateTimeMs = currentTimeMs
            lastUiHeading = effectiveHeading
            _uiState.update { it.copy(currentHeadingDegrees = effectiveHeading) }
        }

        val route = _uiState.value.route ?: return
        if (_uiState.value.isFinished) return

        val targetBearing = calculateTargetBearing() ?: return
        val orientationPrompt = BlindGuidanceFormatter.evaluateOrientation(
            currentHeadingDeg = effectiveHeading.toDouble(),
            targetBearingDeg = targetBearing
        )

        val wasAligned = _uiState.value.isOrientationAligned
        _uiState.update {
            it.copy(
                isOrientationAligned = orientationPrompt.isAligned,
                alignmentPromptMessage = orientationPrompt.message
            )
        }

        // 경로 분석 비행 기록기 기기 헤딩/자세 기록 (1초 주기 또는 정대 상태 변화 시)
        if (Math.abs(currentTimeMs - lastPoseLogTimeMs) >= 1000L || orientationPrompt.isAligned != wasAligned) {
            lastPoseLogTimeMs = currentTimeMs
            NavigationFlightRecorder.recordPose(
                headingDeg = effectiveHeading,
                pitchDeg = pose.pitchDegrees,
                targetBearingDeg = targetBearing,
                isAligned = orientationPrompt.isAligned,
                promptText = orientationPrompt.message
            )
        }

        // 제자리 회전 중 올바른 진행 방향으로 정대 완료 시 (wasAligned = false -> isAligned = true)
        if (orientationPrompt.isAligned && !wasAligned && (currentTimeMs - lastAlignmentTimeMs) > 6000L) {
            lastAlignmentTimeMs = currentTimeMs
            enqueueGuidance(
                GuidanceMessage(
                    id = "orientation_aligned_${currentTimeMs}",
                    text = orientationPrompt.message,
                    priority = GuidancePriority.ROUTE,
                    category = "orientation_alignment",
                    hapticType = HapticFeedbackType.ORIENTATION_ALIGNED
                ),
                currentTimeMs
            )
        }
    }

    /**
     * 현재 스텝에서 향해야 할 목표 지점의 방위각(Bearing)을 산출합니다.
     */
    fun calculateTargetBearing(): Double? {
        val state = _uiState.value
        val route = state.route ?: return null
        val currentM = state.currentManeuver
        val nextM = state.nextManeuver

        if (currentM != null && nextM != null) {
            return GeoMath.initialBearingDegrees(
                lat1 = currentM.location.lat,
                lon1 = currentM.location.lon,
                lat2 = nextM.location.lat,
                lon2 = nextM.location.lon
            )
        }

        val seg = route.segments.getOrNull(state.currentManeuverIndex)
        if (seg != null && seg.geometry.size >= 2) {
            val p1 = seg.geometry.first()
            val p2 = seg.geometry.last()
            return GeoMath.initialBearingDegrees(p1.lat, p1.lon, p2.lat, p2.lon)
        }

        return null
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

        // GPS 수신 원격 기록 및 이동 속도/방위각 갱신
        if (sample.speedMps != null) {
            lastSpeedMps = sample.speedMps
        }
        if (sample.bearingDegrees != null && sample.speedMps != null && sample.speedMps >= 0.75f && sample.accuracyMeters <= 25.0f) {
            lastValidGpsBearing = sample.bearingDegrees
        }

        NavigationFlightRecorder.recordGps(
            lat = sample.lat,
            lon = sample.lon,
            accuracyMeters = sample.accuracyMeters,
            speedMps = sample.speedMps,
            bearingDegrees = sample.bearingDegrees,
            signalPercent = sample.signalStrengthPercent,
            satelliteCount = sample.satelliteCount
        )

        // 1. 경로 진행 엔진 갱신
        val progressEngine = routeProgressEngine
        if (progressEngine != null) {
            val progress = progressEngine.updateProgress(sample)

            // 진행 상태 및 크로스트랙 오차 기록
            NavigationFlightRecorder.recordProgress(
                stepIndex = progress.currentManeuverIndex,
                totalSteps = _uiState.value.route?.maneuvers?.size ?: 0,
                distanceAlongMeters = progress.distanceAlongRouteMeters,
                remainingDistanceMeters = progress.remainingDistanceMeters,
                distanceToNextManeuverMeters = progress.distanceToNextManeuverMeters,
                crossTrackErrorMeters = progress.crossTrackErrorMeters,
                isOffRoute = progress.isOffRoute,
                offRouteCount = progress.offRouteConsecutiveCount
            )

            val wasOffRoute = _uiState.value.isOffRoute
            if (progress.isOffRoute) {
                if (!wasOffRoute) {
                    val cteStr = String.format(Locale.US, "%.1f", progress.crossTrackErrorMeters)
                    NavigationFlightRecorder.recordRerouteTrigger(
                        reason = "OFF_ROUTE",
                        detail = "CTE=${cteStr}m, cnt=${progress.offRouteConsecutiveCount}, acc=${sample.accuracyMeters}m"
                    )
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
                // 초기 출발 위치와 실제 수신 GPS가 25m 이상 차이나는 경우 최초 1회에 한해 새 출발점 기준 경로로 변환 (보행 시작 후에는 반복 재탐색 차단)
                if (!hasCalibratedInitialStart) {
                    val currentRoute = _uiState.value.route
                    if (currentRoute != null && _uiState.value.distanceAlongRouteMeters < 5 && _uiState.value.currentManeuverIndex == 0) {
                        val startPoint = currentRoute.fullGeometry.firstOrNull() ?: currentRoute.maneuvers.firstOrNull()?.location
                        if (startPoint != null) {
                            val distToStart = calculateDistanceMeters(startPoint, kr.safecross.mobile.domain.model.LocationPoint(sample.lat, sample.lon))
                            if (distToStart > 25.0) {
                                hasCalibratedInitialStart = true
                                val dStr = String.format(Locale.US, "%.1f", distToStart)
                                NavigationFlightRecorder.recordRerouteTrigger(
                                    reason = "INITIAL_DEPARTURE_CALIBRATION",
                                    detail = "distToStart=${dStr}m > 25.0m"
                                )
                                recalculateRouteFromCurrentLocation(sample)
                            }
                        }
                    }
                    if (progress.distanceAlongRouteMeters >= 10.0 || progress.currentManeuverIndex > 0) {
                        hasCalibratedInitialStart = true
                    }
                }
            }

            if (progress.isFinished) {
                NavigationFlightRecorder.recordFinish(progress.distanceAlongRouteMeters)
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
                    gpsAccuracyMeters = sample.accuracyMeters,
                    currentLocation = LocationPoint(sample.lat, sample.lon)
                )
            }

            // 1-1. 방향 분기점 도달/변경 시 즉시 음성 안내 (SR-F-070, 즉시 발화)
            if (isManeuverChanged) {
                lastApproachAnnouncedManeuverIndex = progress.currentManeuverIndex
                lastApproachStage = 0
                val newManeuver = progress.currentManeuver
                if (newManeuver != null) {
                    NavigationFlightRecorder.recordStepChange(
                        fromStep = prevManeuverIndex,
                        toStep = progress.currentManeuverIndex,
                        instruction = newManeuver.instruction
                    )
                    val cleaned = BlindGuidanceFormatter.cleanInstruction(newManeuver.instruction)
                    val guidanceText = "${cleaned}. ${_uiState.value.walkingMode.safetyGuidance}"
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
                // 1-2. 다음 분기점 사전 접근 안내 (30m 및 15m: 시계방향 및 걸음수 포맷터 적용)
                val nextM = progress.nextManeuver
                val distToNext = progress.distanceToNextManeuverMeters
                if (nextM != null) {
                    val nextAction = kr.safecross.mobile.domain.model.DirectionAction.fromManeuver(nextM)
                    val targetBearing = calculateTargetBearing()
                    val currentHeading = _uiState.value.currentHeadingDegrees.toDouble()
                    val relativeBearing = if (targetBearing != null) {
                        ((targetBearing - currentHeading + 540.0) % 360.0) - 180.0
                    } else null

                    if (distToNext in 18.0..35.0 && (lastApproachAnnouncedManeuverIndex != progress.currentManeuverIndex || lastApproachStage < 30)) {
                        lastApproachAnnouncedManeuverIndex = progress.currentManeuverIndex
                        lastApproachStage = 30
                        val text = BlindGuidanceFormatter.formatApproachGuidance(
                            action = nextAction,
                            distanceMeters = distToNext,
                            relativeBearingDeg = relativeBearing
                        )
                        NavigationFlightRecorder.recordApproach(30, distToNext, text)
                        enqueueGuidance(
                            GuidanceMessage(
                                id = "approach_30m_${progress.currentManeuverIndex}_${currentTimeMs}",
                                text = text,
                                priority = GuidancePriority.ROUTE,
                                category = "maneuver_approach"
                            ),
                            currentTimeMs
                        )
                    } else if (distToNext in 5.0..18.0 && (lastApproachAnnouncedManeuverIndex != progress.currentManeuverIndex || lastApproachStage < 15)) {
                        lastApproachAnnouncedManeuverIndex = progress.currentManeuverIndex
                        lastApproachStage = 15
                        val text = BlindGuidanceFormatter.formatApproachGuidance(
                            action = nextAction,
                            distanceMeters = distToNext,
                            relativeBearingDeg = relativeBearing
                        )
                        NavigationFlightRecorder.recordApproach(15, distToNext, text)
                        enqueueGuidance(
                            GuidanceMessage(
                                id = "approach_15m_${progress.currentManeuverIndex}_${currentTimeMs}",
                                text = text,
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
                    // 횡단보도 정지 준비 또는 진입 상태 도달 시 카메라 보조 화면 자동 트리거
                    if (alert.targetMode == WalkingMode.CROSSING || alert.targetMode == WalkingMode.APPROACHING_CROSSING) {
                        viewModelScope.launch {
                            _effects.emit(NavigationEffect.TriggerCrossingAssist)
                        }
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
        NavigationFlightRecorder.recordGuidance(message.category, message.priority.name, message.text)
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
            val cleaned = BlindGuidanceFormatter.cleanInstruction(maneuver.instruction)
            "${state.walkingMode.label}. $cleaned. ${state.walkingMode.safetyGuidance}"
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
                    distanceToNextManeuverMeters = if (nextMode == WalkingMode.CROSSING) 20 else 80,
                    currentLocation = nextManeuver?.location ?: it.currentLocation
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
    private var hasCalibratedInitialStart = false

    /**
     * 현재 GPS 위치를 새로운 출발점으로 하여 목적지까지 경로를 자동 재탐색/변환합니다.
     */
    fun recalculateRouteFromCurrentLocation(sample: LocationSample) {
        val repo = routeRepository ?: return
        val currentRoute = _uiState.value.route ?: return
        val now = System.currentTimeMillis()
        if (isRerouting || (now - lastRerouteTimeMs) < 12000L) return

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
                    NavigationFlightRecorder.recordRerouteSuccess(newRoute.totalDistanceMeters, newRoute.maneuvers.size)
                    routeProgressEngine = RouteProgressEngine(newRoute, offRouteThresholdMeters = 35.0, minConsecutiveOffRoute = 4)
                    hasCalibratedInitialStart = true
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
                onFailure = { error ->
                    NavigationFlightRecorder.recordRerouteFailure(error.message ?: "network_or_api_error")
                }
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
        poseJob?.cancel()
        poseJob = null
        locationSource?.stopTracking()
        devicePoseTracker?.stopTracking()
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
