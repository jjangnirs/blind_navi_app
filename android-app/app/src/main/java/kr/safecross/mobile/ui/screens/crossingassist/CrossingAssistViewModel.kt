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
//import kr.safecross.mobile.perception.FakeSignalAssociator
import kr.safecross.mobile.perception.LockOnSignalAssociator
import kr.safecross.mobile.perception.FakeSignalEstimator
import kr.safecross.mobile.perception.PedestrianSignalEstimator
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
    //val signalAssociator: TargetSignalAssociator = FakeSignalAssociator(),
    val signalAssociator: TargetSignalAssociator = LockOnSignalAssociator(),
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

    // ▼▼▼ 여기 새로 추가입니다! ▼▼▼
    private var candidateState: kr.safecross.mobile.decision.CrossingAssistDecisionState? = null
    private var consecutiveStateCount = 0

    init {
        // 기기 기울기 모니터링 구독
        viewModelScope.launch {
            var lastTiltInstruction = "" // 이전 경고 상태 저장

            poseTracker.tiltGuidance.collect { guidance ->
                _uiState.value = _uiState.value.copy(tiltGuidance = guidance)

                // 상태가 부적절하고, 이전 경고 메시지와 다를 때만 1회 실행 (도배 방지)
                if (!guidance.isSuitable && isAnalyzing && guidance.instruction != lastTiltInstruction) {
                    lastTiltInstruction = guidance.instruction

                    val decision = guidanceArbiter.enqueue(
                        kr.safecross.mobile.guidance.GuidanceMessage(
                            id = "tilt_warn_${System.currentTimeMillis()}",
                            text = guidance.instruction,
                            priority = kr.safecross.mobile.guidance.GuidancePriority.INFO,
                            category = "tilt_guidance",
                            hapticType = kr.safecross.mobile.guidance.HapticFeedbackType.UNKNOWN_CAUTION
                        )
                    )

                    // PLAY_IMMEDIATELY, PREEMPT_AND_PLAY뿐만 아니라 QUEUE 상태도 허용
                    when (decision.action) {
                        kr.safecross.mobile.guidance.ArbiterAction.PLAY_IMMEDIATELY,
                        kr.safecross.mobile.guidance.ArbiterAction.PREEMPT_AND_PLAY,
                        kr.safecross.mobile.guidance.ArbiterAction.QUEUE -> {
                            emitGuidance(
                                text = guidance.instruction,
                                priority = kr.safecross.mobile.guidance.GuidancePriority.INFO,
                                category = "tilt_guidance",
                                hapticType = kr.safecross.mobile.guidance.HapticFeedbackType.UNKNOWN_CAUTION
                            )
                        }
                        else -> { /* 쿨다운 중 무시 */ }
                    }
                } else if (guidance.isSuitable) {
                    lastTiltInstruction = "" // 정상 각도로 돌아오면 다시 초기화
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

                val rawState = decision.state

                // 디바운스 로직: 이전 프레임과 상태가 같으면 카운트 증가, 다르면 1부터 다시 시작
                if (rawState == candidateState) {
                    consecutiveStateCount++
                } else {
                    candidateState = rawState
                    consecutiveStateCount = 1
                }

                // 5프레임 이상 동일한 상태가 안정적으로 유지되었을 때만 최종 업데이트 진행
                if (consecutiveStateCount >= 5) {
                    val previousConfirmedState = _uiState.value.decisionState

                    // 1. 화면에 표시될 텍스트를 미리 확정 (null일 경우 기본 설명문 사용)
                    val displayText = decision.guidanceText ?: rawState.description

                    _uiState.value = _uiState.value.copy(
                        decisionState = rawState,
                        crosswalkDetected = cwObs.hasCrosswalk,
                        statusMessage = displayText, // 확정된 텍스트를 화면에 표시
                        detectedSignalBox = association.targetSignal?.box,
                        detectedSignalColor = association.targetSignal?.state
                    )

                    // 2. 발화 안내 이벤트 전송 (텍스트가 비어있지 않고, 상태가 진짜로 바뀌었을 때만 1회 발화)
                    if (displayText.isNotBlank() && rawState != previousConfirmedState) {
                        val priority = when (rawState) {
                            kr.safecross.mobile.decision.CrossingAssistDecisionState.RED_ESTIMATE -> kr.safecross.mobile.guidance.GuidancePriority.SAFETY
                            kr.safecross.mobile.decision.CrossingAssistDecisionState.GREEN_ESTIMATE -> kr.safecross.mobile.guidance.GuidancePriority.SAFETY
                            kr.safecross.mobile.decision.CrossingAssistDecisionState.UNKNOWN -> kr.safecross.mobile.guidance.GuidancePriority.CROSSING
                            else -> kr.safecross.mobile.guidance.GuidancePriority.INFO
                        }

                        val category = "signal_${rawState.name}"

                        val arbiterDecision = guidanceArbiter.enqueue(
                            kr.safecross.mobile.guidance.GuidanceMessage(
                                id = "signal_${System.currentTimeMillis()}",
                                text = displayText,
                                priority = priority,
                                category = category,
                                hapticType = decision.hapticType // 상태에 맞는 진동 패턴 전달
                            )
                        )

                        when (arbiterDecision.action) {
                            kr.safecross.mobile.guidance.ArbiterAction.PLAY_IMMEDIATELY,
                            kr.safecross.mobile.guidance.ArbiterAction.PREEMPT_AND_PLAY,
                            kr.safecross.mobile.guidance.ArbiterAction.QUEUE -> {
                                emitGuidance(
                                    text = displayText,
                                    priority = priority,
                                    category = category,
                                    hapticType = decision.hapticType
                                )
                            }
                            else -> {}
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
