package kr.safecross.mobile.ui.screens.crossingassist

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val enableSignalPolling: Boolean = false,
    // 영상 분석(신호등/횡단보도 추정)을 실행할 디스패처. 실기기에서는 Dispatchers.Default를 주입해
    // 메인(UI) 스레드를 막지 않게 하고, null이면 호출 코루틴에서 그대로 실행한다(단위 테스트용).
    private val analysisDispatcher: CoroutineDispatcher? = null,
    // 세로 화면 기준 카메라 세로 화각 (높이 추정용, ADR-0040)
    private val cameraVerticalFovDegrees: Float = 74f
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

    // 녹색 판정 디바운스: GREEN_ESTIMATE가 5프레임 연속 유지될 때만 확정·발화한다.
    // 적색/UNKNOWN은 안전 방향이므로 지연 없이 즉시 반영한다.
    private var candidateState: CrossingAssistDecisionState? = null
    private var consecutiveStateCount = 0
    private var candidateSinceNanos = 0L

    // 음성 재안내 쿨다운 (프레임 시각 기준). 10/02 현장: 적색↔확인불가가 20초에 6회 번갈아 발화됨
    private var lastRedAnnounceNanos = Long.MIN_VALUE / 2
    private var lastUnknownAnnounceNanos = Long.MIN_VALUE / 2
    private val greenConfirmFrames = 5

    // 프레임 처리 중복 방지: 이전 프레임 분석이 끝나기 전에 들어온 프레임은 버린다
    // (카메라 콜백마다 코루틴이 쌓여 지연이 누적되는 것을 방지).
    @Volatile
    private var isFrameInFlight = false

    // 건너편 보행신호등 조준 정보 (길안내 쪽에서 GPS로 계산해 전달, ADR-0040)
    @Volatile
    private var crossingAim: kr.safecross.mobile.navigation.crossing.CrossingAim? = null
    private var lastAimInstruction: kr.safecross.mobile.navigation.crossing.AimInstruction? = null
    private var lastAimSpeechNanos = Long.MIN_VALUE / 2

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

                // 조준 정보가 있으면 방향·각도를 함께 안내하는 조준 안내가 대신한다 (중복 발화 방지)
                if (!guidance.isSuitable && isAnalyzing && crossingAim == null) {
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
        lastRedAnnounceNanos = Long.MIN_VALUE / 2
        lastUnknownAnnounceNanos = Long.MIN_VALUE / 2
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

        if (!isAnalyzing || isFrameInFlight) return
        isFrameInFlight = true

        viewModelScope.launch {
            try {
                // 1. 횡단보도 형상 인식, 2. 보행신호기 인식 (무거운 영상 연산은 분석 디스패처에서 실행)
                val (cwObs, sigObs) = runAnalysis {
                    crosswalkEstimator.estimate(frame) to signalEstimator.estimate(frame)
                }
                if (!isAnalyzing) return@launch

                // 3. 목표 신호 1:1 연결 (Lock-on)
                val currentPose = poseTracker.currentPose.value
                val isTiltOk = poseTracker.tiltGuidance.value.isSuitable
                val rawAssociation = signalAssociator.associate(
                    crossing = activeCrossingContext,
                    devicePose = currentPose,
                    crosswalk = cwObs,
                    signals = sigObs
                )
                // 3-0. 조준 기준: ARCore VPS가 정밀하면 VPS 위치·방향·기울기, 아니면 나침반+보정값·GPS 거리 (ADR-0041)
                val aimInput = resolveAimInput(frame.arContext, currentPose)
                // 3-1. 높이 걸러내기: 보행신호등 높이(1.8~4.5 m)에 있을 수 없는 불빛은 UNKNOWN (ADR-0040)
                val association = applySignalHeightFilter(rawAssociation, currentPose, aimInput)
                // 3-2. 조준 안내: 건너편 보행신호등 방향·각도로 휴대폰을 맞추도록 음성·진동 안내
                val aimResult = kr.safecross.mobile.navigation.crossing.CrossingAimCalculator.evaluate(
                    aimInput.aim, aimInput.headingDeg, aimInput.pitchDeg
                )
                recordArShadow(frame.arContext, association, aimInput)

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

                // 진단 HUD 및 Flight Recorder 기록 (C-ITS 정보 포함)
                val sigStateStr = targetSignal?.state?.name ?: "NONE"
                val sigScoreStr = targetSignal?.let { "%.2f".format(it.score) } ?: "0.00"
                val trackIdStr = targetSignal?.ephemeralTrackId?.takeLast(8) ?: "none"
                val citsTag = latestOfficialSignal?.let { "[C-ITS:${it.state}${officialSignalRemainingSec?.let { s -> " ${s}s" } ?: ""}]" } ?: "[C-ITS:OFF]"

                // 판정 상태 확정(히스테리시스): 화면 배지·박스 색·상태 문구·음성 안내는 모두 이 확정 상태 하나만 따른다.
                // (실측 10/01: 박스 색은 프레임 원시 색, 배지는 판정 상태, 음성은 판정 변화마다 발화해 서로 어긋났음)
                val rawState = normalizeForUser(decision.state)
                val frameNanos = frame.timestampNanos
                if (rawState == candidateState) {
                    consecutiveStateCount++
                } else {
                    candidateState = rawState
                    consecutiveStateCount = 1
                    candidateSinceNanos = frameNanos
                }
                val previousConfirmedState = _uiState.value.decisionState
                val confirmedState = if (isCandidateConfirmed(rawState, previousConfirmedState, frameNanos)) rawState else previousConfirmedState
                val isNewlyConfirmed = confirmedState != previousConfirmedState

                val diagText = "SIG: $sigStateStr ($sigScoreStr) [#$trackIdStr] $citsTag | G-CNT: ${decisionEngine.consecutiveGreenCount}/5 | TILT: ${if (isTiltOk) "OK" else "WARN"} | RET: ${if (isInsideReticle) "IN" else "OUT"} | $lastDepthDiagText\nDEC: ${decision.state.name} (${decision.reasonCode ?: "OK"}) -> SHOWN: ${confirmedState.name}"

                PerceptionFlightRecorder.updateSummary(diagText)
                PerceptionFlightRecorder.record(
                    "FRAME",
                    "Sig=$sigStateStr($sigScoreStr) Trk=$trackIdStr GCount=${decisionEngine.consecutiveGreenCount} Tilt=$isTiltOk Pitch=${"%.0f".format(currentPose.pitchDegrees)} Ret=$isInsideReticle Dec=${decision.state} Reason=${decision.reasonCode} Shown=$confirmedState CITS=${latestOfficialSignal?.state}"
                )

                // 박스 색은 확정 상태 기준으로만 표시 (적색 확정 = 적색 박스, 녹색 확정 = 녹색 박스, 그 외 박스 없음)
                val shownColor = when (confirmedState) {
                    CrossingAssistDecisionState.RED_ESTIMATE -> ObservedSignalState.RED
                    CrossingAssistDecisionState.GREEN_ESTIMATE -> ObservedSignalState.GREEN
                    else -> null
                }
                _uiState.update {
                    it.copy(
                        decisionState = confirmedState,
                        statusMessage = statusTextFor(confirmedState),
                        crosswalkDetected = cwObs.hasCrosswalk,
                        detectedSignalBox = if (shownColor != null) targetBox else null,
                        detectedSignalColor = shownColor,
                        isSignalInReticle = isInsideReticle,
                        debugDiagnosticText = diagText
                    )
                }

                handleAimGuidance(aimResult, confirmedState, isInsideReticle, frameNanos)

                // 5. 발화 안내: 확정 상태가 바뀔 때만, 화면 문구와 같은 내용으로 1회 발화
                if (isNewlyConfirmed) {
                    PerceptionFlightRecorder.record("ANNOUNCE", "$previousConfirmedState -> $confirmedState")
                    when (confirmedState) {
                        CrossingAssistDecisionState.GREEN_ESTIMATE -> if (!hasSpokenCurrentGreenPhase) {
                            hasSpokenCurrentGreenPhase = true
                            lastGreenGuidanceTimeMs = System.currentTimeMillis()
                            val citsInfo = officialSignalRemainingSec?.let { " 잔여 ${it}초." } ?: ""
                            emitGuidance(
                                text = "녹색 신호로 추정됩니다.$citsInfo 좌우를 살피며 횡단하세요. 앱만으로 안전을 보장할 수 없습니다. 건너편에 도착하면 길안내로 자동으로 돌아갑니다.",
                                priority = GuidancePriority.SAFETY,
                                category = "signal_decision_green",
                                hapticType = HapticFeedbackType.GREEN_ESTIMATE
                            )

                            // 출발 알림 후 비전 분석 즉시 동결: 건너는 도중 스마트폰 흔들림으로 인한 판정 핑퐁 방지.
                            // 동결 이후 화면이 마지막 프레임에 "박제"된 것처럼 보이지 않도록 신호등 박스/조준 표시는 비운다.
                            isAnalyzing = false
                            signalPollingJob?.cancel()
                            _uiState.update {
                                it.copy(
                                    statusMessage = "녹색 신호(추정) · 횡단 중입니다. 건너편에 도착하면 길안내로 자동으로 돌아갑니다. (종료 버튼으로도 돌아갈 수 있습니다)",
                                    detectedSignalBox = null,
                                    detectedSignalColor = null,
                                    isSignalInReticle = false
                                )
                            }
                        }
                        CrossingAssistDecisionState.RED_ESTIMATE -> {
                            hasSpokenCurrentGreenPhase = false
                            // 같은 적색을 잠깐 놓쳤다 다시 잡은 경우 8초 이내 재안내는 생략 (화면은 그대로 갱신).
                            // 단, 그 사이 "확인할 수 없습니다"를 안내했다면 적색을 반드시 다시 알린다.
                            val unknownAnnouncedSinceRed = lastUnknownAnnounceNanos > lastRedAnnounceNanos
                            if (unknownAnnouncedSinceRed || frameNanos - lastRedAnnounceNanos >= RED_REANNOUNCE_NANOS) {
                                lastRedAnnounceNanos = frameNanos
                                emitGuidance(
                                    text = statusTextFor(confirmedState),
                                    priority = GuidancePriority.SAFETY,
                                    category = "signal_decision_red",
                                    hapticType = HapticFeedbackType.RED_STOP
                                )
                            }
                        }
                        CrossingAssistDecisionState.UNKNOWN -> {
                            hasSpokenCurrentGreenPhase = false
                            // 확정된 신호를 놓친 경우에만, 10초에 1회까지만 알린다 (탐색 시작 직후의 UNKNOWN은 시작 안내로 충분)
                            val lostConfirmedSignal = previousConfirmedState == CrossingAssistDecisionState.RED_ESTIMATE ||
                                    previousConfirmedState == CrossingAssistDecisionState.GREEN_ESTIMATE
                            if (lostConfirmedSignal && frameNanos - lastUnknownAnnounceNanos >= UNKNOWN_REANNOUNCE_NANOS) {
                                lastUnknownAnnounceNanos = frameNanos
                                emitGuidance(
                                    text = statusTextFor(confirmedState),
                                    priority = GuidancePriority.CROSSING,
                                    category = "signal_decision_unknown",
                                    hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        decisionState = CrossingAssistDecisionState.UNKNOWN,
                        statusMessage = "분석 오류가 발생했습니다. 주변을 직접 확인하세요."
                    )
                }
            } finally {
                isFrameInFlight = false
            }
        }
    }

    /**
     * 사용자에게 노출하는 상태로 정규화한다. GREEN_CANDIDATE는 내부 검증 상태이므로(SR-F-062)
     * 화면·음성에는 UNKNOWN(확인 중)으로만 반영한다.
     */
    private fun normalizeForUser(state: CrossingAssistDecisionState): CrossingAssistDecisionState =
        if (state == CrossingAssistDecisionState.GREEN_CANDIDATE) CrossingAssistDecisionState.UNKNOWN else state

    /**
     * 후보 상태를 확정할지 판단 (실기기 약 6fps 기준).
     * - 녹색: 5프레임 연속(약 0.8초) — 오탐 방지를 위해 가장 엄격
     * - 적색: 2프레임 연속 — 정지 안내는 빠르게
     * - 확정된 적색/녹색에서 UNKNOWN으로: 6프레임 이상 그리고 4초 이상 연속 — 휴대폰 각도 흔들림이나
     *   검출 누락으로 안내가 번갈아 바뀌지 않도록 (녹색 전환은 이 지연과 무관하게 5프레임이면 확정)
     */
    private fun isCandidateConfirmed(
        target: CrossingAssistDecisionState,
        current: CrossingAssistDecisionState,
        frameNanos: Long
    ): Boolean = when (target) {
        CrossingAssistDecisionState.GREEN_ESTIMATE -> consecutiveStateCount >= greenConfirmFrames
        CrossingAssistDecisionState.RED_ESTIMATE -> consecutiveStateCount >= 2
        CrossingAssistDecisionState.UNKNOWN ->
            if (current == CrossingAssistDecisionState.RED_ESTIMATE || current == CrossingAssistDecisionState.GREEN_ESTIMATE) {
                consecutiveStateCount >= 6 && frameNanos - candidateSinceNanos >= LOSE_SIGNAL_HOLD_NANOS
            } else {
                true
            }
        else -> true
    }

    /** 확정 상태별 화면 문구 (적색/확인 불가는 음성 안내와 동일한 문장) */
    private fun statusTextFor(state: CrossingAssistDecisionState): String = when (state) {
        CrossingAssistDecisionState.RED_ESTIMATE -> "적색 신호입니다. 대기하세요."
        CrossingAssistDecisionState.GREEN_ESTIMATE -> "녹색 신호로 추정됩니다. 좌우를 살피며 횡단하세요."
        CrossingAssistDecisionState.UNKNOWN -> "신호를 확인할 수 없습니다. 신호등을 화면 가운데에 맞춰 주세요."
        else -> state.description
    }

    private companion object {
        const val LOSE_SIGNAL_HOLD_NANOS = 4_000_000_000L
        const val RED_REANNOUNCE_NANOS = 8_000_000_000L
        const val UNKNOWN_REANNOUNCE_NANOS = 10_000_000_000L
        const val AIM_MIN_GAP_NANOS = 1_500_000_000L
        const val AIM_REPEAT_NANOS = 4_000_000_000L
    }

    /** 길안내 화면에서 계산한 건너편 보행신호등 조준 정보 갱신 (null이면 조준 안내·높이 걸러내기 비활성) */
    fun updateAim(aim: kr.safecross.mobile.navigation.crossing.CrossingAim?) {
        crossingAim = aim
        if (aim == null) {
            lastAimInstruction = null
            _uiState.update { it.copy(aimHint = null) }
        }
    }

    /**
     * 검출된 신호 불빛의 실제 높이를 추정해 보행신호등 높이 범위 밖이면 UNKNOWN으로 낮춘다.
     * 차량 미등·브레이크등(약 1 m)과 도로 위 차량 신호등(5 m 이상)을 거른다.
     * 조준 정보(건너편까지 거리)가 있고 거리 5~60 m, 휴대폰 기울기가 적정할 때만 적용한다.
     */
    /** 조준 계산 입력 (출처: VPS 또는 나침반) */
    private data class AimInput(
        val aim: kr.safecross.mobile.navigation.crossing.CrossingAim?,
        val headingDeg: Float?,
        val pitchDeg: Float,
        val source: String
    )

    /**
     * ARCore VPS 자세가 정밀(방향 오차 ≤10°, 위치 오차 ≤10 m)하면 VPS 위치에서 건너편 끝까지 방위각·거리를
     * 다시 계산하고 VPS 카메라 방향·기울기를 쓴다. 아니면 나침반(+보정값)·GPS 거리를 쓴다.
     */
    private fun resolveAimInput(
        ar: kr.safecross.mobile.camera.ArFrameContext?,
        pose: kr.safecross.mobile.perception.DevicePose
    ): AimInput {
        val aim = crossingAim
        val geo = ar?.geo?.takeIf { it.isPrecise }
        if (aim != null && geo != null && !aim.farEndLat.isNaN() && !aim.farEndLon.isNaN()) {
            val math = kr.safecross.mobile.navigation.engine.GeoMath
            val vpsAim = aim.copy(
                targetBearingDeg = math.initialBearingDegrees(geo.latitude, geo.longitude, aim.farEndLat, aim.farEndLon),
                distanceMeters = math.distanceMeters(geo.latitude, geo.longitude, aim.farEndLat, aim.farEndLon),
                compassBiasDeg = null
            )
            return AimInput(vpsAim, geo.headingDeg.toFloat(), geo.pitchDeg.toFloat(), "VPS")
        }
        return AimInput(aim, pose.cameraHeadingDegrees, pose.pitchDegrees, if (aim?.compassBiasDeg != null) "COMPASS+BIAS" else "COMPASS")
    }

    /**
     * ARCore 깊이·장면 라벨 섀도 기록 (판정에는 쓰지 않음, ADR-0041): 원거리 소형 신호등은 뒤 건물 라벨로
     * 잡히기 쉬워 바로 판정에 쓰면 진짜 신호를 버릴 위험이 있으므로 현장 데이터를 먼저 모은다.
     */
    private fun recordArShadow(
        ar: kr.safecross.mobile.camera.ArFrameContext?,
        association: kr.safecross.mobile.perception.TargetSignalAssociation,
        aimInput: AimInput
    ) {
        if (ar == null) return
        val target = association.targetSignal?.takeIf { it.state != ObservedSignalState.UNKNOWN }
        val geoText = ar.geo?.let { "vpsH=${"%.1f".format(it.horizontalAccuracyM)} vpsYaw=${"%.1f".format(it.yawAccuracyDeg)}" } ?: "vps=none"
        if (target == null) {
            PerceptionFlightRecorder.record("AR", "$geoText aim=${aimInput.source}")
            return
        }
        val b = target.box
        val depth = ar.depthMetersAt(b.centerX, b.centerY)
        val semantic = ar.semanticHistogram(b.left, b.top, b.right, b.bottom)
            .entries.sortedByDescending { it.value }.take(2)
            .joinToString("/") { (label, frac) ->
                "${kr.safecross.mobile.camera.ArFrameContext.SEMANTIC_LABEL_NAMES.getOrElse(label) { "L$label" }}${(frac * 100).toInt()}%"
            }
        PerceptionFlightRecorder.record(
            "AR",
            "sig=${target.state} box=[${"%.2f".format(b.centerX)},${"%.2f".format(b.centerY)}] depth=${depth?.let { "%.1f".format(it) } ?: "-"}m " +
                    "sem=${semantic.ifEmpty { "-" }} $geoText aim=${aimInput.source} dist=${aimInput.aim?.distanceMeters?.let { "%.0f".format(it) } ?: "-"}"
        )
    }

    private fun applySignalHeightFilter(
        association: kr.safecross.mobile.perception.TargetSignalAssociation,
        pose: kr.safecross.mobile.perception.DevicePose,
        aimInput: AimInput
    ): kr.safecross.mobile.perception.TargetSignalAssociation {
        val aim = aimInput.aim ?: return association
        val target = association.targetSignal ?: return association
        if (target.state == ObservedSignalState.UNKNOWN) return association
        if (aim.distanceMeters !in 5.0..60.0 || kotlin.math.abs(pose.rollDegrees) > 35f) return association

        val calc = kr.safecross.mobile.navigation.crossing.CrossingAimCalculator
        val elevation = calc.detectionElevationDeg(target.box.centerY, aimInput.pitchDeg, cameraVerticalFovDegrees)
        if (calc.isPlausibleSignalElevation(elevation, aim.distanceMeters)) return association

        val height = calc.estimatedHeightMeters(elevation, aim.distanceMeters)
        PerceptionFlightRecorder.record(
            "AIM",
            "HEIGHT_REJECT state=${target.state} elev=${"%.1f".format(elevation)} est=${"%.1f".format(height)}m dist=${"%.0f".format(aim.distanceMeters)}m pitch=${"%.0f".format(aimInput.pitchDeg)} src=${aimInput.source}"
        )
        return association.copy(targetSignal = target.copy(state = ObservedSignalState.UNKNOWN, score = 0.25f))
    }

    /**
     * 조준 안내: 신호를 아직 확정하지 못했고 조준선 밖일 때, 지시가 바뀌면 바로(최소 1.5초 간격),
     * 같은 지시가 이어지면 4초마다 음성 + 진동(왼쪽 짧게 / 오른쪽 길게 / 각도 주의). 맞으면 확인 진동 1회.
     */
    private suspend fun handleAimGuidance(
        aim: kr.safecross.mobile.navigation.crossing.AimResult,
        confirmedState: CrossingAssistDecisionState,
        isInsideReticle: Boolean,
        frameNanos: Long
    ) {
        val instruction = aim.instruction
        if (instruction == kr.safecross.mobile.navigation.crossing.AimInstruction.UNKNOWN) return
        val signalFound = confirmedState == CrossingAssistDecisionState.RED_ESTIMATE ||
                confirmedState == CrossingAssistDecisionState.GREEN_ESTIMATE || isInsideReticle

        _uiState.update { it.copy(aimHint = if (signalFound) null else aim.message) }
        if (signalFound) {
            lastAimInstruction = instruction
            return
        }

        val changed = instruction != lastAimInstruction
        val elapsed = frameNanos - lastAimSpeechNanos
        val isAligned = instruction == kr.safecross.mobile.navigation.crossing.AimInstruction.ALIGNED
        val shouldSpeak = if (isAligned) changed && elapsed >= AIM_MIN_GAP_NANOS
        else (changed && elapsed >= AIM_MIN_GAP_NANOS) || elapsed >= AIM_REPEAT_NANOS
        lastAimInstruction = instruction
        if (!shouldSpeak) return

        lastAimSpeechNanos = frameNanos
        val haptic = when (instruction) {
            kr.safecross.mobile.navigation.crossing.AimInstruction.TURN_LEFT -> HapticFeedbackType.TURN_LEFT
            kr.safecross.mobile.navigation.crossing.AimInstruction.TURN_RIGHT -> HapticFeedbackType.TURN_RIGHT
            kr.safecross.mobile.navigation.crossing.AimInstruction.ALIGNED -> HapticFeedbackType.ORIENTATION_ALIGNED
            else -> HapticFeedbackType.UNKNOWN_CAUTION
        }
        PerceptionFlightRecorder.record(
            "AIM",
            "$instruction h=${aim.horizontalErrorDeg?.let { "%.0f".format(it) }} v=${aim.verticalErrorDeg?.let { "%.0f".format(it) }} " +
                    "dist=${crossingAim?.distanceMeters?.let { "%.0f".format(it) }} bias=${crossingAim?.compassBiasDeg?.let { "%.0f".format(it) } ?: "-"}"
        )
        _effects.emit(CrossingAssistEffect.SpeakGuidance(text = aim.message, hapticType = haptic, queueFlush = false))
    }

    private suspend fun <T> runAnalysis(block: suspend () -> T): T {
        val dispatcher = analysisDispatcher ?: return block()
        return withContext(dispatcher) { block() }
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
                _uiState.update { it.copy(lastDepthRatio = ratio, lastDepthPeakRatio = peakRatio) }
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
