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

    // 횡단보도 접근 시 카메라 신호 확인 화면 자동 전환 (경로상 15m / 30m 내 정지)
    private var crossingAutoTrigger: kr.safecross.mobile.navigation.crossing.CrossingAutoTriggerPolicy? = null
    // 이미 자동 전환한 횡단보도 위치 (재탐색으로 경로가 바뀌어도 같은 횡단보도에서 다시 전환하지 않음)
    private val autoTriggeredCrosswalkLocations = mutableListOf<LocationPoint>()

    // 현재 경로의 모든 횡단보도 (경로상 시작 위치, 건너편 끝 위치)
    private data class CrosswalkSpan(val maneuverIndex: Int, val startAlong: Double, val endAlong: Double)
    private var routeCrosswalkSpans: List<CrosswalkSpan> = emptyList()

    // 카메라 신호 확인 화면이 열려 있는 동안 건너는 중인 횡단보도 감시
    private data class CrossingWatch(
        val span: CrosswalkSpan,
        val startedAtMs: Long,
        val startAlong: Double = 0.0,
        var isGreenConfirmed: Boolean = false,
        var completeCount: Int = 0
    )
    private var crossingWatch: CrossingWatch? = null

    // 마지막으로 setRoute()로 요청된 원본 경로 (화면 재진입 시 재설정 방지용)
    private var lastRequestedRoute: PedestrianRoute? = null

    private val _crossingCompleted = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * 횡단 완료 이벤트 (안내 문구). 카메라 신호 확인 화면이 떠 있는 동안에는 길안내 화면의 effects가
     * 수집되지 않으므로, 카메라 화면 쪽에서 이 흐름을 받아 음성 안내 후 길안내 화면으로 복귀한다.
     */
    val crossingCompleted: kotlinx.coroutines.flow.SharedFlow<String> = _crossingCompleted

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

    // 현재 위치 기준 전방 경로 지점(look-ahead) 방위각 (GPS 흔들림 평활화)
    private var smoothedGuidanceBearing: Double? = null
    private var lastAlongTrackMeters: Double? = null

    // 방향 가이드 화살표 상대 각도 평활화
    private var smoothedRelativeDirection: Double? = null
    private var lastRelativeUiValue: Float? = null
    private var lastRelativeUiTimeMs = 0L

    // 지도 회전 전용 헤딩 평활화 (실측 10/01: 나침반 1초 변화량 p90 13도, p95 23도 → 지도가 계속 흔들림)
    // ARCore Geospatial(VPS) 방향 (ADR-0037). 실측 10/02: 나침반이 VPS 대비 13~30° 틀어짐
    private var latestVps: kr.safecross.mobile.sensor.VpsHeadingSample? = null
    private var latestVpsReceivedMs = 0L
    // VPS를 놓친 뒤(휴대폰을 바닥으로 숙임 등) 나침반에 적용할 보정값 = VPS 방향 - 나침반 방향
    private var compassBiasDegrees: Double? = null
    private var compassBiasMeasuredMs = 0L
    private var compassBiasLocation: LocationPoint? = null
    private var lastHeadingLogMs = 0L

    private var smoothedMapHeading: Double? = null
    private var lastMapHeadingUiValue: Float? = null
    private var lastMapHeadingUiTimeMs = 0L
    private var lastPoseTimeMs = 0L
    private var lastTurnHapticMs = 0L

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
        lastRequestedRoute = route
        crossingWatch = null

        routeProgressEngine = RouteProgressEngine(route, offRouteThresholdMeters = 35.0, minConsecutiveOffRoute = 4)
        crossingApproachEngine = CrossingApproachEngine(facilities)
        autoTriggeredCrosswalkLocations.clear()
        crossingAutoTrigger = buildCrossingAutoTrigger(route, routeProgressEngine!!)
        guidanceArbiter.stopAll()
        lastApproachAnnouncedManeuverIndex = -1
        lastApproachStage = 0
        lastAlignmentTimeMs = 0L
        hasCalibratedInitialStart = false
        smoothedGuidanceBearing = null
        lastAlongTrackMeters = null
        smoothedRelativeDirection = null

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
     * 화면(재)진입 시 호출: 같은 원본 경로로 이미 안내 중이면 다시 초기화하지 않는다.
     * (카메라 신호 확인 화면에서 돌아올 때 진행 상태·재탐색 경로·자동 전환 이력이 초기화되던 문제 방지)
     */
    fun ensureRoute(route: PedestrianRoute) {
        if (route == lastRequestedRoute && _uiState.value.route != null && !_uiState.value.isFinished) return
        setRoute(route)
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
    /** ARCore Geospatial 공급자로부터 VPS 방향 수신 */
    fun onVpsHeading(sample: kr.safecross.mobile.sensor.VpsHeadingSample?, receivedAtMs: Long = System.currentTimeMillis()) {
        latestVps = sample
        latestVpsReceivedMs = receivedAtMs
    }

    /**
     * 방향 출처 결정 (ADR-0037):
     * 1) VPS 방향 오차 ≤ 10°이고 1.5초 이내 → VPS 방향 (휴대폰을 눕혀 든 상태면 나침반 보정값도 갱신)
     * 2) VPS를 놓쳤지만 60초 이내·30m 이내에서 잰 보정값이 있으면 → 나침반 + 보정값
     * 3) 그 외 → 나침반 (기존 GPS 진행 방향 융합 적용)
     */
    private fun resolveHeading(compassHeading: Float, currentTimeMs: Long): Pair<Float, HeadingSource> {
        val vps = latestVps
        val isVpsUsable = vps != null &&
                currentTimeMs - latestVpsReceivedMs <= VPS_MAX_AGE_MS &&
                vps.yawAccuracyDegrees <= VPS_MAX_YAW_ACCURACY_DEG
        if (isVpsUsable) {
            // 나침반(기기 윗변 방위각)과 같은 축일 때만 보정값을 측정
            if (vps!!.isTopAxis) {
                compassBiasDegrees = wrapDegrees(vps.headingDegrees - compassHeading)
                compassBiasMeasuredMs = currentTimeMs
                compassBiasLocation = _uiState.value.currentLocation
            }
            return vps.headingDegrees.toFloat() to HeadingSource.VPS
        }

        val bias = compassBiasDegrees
        if (bias != null && currentTimeMs - compassBiasMeasuredMs <= COMPASS_BIAS_MAX_AGE_MS) {
            val here = _uiState.value.currentLocation
            val from = compassBiasLocation
            val movedMeters = if (here != null && from != null) calculateDistanceMeters(from, here) else 0.0
            if (movedMeters <= COMPASS_BIAS_MAX_DISTANCE_M) {
                val corrected = ((compassHeading + bias) % 360.0 + 360.0) % 360.0
                return corrected.toFloat() to HeadingSource.VPS_CORRECTED_COMPASS
            }
        }
        return compassHeading to HeadingSource.COMPASS
    }

    fun processDevicePose(pose: DevicePose, currentTimeMs: Long = System.currentTimeMillis()) {
        val (heading, headingSource) = resolveHeading(pose.headingDegrees, currentTimeMs)
        if (headingSource != _uiState.value.headingSource) {
            _uiState.update { it.copy(headingSource = headingSource) }
        }
        if (currentTimeMs - lastHeadingLogMs >= 1000L) {
            lastHeadingLogMs = currentTimeMs
            NavigationFlightRecorder.record(
                "HEADING",
                "source=$headingSource used=${"%.1f".format(heading)} compass=${"%.1f".format(pose.headingDegrees)} " +
                        "vpsYawAcc=${latestVps?.yawAccuracyDegrees?.let { "%.1f".format(it) } ?: "-"} bias=${compassBiasDegrees?.let { "%.1f".format(it) } ?: "-"}"
            )
        }

        // 1. 보행 중(속도 >= 0.65m/s) GPS 이동 궤적(Course) 65% + 나침반 35% 상보 필터 융합 (팔 흔들림/발걸음 진자 운동 억제)
        val gpsBrg = lastValidGpsBearing
        val effectiveHeading: Float = if (headingSource == HeadingSource.VPS) {
            // VPS 방향은 GPS 진행 방향보다 정확하므로 융합하지 않는다
            heading
        } else if (lastSpeedMps >= 0.65f && gpsBrg != null) {
            val deltaGps = ((gpsBrg - heading + 540.0) % 360.0) - 180.0
            // 급격한 회전(50도 초과)이나 제자리 정지 시에는 나침반을 100% 신뢰하여 지도 흔들림 방지
            if (kotlin.math.abs(deltaGps) <= 50.0) {
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

        val dtSec = if (lastPoseTimeMs == 0L) 0.1 else ((currentTimeMs - lastPoseTimeMs) / 1000.0).coerceIn(0.0, 1.0)
        lastPoseTimeMs = currentTimeMs

        // 2-1. 지도 회전 전용 헤딩: 시간상수 0.8초 저역 통과 + 5도/0.3초 이상 변화 시에만 지도에 반영
        val mapHeading = blendAngle(smoothedMapHeading, effectiveHeading.toDouble(), smoothingAlpha(dtSec, MAP_HEADING_TAU_SEC))
        smoothedMapHeading = mapHeading
        val lastMap = lastMapHeadingUiValue
        val mapDelta = if (lastMap == null) 360.0 else kotlin.math.abs(wrapDegrees(mapHeading - lastMap))
        if (mapDelta >= 5.0 && currentTimeMs - lastMapHeadingUiTimeMs >= 300L) {
            lastMapHeadingUiValue = mapHeading.toFloat()
            lastMapHeadingUiTimeMs = currentTimeMs
            _uiState.update { it.copy(mapHeadingDegrees = mapHeading.toFloat()) }
        }

        val route = _uiState.value.route ?: return
        if (_uiState.value.isFinished) return

        val targetBearing = calculateTargetBearing() ?: return

        // 2-2. 방향 가이드 화살표: 몸 정면 기준 가야 할 방향 (시간상수 0.3초 평활화, 3도 이상 변화 시 갱신)
        val rawRelative = wrapDegrees(targetBearing - effectiveHeading)
        val prevRelative = smoothedRelativeDirection
        val relative = if (prevRelative == null) {
            rawRelative
        } else {
            wrapDegrees(prevRelative + smoothingAlpha(dtSec, ARROW_TAU_SEC) * wrapDegrees(rawRelative - prevRelative))
        }
        smoothedRelativeDirection = relative
        val lastRel = lastRelativeUiValue
        if (lastRel == null || kotlin.math.abs(wrapDegrees(relative - lastRel)) >= 3.0 || currentTimeMs - lastRelativeUiTimeMs >= 500L) {
            lastRelativeUiValue = relative.toFloat()
            lastRelativeUiTimeMs = currentTimeMs
            _uiState.update { it.copy(relativeDirectionDegrees = relative.toFloat()) }
        }

        // 2-3. 몸 방향이 30° 이상 어긋나 있으면 3초마다 방향 진동 (왼쪽 = 짧게, 오른쪽 = 길게)
        if (kotlin.math.abs(relative) >= TURN_HAPTIC_MIN_DEG && currentTimeMs - lastTurnHapticMs >= TURN_HAPTIC_INTERVAL_MS) {
            lastTurnHapticMs = currentTimeMs
            val type = if (relative < 0) HapticFeedbackType.TURN_LEFT else HapticFeedbackType.TURN_RIGHT
            viewModelScope.launch { _effects.emit(NavigationEffect.Haptic(type)) }
        }

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
     * 지금 향해야 할 방위각(Bearing)을 산출합니다.
     * GPS 위치가 있으면 "현재 위치 → 경로상 전방 지점(look-ahead)" 방위각을 사용합니다.
     * 경로를 벗어나 있으면 전방 지점이 경로 위에 있으므로 자연스럽게 경로로 복귀하는 방향을 가리킵니다.
     * 위치가 아직 없으면 현재 분기점 → 다음 분기점 방위각으로 대체합니다.
     */
    fun calculateTargetBearing(): Double? {
        smoothedGuidanceBearing?.let { return it }
        return calculateSegmentBearing()
    }

    private fun calculateSegmentBearing(): Double? {
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
            if (sample.speedMps < 0.5f) {
                lastValidGpsBearing = null // 정지 또는 초저속 시 GPS bearing 잔류로 인한 오동작 방지
            }
        }
        if (sample.bearingDegrees != null && sample.speedMps != null && sample.speedMps >= 0.75f && sample.accuracyMeters <= 12.0f) {
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

            // 1-0. 현재 위치 기준 전방 경로 지점 방위각 (경로 복귀 방향 포함) 및 지도 표시 위치 산출
            updateGuidanceBearing(progressEngine, progress, sample)

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
                            // GPS 오차(정확도 15m 초과)로 인한 불필요한 출발점 재탐색 방지 (실측: 정확도 24m에서 27m 오차로 재탐색)
                            if (distToStart > 25.0 && sample.accuracyMeters <= 15.0f) {
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
                    upcomingManeuverIndex = progress.upcomingManeuverIndex,
                    distanceToUpcomingManeuverMeters = progress.distanceToUpcomingManeuverMeters.toInt(),
                    isOffRoute = progress.isOffRoute,
                    gpsSignalStrengthPercent = sample.signalStrengthPercent,
                    gpsAccuracyMeters = sample.accuracyMeters,
                    currentLocation = LocationPoint(sample.lat, sample.lon)
                )
            }

            // 1-0-1. 횡단보도 접근 시 카메라 신호 확인 화면 자동 전환
            evaluateCrossingAutoTrigger(progress, sample, currentTimeMs)

            // 1-0-2. 카메라 신호 확인 중 횡단보도를 다 건넜으면 길안내 화면으로 복귀
            evaluateCrossingCompletion(progress, sample, currentTimeMs)

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
                    val stepAction = kr.safecross.mobile.domain.model.DirectionAction.fromManeuver(newManeuver)
                    enqueueGuidance(
                        GuidanceMessage(
                            id = "maneuver_changed_${progress.currentManeuverIndex}_${currentTimeMs}",
                            text = guidanceText,
                            priority = GuidancePriority.ROUTE,
                            category = "maneuver_step",
                            // 좌회전 = 짧은 진동, 우회전 = 긴 진동
                            hapticType = HapticFeedbackType.forTurn(stepAction) ?: HapticFeedbackType.UNKNOWN_CAUTION
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
                                category = "maneuver_approach",
                                hapticType = HapticFeedbackType.forTurn(nextAction)
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
                                hapticType = HapticFeedbackType.forTurn(nextAction) ?: HapticFeedbackType.UNKNOWN_CAUTION
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
                    // 카메라 보조 화면 자동 전환은 경로상 거리 기반 CrossingAutoTriggerPolicy가 단독으로 담당한다
                    // (이 엔진은 직선거리 40m 사전 알림 구간에서도 같은 상태를 내므로 전환 근거로 쓰지 않음)
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
        if (_uiState.value.isFinished) return
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
                    crossingAutoTrigger = buildCrossingAutoTrigger(newRoute, routeProgressEngine!!)
                    hasCalibratedInitialStart = true
                    smoothedGuidanceBearing = null
                    lastAlongTrackMeters = null
                    _uiState.update {
                        it.copy(
                            route = newRoute,
                            currentManeuverIndex = 0,
                            distanceAlongRouteMeters = 0,
                            remainingDistanceMeters = newRoute.totalDistanceMeters,
                            distanceToNextManeuverMeters = newRoute.segments.firstOrNull()?.distanceMeters ?: 50,
                            upcomingManeuverIndex = null,
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

    /**
     * 현재 위치에서 경로상 전방 지점까지의 방위각을 갱신한다.
     * - 전방 거리: 25m + 경로 이탈 거리(최대 40m) → 경로에서 멀어질수록 더 앞쪽 지점으로 비스듬히 복귀
     * - GPS 위치 흔들림으로 방위각이 튀지 않도록 원형 지수 평활화(α=0.4)
     * - 지도 표시 위치: 경로 위(이탈 거리 15m 이하)이면 경로선에 맞춘 위치, 아니면 실제 GPS 위치
     */
    private fun updateGuidanceBearing(
        engine: RouteProgressEngine,
        progress: kr.safecross.mobile.navigation.engine.RouteProgressState,
        sample: LocationSample
    ) {
        if (progress.isFinished) return
        val along = progress.distanceAlongRouteMeters
        lastAlongTrackMeters = along
        val lookAhead = LOOK_AHEAD_METERS + progress.crossTrackErrorMeters.coerceAtMost(40.0)
        val target = engine.pointAtDistance(along + lookAhead)
        if (target != null) {
            val dist = kr.safecross.mobile.navigation.engine.GeoMath.distanceMeters(sample.lat, sample.lon, target.lat, target.lon)
            if (dist >= 3.0) {
                val bearing = kr.safecross.mobile.navigation.engine.GeoMath.initialBearingDegrees(sample.lat, sample.lon, target.lat, target.lon)
                smoothedGuidanceBearing = blendAngle(smoothedGuidanceBearing, bearing, 0.4)
            }
        }

        val mapLocation = if (!progress.isOffRoute && progress.crossTrackErrorMeters <= MAP_MATCH_MAX_CTE_METERS) {
            engine.pointAtDistance(along) ?: LocationPoint(sample.lat, sample.lon)
        } else {
            LocationPoint(sample.lat, sample.lon)
        }
        _uiState.update { it.copy(mapLocation = mapLocation) }
    }

    private fun wrapDegrees(deg: Double): Double = ((deg % 360.0) + 540.0) % 360.0 - 180.0

    /** 원형(각도) 지수 평활화: 359도와 1도 사이를 최단 경로로 보간 */
    private fun blendAngle(prev: Double?, next: Double, alpha: Double): Double {
        if (prev == null) return (next % 360.0 + 360.0) % 360.0
        return ((prev + alpha * wrapDegrees(next - prev)) % 360.0 + 360.0) % 360.0
    }

    /** 샘플 간격에 무관한 시간상수 기반 평활화 계수 */
    private fun smoothingAlpha(dtSec: Double, tauSec: Double): Double =
        (1.0 - kotlin.math.exp(-dtSec / tauSec)).coerceIn(0.02, 1.0)

    /**
     * 경로의 횡단보도 분기점(TMAP turnType 211~217, 시설 유형 "횡단보도" 등)을 경로상 거리와 함께 추출해
     * 자동 전환 정책을 만든다. 이미 전환했던 횡단보도(15m 이내 동일 위치)는 제외한다.
     */
    private fun buildCrossingAutoTrigger(
        route: PedestrianRoute,
        engine: RouteProgressEngine
    ): kr.safecross.mobile.navigation.crossing.CrossingAutoTriggerPolicy {
        val alongs = engine.maneuverAlongDistances
        routeCrosswalkSpans = route.maneuvers.mapIndexedNotNull { idx, m ->
            if (kr.safecross.mobile.domain.model.DirectionAction.fromManeuver(m) != kr.safecross.mobile.domain.model.DirectionAction.CROSSWALK) {
                return@mapIndexedNotNull null
            }
            val start = alongs.getOrElse(idx) { 0.0 }
            // 건너편 끝: 다음 분기점 위치 (없거나 비정상이면 시작점 + 20m)
            val next = alongs.getOrNull(idx + 1)
            val end = if (next != null && next - start in 3.0..60.0) next else start + DEFAULT_CROSSWALK_LENGTH_METERS
            CrosswalkSpan(idx, start, end)
        }
        val crosswalks = route.maneuvers.mapIndexedNotNull { idx, m ->
            if (kr.safecross.mobile.domain.model.DirectionAction.fromManeuver(m) != kr.safecross.mobile.domain.model.DirectionAction.CROSSWALK) {
                return@mapIndexedNotNull null
            }
            val alreadyTriggered = autoTriggeredCrosswalkLocations.any { calculateDistanceMeters(it, m.location) <= 15.0 }
            if (alreadyTriggered) return@mapIndexedNotNull null
            kr.safecross.mobile.navigation.crossing.RouteCrosswalk(
                maneuverIndex = idx,
                alongRouteMeters = alongs.getOrElse(idx) { 0.0 },
                instruction = m.instruction
            )
        }
        NavigationFlightRecorder.record(
            "CROSSING_AUTO",
            "횡단보도 ${crosswalks.size}개 등록: " + crosswalks.joinToString { "#${it.maneuverIndex}@${it.alongRouteMeters.toInt()}m" }
        )
        return kr.safecross.mobile.navigation.crossing.CrossingAutoTriggerPolicy(crosswalks)
    }

    private fun evaluateCrossingAutoTrigger(
        progress: kr.safecross.mobile.navigation.engine.RouteProgressState,
        sample: LocationSample,
        currentTimeMs: Long
    ) {
        val policy = crossingAutoTrigger ?: return
        val decision = policy.evaluate(
            userAlongRouteMeters = progress.distanceAlongRouteMeters,
            speedMps = sample.speedMps,
            accuracyMeters = sample.accuracyMeters,
            isOffRoute = progress.isOffRoute,
            nowMs = currentTimeMs
        ) ?: return
        val route = _uiState.value.route ?: return

        when (decision) {
            is kr.safecross.mobile.navigation.crossing.CrossingAutoTriggerDecision.Trigger -> {
                val remaining = decision.remainingMeters.coerceAtLeast(0.0).toInt()
                route.maneuvers.getOrNull(decision.crosswalk.maneuverIndex)?.let { autoTriggeredCrosswalkLocations.add(it.location) }
                NavigationFlightRecorder.record(
                    "CROSSING_AUTO",
                    "TRIGGER #${decision.crosswalk.maneuverIndex} reason=${decision.reason} remaining=${String.format(Locale.US, "%.1f", decision.remainingMeters)}m " +
                            "acc=${sample.accuracyMeters}m spd=${sample.speedMps ?: -1f}m/s"
                )
                enqueueGuidance(
                    GuidanceMessage(
                        id = "crossing_auto_${decision.crosswalk.maneuverIndex}_${currentTimeMs}",
                        text = if (remaining >= 3) "약 ${remaining}미터 앞 횡단보도입니다. 신호 확인으로 전환합니다." else "횡단보도 앞입니다. 신호 확인으로 전환합니다.",
                        priority = GuidancePriority.CROSSING,
                        category = "crossing_auto_trigger",
                        hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                    ),
                    currentTimeMs
                )
                viewModelScope.launch {
                    _effects.emit(NavigationEffect.TriggerCrossingAssist)
                }
            }
            is kr.safecross.mobile.navigation.crossing.CrossingAutoTriggerDecision.SuggestManual -> {
                val remaining = decision.remainingMeters.coerceAtLeast(0.0).toInt()
                NavigationFlightRecorder.record(
                    "CROSSING_AUTO",
                    "SUGGEST_MANUAL #${decision.crosswalk.maneuverIndex} remaining=${remaining}m acc=${decision.accuracyMeters}m"
                )
                enqueueGuidance(
                    GuidanceMessage(
                        id = "crossing_suggest_${decision.crosswalk.maneuverIndex}_${currentTimeMs}",
                        text = "약 ${remaining}미터 앞 횡단보도입니다. 위치 신호가 불안정해 자동 전환하지 않습니다. 신호 확인이 필요하면 화면의 신호 확인 버튼을 누르세요.",
                        priority = GuidancePriority.CROSSING,
                        category = "crossing_auto_suggest",
                        hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                    ),
                    currentTimeMs
                )
            }
        }
    }

    /**
     * 카메라 신호 확인 화면이 열릴 때 호출: 지금 건너려는 횡단보도(전방 40m 이내 ~ 10m 지난 지점)를 감시 대상으로 등록.
     */
    fun onCrossingAssistOpened(currentTimeMs: Long = System.currentTimeMillis()) {
        val along = lastAlongTrackMeters ?: return
        val span = routeCrosswalkSpans
            .filter { it.startAlong - along in -10.0..40.0 }
            .minByOrNull { kotlin.math.abs(it.startAlong - along) }
        crossingWatch = span?.let { CrossingWatch(it, currentTimeMs, startAlong = along) }
        NavigationFlightRecorder.record(
            "CROSSING_RETURN",
            if (span != null) "WATCH #${span.maneuverIndex} start=${span.startAlong.toInt()}m end=${span.endAlong.toInt()}m along=${along.toInt()}m"
            else "NO_CROSSWALK_NEARBY along=${along.toInt()}m"
        )
    }

    /** 카메라 화면이 녹색을 확정(횡단 시작)했을 때 호출 */
    fun onCrossingGreenConfirmed() {
        crossingWatch?.isGreenConfirmed = true
    }

    /** 사용자가 카메라 화면을 직접 닫았을 때 호출 */
    fun onCrossingAssistClosed() {
        crossingWatch = null
    }

    /**
     * 건너편 끝(다음 분기점) 3m 전까지 도달한 GPS 샘플이 2회 연속(정확도 25m 이내)이고,
     * 카메라가 녹색을 확정했거나 실제로 걷고 있으면(0.6m/s 이상) 횡단 완료로 판단한다.
     * 적색 대기 중 GPS 흔들림만으로 화면이 닫히지 않도록 이동 근거를 함께 요구한다.
     */
    private fun evaluateCrossingCompletion(
        progress: kr.safecross.mobile.navigation.engine.RouteProgressState,
        sample: LocationSample,
        currentTimeMs: Long
    ) {
        val watch = crossingWatch ?: return
        if (currentTimeMs - watch.startedAtMs > CROSSING_WATCH_TIMEOUT_MS) {
            NavigationFlightRecorder.record("CROSSING_RETURN", "TIMEOUT #${watch.span.maneuverIndex}")
            crossingWatch = null
            return
        }

        val reachedFarSide = progress.distanceAlongRouteMeters >= watch.span.endAlong - 3.0
        // 감시 시작 위치에서 건너편 끝까지 물리적으로 걸릴 최소 시간이 지나야 완료로 인정
        // (10/02 현장: 경로상 위치 점프로 전환 5~6초 만에 녹색 확인 없이 지도로 복귀한 사례 2건)
        val minDurationMs = maxOf(
            MIN_CROSSING_DURATION_MS,
            ((watch.span.endAlong - watch.startAlong).coerceAtLeast(0.0) / MAX_CROSSING_SPEED_MPS * 1000).toLong()
        )
        val isPlausibleTime = currentTimeMs - watch.startedAtMs >= minDurationMs
        val isMoving = (sample.speedMps ?: 0f) >= 0.6f
        val isReliable = sample.accuracyMeters <= 25.0f && !progress.isOffRoute
        watch.completeCount = if (reachedFarSide && isReliable && isPlausibleTime && (watch.isGreenConfirmed || isMoving)) watch.completeCount + 1 else 0

        if (watch.completeCount >= 2) {
            NavigationFlightRecorder.record(
                "CROSSING_RETURN",
                "COMPLETED #${watch.span.maneuverIndex} along=${progress.distanceAlongRouteMeters.toInt()}m end=${watch.span.endAlong.toInt()}m " +
                        "green=${watch.isGreenConfirmed} spd=${sample.speedMps ?: -1f} acc=${sample.accuracyMeters}"
            )
            crossingWatch = null
            _crossingCompleted.tryEmit("횡단보도를 건넜습니다. 길안내로 돌아갑니다.")
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

    companion object {
        private const val LOOK_AHEAD_METERS = 25.0
        private const val VPS_MAX_AGE_MS = 1_500L
        private const val TURN_HAPTIC_MIN_DEG = 30.0
        private const val TURN_HAPTIC_INTERVAL_MS = 3_000L
        private const val MAX_CROSSING_SPEED_MPS = 2.5
        private const val MIN_CROSSING_DURATION_MS = 5_000L
        private const val VPS_MAX_YAW_ACCURACY_DEG = 10.0
        private const val COMPASS_BIAS_MAX_AGE_MS = 60_000L
        private const val COMPASS_BIAS_MAX_DISTANCE_M = 30.0
        private const val MAP_MATCH_MAX_CTE_METERS = 15.0
        private const val MAP_HEADING_TAU_SEC = 0.8
        private const val ARROW_TAU_SEC = 0.3
        private const val DEFAULT_CROSSWALK_LENGTH_METERS = 20.0
        private const val CROSSING_WATCH_TIMEOUT_MS = 5 * 60_000L
    }

    override fun onCleared() {
        super.onCleared()
        stopLocationTracking()
        serviceStopJob?.cancel()
        guidanceArbiter.stopAll()
    }
}
