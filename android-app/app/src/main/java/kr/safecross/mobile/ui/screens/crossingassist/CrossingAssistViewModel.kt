package kr.safecross.mobile.ui.screens.crossingassist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.safecross.mobile.camera.CameraPipeManager
import kr.safecross.mobile.camera.FakeCameraPipeManager
import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.decision.CrossingAssistDecisionState
import kr.safecross.mobile.decision.CrossingDecisionEngine
import kr.safecross.mobile.decision.model.OfficialSignalObservation
import kr.safecross.mobile.guidance.ArbiterAction
import kr.safecross.mobile.guidance.GuidanceArbiter
import kr.safecross.mobile.guidance.GuidanceMessage
import kr.safecross.mobile.guidance.GuidancePriority
import kr.safecross.mobile.guidance.HapticFeedbackType
import kr.safecross.mobile.perception.CrosswalkSceneEstimator
import kr.safecross.mobile.perception.DepthEstimator
import kr.safecross.mobile.perception.DepthRoiAnalyzer
import kr.safecross.mobile.perception.FakeCrosswalkEstimator
import kr.safecross.mobile.perception.FakeDepthEstimator
import kr.safecross.mobile.perception.FakeSignalEstimator
import kr.safecross.mobile.perception.LockOnSignalAssociator
import kr.safecross.mobile.perception.ObservedSignalState
import kr.safecross.mobile.perception.PedestrianSignalEstimator
import kr.safecross.mobile.perception.PerceptionFlightRecorder
import kr.safecross.mobile.perception.TargetSignalAssociator
import kr.safecross.mobile.perception.VerifiedCrossingContext
import kr.safecross.mobile.sensor.DevicePoseTracker
import kr.safecross.mobile.sensor.FakeDevicePoseTracker
import kr.safecross.mobile.sensor.TiltGuidance
import kr.safecross.mobile.signal.FakeSignalStatusProvider
import kr.safecross.mobile.signal.SignalStatusProvider
import kr.safecross.mobile.signal.model.SignalFetchResult

/**
 * 횡단 보조 화면 뷰모델 (SR-F-040, SR-F-049, SR-F-070).
 */
class CrossingAssistViewModel(
    val cameraPipeManager: CameraPipeManager = FakeCameraPipeManager(),
    val crosswalkEstimator: CrosswalkSceneEstimator = FakeCrosswalkEstimator(),
    val signalEstimator: PedestrianSignalEstimator = FakeSignalEstimator(),
    val signalAssociator: TargetSignalAssociator = LockOnSignalAssociator(),
    val depthEstimator: DepthEstimator = FakeDepthEstimator(),
    val decisionEngine: CrossingDecisionEngine = CrossingDecisionEngine(),
    val poseTracker: DevicePoseTracker = FakeDevicePoseTracker(),
    val guidanceArbiter: GuidanceArbiter = GuidanceArbiter(),
    val signalStatusProvider: SignalStatusProvider = FakeSignalStatusProvider(),
    val enableSignalPolling: Boolean = false
) : ViewModel() {

    private val _uiState = MutableStateFlow(CrossingAssistUiState())
    val uiState: StateFlow<CrossingAssistUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<CrossingAssistEffect>()
    val effects: SharedFlow<CrossingAssistEffect> = _effects.asSharedFlow()

    private var activeCrossingContext: VerifiedCrossingContext? = null
    private var isAnalyzing = false
    private var guidanceSeq = 0

    // 기기 기울기 경고 발화 쿨다운 상태
    private var lastTiltSpeechTimeMs: Long = 0L
    private var lastSpokenTiltGuidance: TiltGuidance? = null
    private val tiltSpeechCooldownMs: Long = 6_000L

    // 신호등 조준(락온) 디바운스 상태
    private var reticleOutCount = 0
    private var lastLockOnSpeechTimeMs = 0L

    private var lastGreenGuidanceTimeMs = 0L
    private var hasSpokenCurrentGreenPhase = false

    private var signalPollingJob: Job? = null
    private var latestOfficialSignal: OfficialSignalObservation? = null
    private var officialSignalRemainingSec: Int? = null

    // 신호등 판정 상태 디바운스(연속 상태 카운트): 5프레임 연속 동일 판정일 때만 화면/발화에 반영
    private var candidateState: CrossingAssistDecisionState? = null
    private var consecutiveStateCount = 0

    // 깊이 추정 진단용 스로틀 상태 (매 프레임 실행하지 않음)
    private var lastDepthEstimateAtMs: Long = 0L
    private val depthEstimateIntervalMs: Long = 700L
    private var lastDepthDiagText: String = "DEPTH: n/a"
    private var isDepthEstimateInFlight = false

    // 장애물 근접 추세 경고 (2단계, 데드라인으로 인한 축소 범위).
    // 절대 거리 임계치(예: "peak > 1.8이면 1m")는 캘리브레이션 실측 결과 조건(각도/배경)마다
    // 값이 너무 흔들려 신뢰할 수 없었다. 대신 "방금 전 몇 초 대비 지금 값이 뚜렷하게 오르고
    // 있는가"라는 상대적 추세만 본다 - 절대 보정 없이도 "무언가 가까워지고 있다"는 정성적
    // 신호는 비교적 안정적으로 잡을 수 있다는 판단.
    private val depthTrendWindow: ArrayDeque<Pair<Long, Float>> = ArrayDeque() // (timestampMs, peakRatio)
    private val depthTrendWindowMs: Long = 8_000L       // 추세 판단에 사용할 최근 이력 범위
    private val depthTrendRecentMs: Long = 2_000L       // "지금"으로 취급할 최근 구간
    private val depthTrendRiseFactor: Float = 1.20f     // 기준 대비 20% 이상 상승 시 "다가옴" 후보
    private val depthTrendMinRecentValue: Float = 1.0f  // 잡음 방지: 절대값이 너무 낮으면 무시
    private var consecutiveProximityTrendCount = 0
    private var lastObstacleWarningAtMs: Long = 0L
    private val obstacleWarningCooldownMs: Long = 12_000L

    init {
        // 기기 기울기 모니터링 구독 (과도한 반복 발화 억제를 위한 쿨다운 적용)
        viewModelScope.launch {
            poseTracker.tiltGuidance.collect { guidance ->
                _uiState.value = _uiState.value.copy(tiltGuidance = guidance)

                if (!guidance.isSuitable && isAnalyzing) {
                    val now = System.currentTimeMillis()
                    val canSpeak = (now - lastTiltSpeechTimeMs >= tiltSpeechCooldownMs) ||
                            (lastSpokenTiltGuidance != guidance && now - lastTiltSpeechTimeMs >= 3_000L)
                    if (canSpeak) {
                        lastTiltSpeechTimeMs = now
                        lastSpokenTiltGuidance = guidance
                        emitGuidance(
                            text = guidance.instruction,
                            priority = GuidancePriority.CROSSING,
                            category = "tilt_guidance",
                            hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                        )
                    }
                } else if (guidance.isSuitable) {
                    lastSpokenTiltGuidance = null
                }
            }
        }

        // 실시간 기울기 각도(pitch) 구독: 깊이 캘리브레이션 패널에서 "지금 몇 도로 숙였는지"를
        // 눈으로 확인하고, 태깅 시 그 값을 근접도 비율과 함께 기록하기 위함 (근접 거리는 신호등용
        // 허용 각도(-35도)보다 훨씬 더 숙여야 지면 물체가 프레임에 들어온다).
        viewModelScope.launch {
            poseTracker.currentPose.collect { pose ->
                _uiState.value = _uiState.value.copy(currentPitchDegrees = pose.pitchDegrees)
            }
        }
    }

    fun onCameraPermissionGranted(context: VerifiedCrossingContext? = null) {
        _uiState.value = _uiState.value.copy(hasCameraPermission = true)
        startAssistance(context)
    }

    fun onCameraPermissionDenied() {
        _uiState.value = _uiState.value.copy(
            hasCameraPermission = false,
            decisionState = CrossingAssistDecisionState.UNKNOWN,
            statusMessage = "카메라 권한이 거부되어 신호 확인이 불가합니다."
        )
        emitGuidance(
            text = "카메라 권한이 필요합니다. 권한을 확인해 주세요.",
            priority = GuidancePriority.SAFETY,
            category = "camera_permission",
            hapticType = HapticFeedbackType.UNKNOWN_CAUTION
        )
    }

    fun startAssistance(context: VerifiedCrossingContext?) {
        activeCrossingContext = context
        decisionEngine.reset()
        poseTracker.startTracking()
        isAnalyzing = true
        candidateState = null
        consecutiveStateCount = 0
        depthTrendWindow.clear()
        consecutiveProximityTrendCount = 0
        lastObstacleWarningAtMs = 0L
        _uiState.value = _uiState.value.copy(
            isCameraBound = true,
            isTerminated = false,
            decisionState = CrossingAssistDecisionState.SCANNING,
            statusMessage = "전방 횡단보도와 신호등을 탐색 중입니다."
        )
        emitGuidance(
            text = "카메라 횡단 보조를 시작합니다. 스마트폰을 전방으로 들어주세요.",
            priority = GuidancePriority.CROSSING,
            category = "crossing_assist_start",
            hapticType = HapticFeedbackType.UNKNOWN_CAUTION
        )

        // C-ITS 실시간 보행 신호 폴링 시작 (광주광역시 C-ITS / UTIC 연동, 활성화된 경우만)
        signalPollingJob?.cancel()
        if (enableSignalPolling) {
            val intersectionId = context?.crossingId ?: "GWANGJU-DEFAULT-01"
            val movementId = "PED-01"
            signalPollingJob = viewModelScope.launch {
                while (isActive && isAnalyzing) {
                    val fetchResult = signalStatusProvider.fetchSignalStatus(
                        intersectionId = intersectionId,
                        movementId = movementId,
                        currentElapsedRealtimeNanos = System.nanoTime()
                    )
                    if (fetchResult is SignalFetchResult.Success) {
                        latestOfficialSignal = OfficialSignalObservation.fromNormalized(fetchResult.status)
                        officialSignalRemainingSec = fetchResult.status.optionalRemainingSeconds
                    }
                    delay(1000L)
                }
            }
        }
    }

    fun processFrame(frame: FrameRef) {
        if (_uiState.value.isTerminated) return

        // 깊이 추정은 신호 판정 동결(GREEN_ESTIMATE 확정 후 isAnalyzing=false)과 완전히
        // 독립적으로 항상 최신 상태를 유지해야 한다. 예전엔 이 로직이 아래 isAnalyzing 게이트
        // 안에 있어서, 실제 신호등이 녹색으로 확정돼 신호 분석이 동결되면 근접도 값까지 그
        // 순간에 영원히 멈춰버리는 버그가 있었다 (2026-09-30 실측 캘리브레이션 데이터에서
        // 발견: 같은 세션 내 모든 거리 태그에서 near/avg 값이 완전히 동일하게 찍힘).
        maybeRunDepthEstimate(frame)

        if (!isAnalyzing) return

        viewModelScope.launch {
            try {
                // 1. 횡단보도 형상 인식
                val cwObs = crosswalkEstimator.estimate(frame)

                // 2. 보행신호기 인식
                val sigObs = signalEstimator.estimate(frame)

                // 3. 목표 신호 1:1 연결 (Lock-on)
                val currentPose = poseTracker.currentPose.value
                val isTiltOk = poseTracker.tiltGuidance.value.isSuitable
                val association = signalAssociator.associate(
                    crossing = activeCrossingContext,
                    devicePose = currentPose,
                    crosswalk = cwObs,
                    signals = sigObs
                )

                // 4. 안전 상태기계 판정 (C-ITS 실시간 공식 신호 융합)
                val decision = decisionEngine.evaluate(
                    crossingContext = activeCrossingContext,
                    devicePose = currentPose,
                    crosswalk = cwObs,
                    association = association,
                    isTiltSuitable = isTiltOk,
                    officialSignal = latestOfficialSignal
                )

                val targetSignal = association.targetSignal
                val targetBox = targetSignal?.box
                val reticle = _uiState.value.reticleBox

                // 실제 유효 신호(RED/GREEN)가 명확히 감지된 경우에만 조준 완료(락온)로 인정 (더미 탐색 박스 오조준 방지)
                val isActualSignalDetected = targetSignal != null && targetSignal.state != ObservedSignalState.UNKNOWN
                val isInsideRaw = if (isActualSignalDetected && targetBox != null) {
                    val cx = (targetBox.left + targetBox.right) / 2f
                    val cy = (targetBox.top + targetBox.bottom) / 2f
                    cx in reticle.left..reticle.right && cy in reticle.top..reticle.bottom
                } else {
                    false
                }

                // 손떨림 방지 조준선 디바운싱: 8프레임(약 270ms) 이내의 일시적 이탈은 조준 상태 유지
                val wasInReticle = _uiState.value.isSignalInReticle
                val isInsideReticle = if (isInsideRaw) {
                    reticleOutCount = 0
                    true
                } else if (wasInReticle && reticleOutCount < 8) {
                    reticleOutCount++
                    true
                } else {
                    reticleOutCount = 0
                    false
                }

                if (isInsideReticle && !wasInReticle) {
                    val now = System.currentTimeMillis()
                    if (now - lastLockOnSpeechTimeMs >= 4_000L) {
                        lastLockOnSpeechTimeMs = now
                        _effects.emit(
                            CrossingAssistEffect.SpeakGuidance(
                                text = "신호등이 조준되었습니다.",
                                hapticType = HapticFeedbackType.ORIENTATION_ALIGNED,
                                queueFlush = false
                            )
                        )
                    }
                }

                // 횡단보도는 감지되었으나 신호등이 감지되지 않는 경우 직관적인 상태 메시지 제공
                val resolvedStatusMessage = when {
                    decision.state == CrossingAssistDecisionState.UNKNOWN && cwObs.hasCrosswalk && !isActualSignalDetected ->
                        "횡단보도 감지됨 (신호등 미인식 / 무신호 주의)"
                    decision.guidanceText != null -> decision.guidanceText
                    else -> decision.state.description
                }

                // 진단 HUD 및 Flight Recorder 기록 (C-ITS 정보 포함)
                val sigStateStr = targetSignal?.state?.name ?: "NONE"
                val sigScoreStr = targetSignal?.let { "%.2f".format(it.score) } ?: "0.00"
                val trackIdStr = targetSignal?.ephemeralTrackId?.takeLast(8) ?: "none"
                val citsTag = latestOfficialSignal?.let { "[C-ITS:${it.state}${officialSignalRemainingSec?.let { s -> " ${s}s" } ?: ""}]" } ?: "[C-ITS:OFF]"
                val diagText = "SIG: $sigStateStr ($sigScoreStr) [#$trackIdStr] $citsTag | G-CNT: ${decisionEngine.consecutiveGreenCount}/5 | TILT: ${if (isTiltOk) "OK" else "WARN"} | RET: ${if (isInsideReticle) "IN" else "OUT"} | $lastDepthDiagText\nDEC: ${decision.state.name} (${decision.reasonCode ?: "OK"})"

                PerceptionFlightRecorder.updateSummary(diagText)
                PerceptionFlightRecorder.record(
                    "FRAME",
                    "Sig=$sigStateStr($sigScoreStr) Trk=$trackIdStr GCount=${decisionEngine.consecutiveGreenCount} Tilt=$isTiltOk Ret=$isInsideReticle Dec=${decision.state} Reason=${decision.reasonCode} CITS=${latestOfficialSignal?.state}"
                )

                // 상태 디바운스(연속 상태 카운트): 동일 판정이 5프레임 연속 유지될 때만 화면/발화에 반영 (오탐 깜빡임 억제)
                val rawState = decision.state
                if (rawState == candidateState) {
                    consecutiveStateCount++
                } else {
                    candidateState = rawState
                    consecutiveStateCount = 1
                }

                if (consecutiveStateCount >= 5) {
                    val previousConfirmedState = _uiState.value.decisionState

                    _uiState.value = _uiState.value.copy(
                        decisionState = rawState,
                        crosswalkDetected = cwObs.hasCrosswalk,
                        statusMessage = resolvedStatusMessage,
                        detectedSignalBox = if (isActualSignalDetected) targetBox else null,
                        detectedSignalColor = if (isActualSignalDetected) targetSignal?.state else null,
                        isSignalInReticle = isInsideReticle,
                        debugDiagnosticText = diagText
                    )

                    // 5. 발화 안내: 녹색 신호는 1회만 알리고 이후 비전 분석을 동결(아이나비식), 그 외 상태는 상태 전환 시 1회 발화
                    if (rawState == CrossingAssistDecisionState.GREEN_ESTIMATE) {
                        if (!hasSpokenCurrentGreenPhase) {
                            hasSpokenCurrentGreenPhase = true
                            lastGreenGuidanceTimeMs = System.currentTimeMillis()
                            val citsInfo = officialSignalRemainingSec?.let { " (잔여 ${it}초)" } ?: ""
                            emitGuidance(
                                text = "신호가 바뀌었습니다. 건너가세요.$citsInfo 좌우를 살피며 횡단하세요. 다 건너신 후에는 화면 아래 종료 버튼을 눌러 길안내로 돌아가세요.",
                                priority = GuidancePriority.SAFETY,
                                category = "signal_decision_green",
                                hapticType = decision.hapticType ?: HapticFeedbackType.GREEN_ESTIMATE
                            )

                            // 출발 알림 후 비전 분석 즉시 동결: 건너는 도중 스마트폰 흔들림으로 인한 판정 핑퐁 방지.
                            // 동결 이후 화면이 마지막 프레임에 "박제"된 것처럼 보이지 않도록 신호등 관련
                            // 표시(바운딩 박스/조준 상태)는 여기서 명시적으로 비운다.
                            isAnalyzing = false
                            signalPollingJob?.cancel()
                            _uiState.value = _uiState.value.copy(
                                statusMessage = "안전 횡단 진행 중입니다. 다 건너면 아래 종료 버튼을 눌러주세요.",
                                detectedSignalBox = null,
                                detectedSignalColor = null,
                                isSignalInReticle = false
                            )
                        }
                    } else {
                        hasSpokenCurrentGreenPhase = false
                        if (resolvedStatusMessage.isNotBlank() && rawState != previousConfirmedState) {
                            val priority = when (rawState) {
                                CrossingAssistDecisionState.RED_ESTIMATE -> GuidancePriority.SAFETY
                                CrossingAssistDecisionState.UNKNOWN -> GuidancePriority.CROSSING
                                else -> GuidancePriority.INFO
                            }
                            val category = when (rawState) {
                                CrossingAssistDecisionState.RED_ESTIMATE -> "signal_decision_red"
                                CrossingAssistDecisionState.UNKNOWN -> "signal_decision_unknown"
                                else -> "signal_decision_info"
                            }
                            emitGuidance(
                                text = resolvedStatusMessage,
                                priority = priority,
                                category = category,
                                hapticType = decision.hapticType
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    decisionState = CrossingAssistDecisionState.UNKNOWN,
                    statusMessage = "분석 오류가 발생했습니다. 주변을 직접 확인하세요."
                )
            }
        }
    }

    /**
     * 깊이 추정을 스로틀링(700ms)하며 백그라운드 코루틴에서 실행한다.
     * GPU/NNAPI 가속 없이 CPU만으로 640x384 백본을 돌리는 무거운 연산이라, 신호등/횡단보도
     * 인식(안전 핵심 경로)을 절대 지연시키지 않도록 별도 코루틴으로 완전히 분리한다. 여기서
     * await하지 않고 fire-and-forget으로 던지고, 완료되면 lastDepthDiagText/lastDepthRatio만
     * 갱신한다. isDepthEstimateInFlight로 중첩 실행을 막아 스레드풀 과점유를 방지한다.
     * processFrame()의 isAnalyzing 게이트보다 앞에서 호출되므로, 신호 판정이 동결된 상태에서도
     * 계속 최신 값을 유지한다.
     */
    private fun maybeRunDepthEstimate(frame: FrameRef) {
        val nowMs = System.currentTimeMillis()
        if (isDepthEstimateInFlight || nowMs - lastDepthEstimateAtMs < depthEstimateIntervalMs) return

        lastDepthEstimateAtMs = nowMs
        isDepthEstimateInFlight = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val depthObs = depthEstimator.estimate(frame)
                val ratio = DepthRoiAnalyzer.nearPathProximityRatio(depthObs)
                val peakRatio = DepthRoiAnalyzer.nearPathPeakRatio(depthObs)
                lastDepthDiagText = if (ratio != null && peakRatio != null) {
                    "DEPTH: avg=%.2f peak=%.2f".format(ratio, peakRatio)
                } else "DEPTH: n/a"
                _uiState.value = _uiState.value.copy(lastDepthRatio = ratio, lastDepthPeakRatio = peakRatio)
                if (peakRatio != null) {
                    evaluateObstacleProximityTrend(System.currentTimeMillis(), peakRatio)
                }
            } catch (_: Exception) {
                lastDepthDiagText = "DEPTH: n/a"
            } finally {
                isDepthEstimateInFlight = false
            }
        }
    }

    /**
     * 근접도(peak) 값이 최근 대비 뚜렷하게 오르고 있는지(=무언가 가까워지고 있는지) 판단한다.
     * 절대 임계치가 아니라 상대 추세만 보므로, 조건(각도/배경)에 따라 절대값이 흔들려도
     * "지금이 조금 전보다 확실히 가까워졌는가"는 비교적 안정적으로 판단할 수 있다.
     * 2회 연속 추세가 확인되고 쿨다운이 지났을 때만 SAFETY 경고를 1회 발화한다.
     */
    private fun evaluateObstacleProximityTrend(nowMs: Long, peakRatio: Float) {
        depthTrendWindow.addLast(nowMs to peakRatio)
        while (depthTrendWindow.isNotEmpty() && nowMs - depthTrendWindow.first().first > depthTrendWindowMs) {
            depthTrendWindow.removeFirst()
        }

        val recentCutoff = nowMs - depthTrendRecentMs
        val recent = depthTrendWindow.filter { it.first >= recentCutoff }
        val baseline = depthTrendWindow.filter { it.first < recentCutoff }

        if (recent.size < 2 || baseline.size < 3) {
            consecutiveProximityTrendCount = 0
            return
        }

        val recentAvg = recent.map { it.second }.average().toFloat()
        val baselineAvg = baseline.map { it.second }.average().toFloat()
        val isRising = recentAvg >= baselineAvg * depthTrendRiseFactor && recentAvg >= depthTrendMinRecentValue

        consecutiveProximityTrendCount = if (isRising) consecutiveProximityTrendCount + 1 else 0

        if (consecutiveProximityTrendCount >= 2 && nowMs - lastObstacleWarningAtMs >= obstacleWarningCooldownMs) {
            lastObstacleWarningAtMs = nowMs
            consecutiveProximityTrendCount = 0
            emitGuidance(
                text = "전방에 장애물이 가까워지고 있습니다. 주의하세요.",
                priority = GuidancePriority.SAFETY,
                category = "obstacle_proximity",
                hapticType = HapticFeedbackType.SAFETY_WARNING
            )
        }
    }

    /**
     * 깊이 임계치 캘리브레이션용 수동 태깅. 테스터가 실측 거리에 물체를 놓고 버튼을 누르면
     * 그 순간의 near/avg 비율을 (실측 거리, 비율) 쌍으로 FlightRecorder 로그에 남긴다.
     * 이 값들을 모아 나중에 2단계(SAFETY 경고) 임계치를 실측 기반으로 정한다.
     */
    fun tagCalibrationPoint(distanceLabel: String) {
        val ratio = _uiState.value.lastDepthRatio
        val ratioText = ratio?.let { "%.4f".format(it) } ?: "N/A"
        val peakRatio = _uiState.value.lastDepthPeakRatio
        val peakRatioText = peakRatio?.let { "%.4f".format(it) } ?: "N/A"
        val pitchDeg = _uiState.value.currentPitchDegrees
        val pitchText = "%.1f".format(pitchDeg)
        PerceptionFlightRecorder.record(
            "CALIBRATION",
            "distance=$distanceLabel near_avg_ratio=$ratioText near_peak_ratio=$peakRatioText pitch_deg=$pitchText"
        )
        val nowLabel = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
        _uiState.value = _uiState.value.copy(
            calibrationStatusText = "기록됨: $distanceLabel (avg=$ratioText, peak=$peakRatioText, 기울기=$pitchText°) $nowLabel"
        )
    }

    private fun emitGuidance(
        text: String,
        priority: GuidancePriority,
        category: String,
        hapticType: HapticFeedbackType?
    ) {
        val msg = GuidanceMessage(
            id = "assist_${++guidanceSeq}",
            text = text,
            priority = priority,
            category = category,
            hapticType = hapticType
        )
        val decision = guidanceArbiter.enqueue(msg)
        when (decision.action) {
            ArbiterAction.PLAY_IMMEDIATELY, ArbiterAction.PREEMPT_AND_PLAY -> {
                val now = System.currentTimeMillis()
                val isRecentGreen = (now - lastGreenGuidanceTimeMs) < 1800L
                val shouldFlush = (msg.priority == GuidancePriority.SAFETY) && (!isRecentGreen || msg.text.contains("녹색"))

                viewModelScope.launch {
                    _effects.emit(
                        CrossingAssistEffect.SpeakGuidance(
                            text = msg.text,
                            hapticType = msg.hapticType,
                            queueFlush = shouldFlush
                        )
                    )
                }
            }
            ArbiterAction.QUEUE -> {
                viewModelScope.launch {
                    _effects.emit(
                        CrossingAssistEffect.SpeakGuidance(
                            text = msg.text,
                            hapticType = msg.hapticType,
                            queueFlush = false
                        )
                    )
                }
            }
            ArbiterAction.SUPPRESSED_COOLDOWN, ArbiterAction.DROPPED_EXPIRED -> {
                // 쿨다운 또는 만료로 억제됨
            }
        }
    }

    /**
     * 화면 이탈, 백그라운드 전환, 또는 사용자의 즉시 종료 요청 시 자원 완전 해제 (SR-F-049).
     */
    fun stopAssistance() {
        isAnalyzing = false
        signalPollingJob?.cancel()
        poseTracker.stopTracking()
        cameraPipeManager.unbind()
        decisionEngine.reset()
        guidanceArbiter.stopAll()
        hasSpokenCurrentGreenPhase = false
        latestOfficialSignal = null
        officialSignalRemainingSec = null
        candidateState = null
        consecutiveStateCount = 0
        depthTrendWindow.clear()
        consecutiveProximityTrendCount = 0
        _uiState.value = _uiState.value.copy(
            isCameraBound = false,
            isTerminated = true,
            decisionState = CrossingAssistDecisionState.IDLE,
            statusMessage = "횡단 보조가 종료되었습니다."
        )
        viewModelScope.launch {
            _effects.emit(CrossingAssistEffect.FinishScreen)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAssistance()
    }
}
