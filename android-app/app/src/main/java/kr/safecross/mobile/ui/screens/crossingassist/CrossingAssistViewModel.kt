package kr.safecross.mobile.ui.screens.crossingassist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.safecross.mobile.camera.CameraPipeManager
import kr.safecross.mobile.camera.FakeCameraPipeManager
import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.decision.CrossingAssistDecisionState
import kr.safecross.mobile.decision.CrossingDecisionEngine
import kr.safecross.mobile.guidance.ArbiterAction
import kr.safecross.mobile.guidance.GuidanceArbiter
import kr.safecross.mobile.guidance.GuidanceMessage
import kr.safecross.mobile.guidance.GuidancePriority
import kr.safecross.mobile.perception.CrosswalkSceneEstimator
import kr.safecross.mobile.perception.FakeCrosswalkEstimator
import kr.safecross.mobile.perception.FakeSignalAssociator
import kr.safecross.mobile.perception.FakeSignalEstimator
import kr.safecross.mobile.perception.PedestrianSignalEstimator
import kr.safecross.mobile.perception.PerceptionFlightRecorder
import kr.safecross.mobile.perception.TargetSignalAssociator
import kr.safecross.mobile.perception.VerifiedCrossingContext
import kr.safecross.mobile.sensor.DevicePoseTracker
import kr.safecross.mobile.sensor.FakeDevicePoseTracker
import kr.safecross.mobile.sensor.TiltGuidance

/**
 * 횡단 보조 화면 뷰모델 (SR-F-040, SR-F-049, SR-F-070).
 */
class CrossingAssistViewModel(
    val cameraPipeManager: CameraPipeManager = FakeCameraPipeManager(),
    val crosswalkEstimator: CrosswalkSceneEstimator = FakeCrosswalkEstimator(),
    val signalEstimator: PedestrianSignalEstimator = FakeSignalEstimator(),
    val signalAssociator: TargetSignalAssociator = FakeSignalAssociator(),
    val decisionEngine: CrossingDecisionEngine = CrossingDecisionEngine(),
    val poseTracker: DevicePoseTracker = FakeDevicePoseTracker(),
    val guidanceArbiter: GuidanceArbiter = GuidanceArbiter()
) : ViewModel() {

    private val _uiState = MutableStateFlow(CrossingAssistUiState())
    val uiState: StateFlow<CrossingAssistUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<CrossingAssistEffect>()
    val effects: SharedFlow<CrossingAssistEffect> = _effects.asSharedFlow()

    private var activeCrossingContext: VerifiedCrossingContext? = null
    private var isAnalyzing = false
    private var guidanceSeq = 0
    private var lastTiltSpeechTimeMs: Long = 0L
    private var lastSpokenTiltGuidance: TiltGuidance? = null
    private val tiltSpeechCooldownMs: Long = 6_000L

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
                            hapticType = kr.safecross.mobile.guidance.HapticFeedbackType.UNKNOWN_CAUTION
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
            hapticType = kr.safecross.mobile.guidance.HapticFeedbackType.UNKNOWN_CAUTION
        )
    }

    fun startAssistance(context: VerifiedCrossingContext?) {
        activeCrossingContext = context
        decisionEngine.reset()
        poseTracker.startTracking()
        isAnalyzing = true
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
            hapticType = kr.safecross.mobile.guidance.HapticFeedbackType.UNKNOWN_CAUTION
        )
    }

    fun processFrame(frame: FrameRef) {
        if (!isAnalyzing || _uiState.value.isTerminated) return

        viewModelScope.launch {
            try {
                // 1. 횡단보도 형상 인식
                val cwObs = crosswalkEstimator.estimate(frame)

                // 2. 보행신호기 인식
                val sigObs = signalEstimator.estimate(frame)

                // 3. 목표 신호 1:1 연결
                val currentPose = poseTracker.currentPose.value
                val isTiltOk = poseTracker.tiltGuidance.value.isSuitable
                val association = signalAssociator.associate(
                    crossing = activeCrossingContext,
                    devicePose = currentPose,
                    crosswalk = cwObs,
                    signals = sigObs
                )

                // 4. 안전 상태기계 판정
                val decision = decisionEngine.evaluate(
                    crossingContext = activeCrossingContext,
                    devicePose = currentPose,
                    crosswalk = cwObs,
                    association = association,
                    isTiltSuitable = isTiltOk
                )

                val targetSignal = association.targetSignal
                val targetBox = targetSignal?.box
                val reticle = _uiState.value.reticleBox

                // 실제 유효 신호(RED 또는 GREEN)가 명확히 감지된 경우에만 조준 완료(락온)로 인정 (더미 탐색 박스 오조준 방지)
                val isActualSignalDetected = targetSignal != null && targetSignal.state != kr.safecross.mobile.perception.ObservedSignalState.UNKNOWN
                val isInsideReticle = if (isActualSignalDetected && targetBox != null) {
                    val cx = (targetBox.left + targetBox.right) / 2f
                    val cy = (targetBox.top + targetBox.bottom) / 2f
                    cx in reticle.left..reticle.right && cy in reticle.top..reticle.bottom
                } else {
                    false
                }

                val wasInReticle = _uiState.value.isSignalInReticle
                if (isInsideReticle && !wasInReticle) {
                    viewModelScope.launch {
                        _effects.emit(
                            CrossingAssistEffect.SpeakGuidance(
                                text = "신호등이 조준되었습니다.",
                                hapticType = kr.safecross.mobile.guidance.HapticFeedbackType.ORIENTATION_ALIGNED,
                                queueFlush = false
                            )
                        )
                    }
                }

                // 횡단보도는 감지되었으나 신호등이 감지되지 않는 경우 직관적인 상태 메시지 제공
                val resolvedStatusMessage = when {
                    decision.state == CrossingAssistDecisionState.UNKNOWN && cwObs.hasCrosswalk && !isActualSignalDetected ->
                        "횡단보도 감지됨 (신호등 미인식 / 무신호 주의)"
                    decision.guidanceText != null ->
                        decision.guidanceText
                    else ->
                        decision.state.description
                }

                // 진단 HUD 및 Flight Recorder 기록
                val sigStateStr = targetSignal?.state?.name ?: "NONE"
                val sigScoreStr = targetSignal?.let { "%.2f".format(it.score) } ?: "0.00"
                val trackIdStr = targetSignal?.ephemeralTrackId?.takeLast(8) ?: "none"
                val diagText = "SIG: $sigStateStr ($sigScoreStr) [#$trackIdStr] | G-CNT: ${decisionEngine.consecutiveGreenCount}/5 | TILT: ${if (isTiltOk) "OK" else "WARN"} | RET: ${if (isInsideReticle) "IN" else "OUT"}\nDEC: ${decision.state.name} (${decision.reasonCode ?: "OK"})"

                PerceptionFlightRecorder.updateSummary(diagText)
                PerceptionFlightRecorder.record(
                    "FRAME",
                    "Sig=$sigStateStr($sigScoreStr) Trk=$trackIdStr GCount=${decisionEngine.consecutiveGreenCount} Tilt=$isTiltOk Ret=$isInsideReticle Dec=${decision.state} Reason=${decision.reasonCode}"
                )

                _uiState.value = _uiState.value.copy(
                    decisionState = decision.state,
                    crosswalkDetected = cwObs.hasCrosswalk,
                    statusMessage = resolvedStatusMessage,
                    detectedSignalBox = if (isActualSignalDetected) targetBox else null,
                    detectedSignalColor = if (isActualSignalDetected) targetSignal?.state else null,
                    isSignalInReticle = isInsideReticle,
                    debugDiagnosticText = diagText
                )

                // 5. 발화 안내 이벤트 전송
                if (decision.guidanceText != null) {
                    val priority = when (decision.state) {
                        CrossingAssistDecisionState.RED_ESTIMATE -> GuidancePriority.SAFETY
                        CrossingAssistDecisionState.GREEN_ESTIMATE -> GuidancePriority.SAFETY
                        CrossingAssistDecisionState.UNKNOWN -> GuidancePriority.CROSSING
                        else -> GuidancePriority.INFO
                    }
                    val category = "signal_decision"

                    emitGuidance(
                        text = decision.guidanceText,
                        priority = priority,
                        category = category,
                        hapticType = decision.hapticType
                    )
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
        hapticType: kr.safecross.mobile.guidance.HapticFeedbackType?
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
                viewModelScope.launch {
                    _effects.emit(
                        CrossingAssistEffect.SpeakGuidance(
                            text = msg.text,
                            hapticType = msg.hapticType,
                            queueFlush = msg.priority == GuidancePriority.SAFETY
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
        poseTracker.stopTracking()
        cameraPipeManager.unbind()
        decisionEngine.reset()
        guidanceArbiter.stopAll()
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
