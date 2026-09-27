package kr.safecross.mobile.ui.screens.crossingassist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kr.safecross.mobile.perception.FakeCrosswalkEstimator
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
        if (!isAnalyzing || _uiState.value.isTerminated) return

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
                val diagText = "SIG: $sigStateStr ($sigScoreStr) [#$trackIdStr] $citsTag | G-CNT: ${decisionEngine.consecutiveGreenCount}/5 | TILT: ${if (isTiltOk) "OK" else "WARN"} | RET: ${if (isInsideReticle) "IN" else "OUT"}\nDEC: ${decision.state.name} (${decision.reasonCode ?: "OK"})"

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
                                text = "신호가 바뀌었습니다. 건너가세요.$citsInfo 좌우를 살피며 횡단하세요.",
                                priority = GuidancePriority.SAFETY,
                                category = "signal_decision_green",
                                hapticType = decision.hapticType ?: HapticFeedbackType.GREEN_ESTIMATE
                            )

                            // 출발 알림 후 비전 분석 즉시 동결: 건너는 도중 스마트폰 흔들림으로 인한 판정 핑퐁 방지
                            isAnalyzing = false
                            signalPollingJob?.cancel()
                            _uiState.value = _uiState.value.copy(
                                statusMessage = "안전 횡단 진행 중 (비전 자동 완료)"
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
