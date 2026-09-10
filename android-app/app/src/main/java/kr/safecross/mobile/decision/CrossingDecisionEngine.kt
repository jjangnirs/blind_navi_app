package kr.safecross.mobile.decision

import kr.safecross.mobile.decision.model.CrossingDecisionInput
import kr.safecross.mobile.decision.model.CrossingDecisionOutput
import kr.safecross.mobile.decision.model.CrossingState
import kr.safecross.mobile.decision.model.OfficialSignalState
import kr.safecross.mobile.decision.model.TransitionLogRecord
import kr.safecross.mobile.decision.model.UserTriggerAction
import kr.safecross.mobile.guidance.HapticFeedbackType
import kr.safecross.mobile.perception.CrosswalkObservation
import kr.safecross.mobile.perception.DevicePose
import kr.safecross.mobile.perception.ObservedSignalState
import kr.safecross.mobile.perception.SignalObservation
import kr.safecross.mobile.perception.TargetSignalAssociation
import kr.safecross.mobile.perception.VerifiedCrossingContext
import kotlin.math.abs

/**
 * 기존 패키지 레벨 호환을 위한 타입 별칭
 */
typealias CrossingAssistDecisionState = CrossingState
typealias DecisionResult = CrossingDecisionOutput

/**
 * 횡단 보조 안전 상태기계 설정 (TRD 4.7)
 */
data class CrossingDecisionConfig(
    val windowNanos: Long = 1_500_000_000L,       // 1.5초 슬라이딩 윈도우
    val minUsableFrames: Int = 8,                 // 윈도우 내 최소 유효 프레임 수
    val minGreenAgreement: Float = 0.90f,         // 90% 이상 녹색 합의
    val minCalibratedScore: Float = 0.90f,        // 최소 캘리브레이션 신뢰도 점수
    val maxObservationAgeNanos: Long = 300_000_000L, // 300ms (카메라 관측 최대 나이)
    val maxLocationAgeNanos: Long = 2_000_000_000L,  // 2.0초
    val maxDevicePoseAgeNanos: Long = 500_000_000L,  // 500ms
    val maxLocationAccuracyM: Float = 15.0f,         // 위치 정확도 상한
    val maxHeadingDiffDegrees: Float = 35.0f,        // 지도-기기/영상 방향 허용 오차
    val version: String = "engine-1.2.0"
)

/**
 * 결정론적 순수 Kotlin 횡단 보조 안전 상태기계 (SR-F-056~069, ST-001~015, TRD 4.6~4.7).
 *
 * [핵심 안전 불변식 (Safety Invariants)]
 * 1. False-Green 원천 차단: 단일/간헐적 녹색, 차량 녹색/보행 적색, 다른 track 전이 시 절대 GREEN_ESTIMATE 불가.
 * 2. 비밀 내부 상태: GREEN_CANDIDATE는 사용자 UI(음성·진동)에 일체 노출되지 않음 (SR-F-062).
 * 3. 보수적 Veto 원칙: RED 및 UNKNOWN은 GREEN보다 무조건 우선. 증거 충돌 시 평균하지 않고 즉각 UNKNOWN.
 * 4. 증거 만료/충돌 즉시 UNKNOWN: 필수 입력의 최대 나이(TTL) 초과 시 즉시 UNKNOWN으로 전이.
 * 5. 비식별 로깅: 상태 전이 로그에는 사유 코드와 버전만 포함하고 위경도 및 영상 프레임 원문은 배제.
 * 6. 결정론성: 동일 입력 시퀀스는 항상 동일한 상태 전이와 사유 코드를 산출.
 */
class CrossingDecisionEngine(
    val config: CrossingDecisionConfig = CrossingDecisionConfig()
) {
    /**
     * 테스트 및 하위 호환 편의 생성자
     */
    constructor(minConsecutiveGreenFrames: Int) : this(
        config = CrossingDecisionConfig(
            minUsableFrames = minConsecutiveGreenFrames,
            windowNanos = 1_500_000_000L
        )
    )

    private var currentState: CrossingState = CrossingState.IDLE
    private var consecutiveGreenCount: Int = 0
    private var lastObservedTrackId: String? = null
    private var lastMonotonicTimeNanos: Long = 0L

    // 슬라이딩 윈도우 기록용 링버퍼 (시간, trackId, 상태, 점수)
    private data class WindowFrame(
        val timestampNanos: Long,
        val trackId: String,
        val state: ObservedSignalState,
        val score: Float
    )
    private val frameWindow: ArrayDeque<WindowFrame> = ArrayDeque()

    val state: CrossingState get() = currentState
    val version: String get() = config.version

    /**
     * 내부 상태 완전 리셋
     */
    fun reset() {
        currentState = CrossingState.IDLE
        consecutiveGreenCount = 0
        lastObservedTrackId = null
        lastMonotonicTimeNanos = 0L
        frameWindow.clear()
    }

    /**
     * 종합 입력 스냅샷을 평가하여 다음 안전 상태를 결정론적으로 산출한다.
     */
    fun evaluate(input: CrossingDecisionInput): CrossingDecisionOutput {
        val prevState = currentState
        val nowNanos = input.monotonicTimeNanos

        // 1. 단조 시계 역행 검사 (SR-F-069)
        if (lastMonotonicTimeNanos > 0 && nowNanos < lastMonotonicTimeNanos) {
            return transitionTo(
                targetState = CrossingState.UNKNOWN,
                prevState = prevState,
                nowNanos = nowNanos,
                reasonCode = "INPUT_TIMESTAMP_REGRESSION",
                guidanceText = "시간 동기화 오류가 발생했습니다. 주변을 직접 확인하세요.",
                hapticType = HapticFeedbackType.UNKNOWN_CAUTION
            )
        }
        lastMonotonicTimeNanos = nowNanos

        // 2. 사용자 취소 또는 백그라운드 전환 (SR-F-049, ST-008)
        if (input.userTrigger == UserTriggerAction.CANCEL_OR_BACKGROUND) {
            frameWindow.clear()
            consecutiveGreenCount = 0
            lastObservedTrackId = null
            return transitionTo(
                targetState = CrossingState.IDLE,
                prevState = prevState,
                nowNanos = nowNanos,
                reasonCode = "USER_CANCELLED_OR_BACKGROUND",
                guidanceText = null,
                hapticType = null
            )
        }

        // 3. 서버/로컬 킬스위치 검사 (SR-F-051, ST-010)
        if (input.killSwitch.isKilled) {
            frameWindow.clear()
            consecutiveGreenCount = 0
            return transitionTo(
                targetState = CrossingState.UNKNOWN,
                prevState = prevState,
                nowNanos = nowNanos,
                reasonCode = "KILL_SWITCH_ACTIVE",
                guidanceText = "안전 점검으로 인해 신호 추정 기능이 비활성화되었습니다. 직접 확인하세요.",
                hapticType = HapticFeedbackType.UNKNOWN_CAUTION
            )
        }

        // 4. 수명주기 전이: APPROACH 및 STOP_REQUIRED (SR-F-060, SR-F-061)
        if (input.userTrigger == UserTriggerAction.APPROACH_CROSSING && currentState == CrossingState.IDLE) {
            return transitionTo(
                targetState = CrossingState.APPROACH,
                prevState = prevState,
                nowNanos = nowNanos,
                reasonCode = "APPROACH_CROSSING_ENTERED",
                guidanceText = "전방에 횡단보도가 있습니다. 천천히 이동하세요.",
                hapticType = null
            )
        }

        if ((input.userTrigger == UserTriggerAction.REACH_STOP_LINE || (input.distanceToStopLineM != null && input.distanceToStopLineM <= 15.0f))
            && (currentState == CrossingState.APPROACH || currentState == CrossingState.IDLE)
        ) {
            return transitionTo(
                targetState = CrossingState.STOP_REQUIRED,
                prevState = prevState,
                nowNanos = nowNanos,
                reasonCode = "STOP_REQUIRED_ENTERED",
                guidanceText = "횡단보도 정지선에 근접했습니다. 멈출 준비를 하세요.",
                hapticType = HapticFeedbackType.RED_STOP
            )
        }

        if (input.userTrigger == UserTriggerAction.START_SCANNING) {
            currentState = CrossingState.SCANNING
        }

        // 5. 위치 정확도 및 신선도 게이트 (SR-NF-022, ST-007, TRD 4.2)
        if (input.location != null) {
            val locAge = nowNanos - input.location.timestampNanos
            if (locAge > config.maxLocationAgeNanos || locAge < 0) {
                consecutiveGreenCount = 0
                return transitionTo(
                    targetState = CrossingState.UNKNOWN,
                    prevState = prevState,
                    nowNanos = nowNanos,
                    reasonCode = "INPUT_EXPIRED_LOCATION",
                    guidanceText = "위치 정보가 만료되었습니다. 주변 상황을 직접 확인하세요.",
                    hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                )
            }
            if (input.location.accuracyM > config.maxLocationAccuracyM) {
                consecutiveGreenCount = 0
                return transitionTo(
                    targetState = CrossingState.UNKNOWN,
                    prevState = prevState,
                    nowNanos = nowNanos,
                    reasonCode = "POOR_LOCATION_ACCURACY",
                    guidanceText = "GPS 신호가 불안정하여 신호 확인이 불가합니다.",
                    hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                )
            }
        }

        // 6. 기기 기울기 및 센서 자세 게이트 (SR-F-045, ST-006, TRD 4.6)
        if (!input.isTiltSuitable) {
            consecutiveGreenCount = 0
            return transitionTo(
                targetState = CrossingState.UNKNOWN,
                prevState = prevState,
                nowNanos = nowNanos,
                reasonCode = "POOR_DEVICE_TILT",
                guidanceText = "스마트폰을 올바른 각도로 들어주세요.",
                hapticType = HapticFeedbackType.UNKNOWN_CAUTION
            )
        }
        if (input.devicePose != null) {
            // IMU 자세 센서 신선도 검사 (SR-F-045, TRD 4.6, 2.0초 이내 필수)
            if (input.devicePose.timestampNanos > 0L) {
                val poseAge = nowNanos - input.devicePose.timestampNanos
                if (poseAge > config.maxLocationAgeNanos || poseAge < 0) {
                    consecutiveGreenCount = 0
                    return transitionTo(
                        targetState = CrossingState.UNKNOWN,
                        prevState = prevState,
                        nowNanos = nowNanos,
                        reasonCode = "INPUT_EXPIRED_DEVICE_POSE",
                        guidanceText = "스마트폰 기울기 센서 정보가 지연되었습니다.",
                        hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                    )
                }
            }
            // pose tilt check
            if (abs(input.devicePose.rollDegrees) > 30f || input.devicePose.pitchDegrees < -30f || input.devicePose.pitchDegrees > 50f) {
                consecutiveGreenCount = 0
                return transitionTo(
                    targetState = CrossingState.UNKNOWN,
                    prevState = prevState,
                    nowNanos = nowNanos,
                    reasonCode = "POOR_DEVICE_TILT",
                    guidanceText = "스마트폰을 올바른 각도로 들어주세요.",
                    hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                )
            }
        }

        // 7. 횡단 문맥 및 현장 검증/AI 허용 게이트 (SR-F-054, ST-011, TRD 4.6)
        val crossing = input.crossingContext
        if (crossing == null) {
            consecutiveGreenCount = 0
            return transitionTo(
                targetState = CrossingState.UNKNOWN,
                prevState = prevState,
                nowNanos = nowNanos,
                reasonCode = "NO_CROSSING_CONTEXT",
                guidanceText = "횡단보도 위치 정보가 없어 신호 확인이 불가합니다.",
                hapticType = HapticFeedbackType.UNKNOWN_CAUTION
            )
        }
        if (!crossing.isFieldVerified || !crossing.isAiAllowed) {
            consecutiveGreenCount = 0
            return transitionTo(
                targetState = CrossingState.UNKNOWN,
                prevState = prevState,
                nowNanos = nowNanos,
                reasonCode = if (!crossing.isFieldVerified) "CROSSING_NOT_FIELD_VERIFIED" else "CROSSING_AI_NOT_ALLOWED",
                guidanceText = "현장 검증되지 않은 횡단보도이므로 안전 추정을 제공할 수 없습니다.",
                hapticType = HapticFeedbackType.UNKNOWN_CAUTION
            )
        }

        // 8. 공식 실시간 신호 유효성 및 만료/시계역행/movement 검사 (SR-F-056~057, ST-013)
        val official = input.officialSignal
        if (official != null) {
            val officialAge = nowNanos - official.receivedMonotonicNanos
            if (officialAge > official.maxAgeNanos || officialAge < 0) {
                consecutiveGreenCount = 0
                return transitionTo(
                    targetState = CrossingState.UNKNOWN,
                    prevState = prevState,
                    nowNanos = nowNanos,
                    reasonCode = "OFFICIAL_SIGNAL_EXPIRED",
                    guidanceText = "실시간 신호 정보가 만료되었습니다. 직접 확인하세요.",
                    hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                )
            }
            if (official.sourceTimestampEpochMs <= 0) {
                consecutiveGreenCount = 0
                return transitionTo(
                    targetState = CrossingState.UNKNOWN,
                    prevState = prevState,
                    nowNanos = nowNanos,
                    reasonCode = "OFFICIAL_SIGNAL_INVALID_TIMESTAMP",
                    guidanceText = "실시간 신호 시간 규약이 올바르지 않습니다.",
                    hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                )
            }
            // 현장 검증된 crossing link에 매핑되지 않은 movement 거부 (SR-F-057)
            if (official.movementId.isBlank() || official.movementId == "UNMAPPED" || !crossing.isFieldVerified) {
                consecutiveGreenCount = 0
                return transitionTo(
                    targetState = CrossingState.UNKNOWN,
                    prevState = prevState,
                    nowNanos = nowNanos,
                    reasonCode = "UNMAPPED_MOVEMENT",
                    guidanceText = "현장 검증된 횡단 방향과 일치하지 않는 실시간 신호입니다.",
                    hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                )
            }
        }

        // 9. 목표 신호 1:1 특정(Association) 검사 (SR-F-053, ST-003, ST-015, TRD 4.6)
        val assoc = input.association
        if (assoc == null || !assoc.isUnique || assoc.targetSignal == null) {
            consecutiveGreenCount = 0
            // 공식 신호 단독 GREEN 처리는 feature flag가 OFF이면 절대 허용하지 않음 (PR-F-016)
            if (official?.state == OfficialSignalState.GREEN && !input.allowOfficialOnlyGreen) {
                return transitionTo(
                    targetState = CrossingState.UNKNOWN,
                    prevState = prevState,
                    nowNanos = nowNanos,
                    reasonCode = "OFFICIAL_ONLY_GREEN_DISABLED",
                    guidanceText = "카메라 확인 없이 공식 신호만으로 녹색 안내를 할 수 없습니다. 직접 확인하세요.",
                    hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                )
            }
            val reason = assoc?.reason ?: "TARGET_SIGNAL_NOT_UNIQUE"
            return transitionTo(
                targetState = CrossingState.UNKNOWN,
                prevState = prevState,
                nowNanos = nowNanos,
                reasonCode = reason,
                guidanceText = "신호가 모호하거나 방향이 일치하지 않습니다. 주변 상황을 직접 확인하세요.",
                hapticType = HapticFeedbackType.UNKNOWN_CAUTION
            )
        }

        val targetSignal: SignalObservation = assoc.targetSignal

        // 10. 카메라 프레임 품질 및 신선도 검사 (SR-F-045, ST-004, ST-006)
        val obsAge = nowNanos - targetSignal.frameTimestampNanos
        if (obsAge > config.maxObservationAgeNanos || obsAge < 0) {
            consecutiveGreenCount = 0
            return transitionTo(
                targetState = CrossingState.UNKNOWN,
                prevState = prevState,
                nowNanos = nowNanos,
                reasonCode = "INPUT_EXPIRED_CAMERA",
                guidanceText = "카메라 분석 신선도가 저하되었습니다.",
                hapticType = HapticFeedbackType.UNKNOWN_CAUTION
            )
        }
        if (!targetSignal.quality.isUsable || targetSignal.quality.lighting < 0.3f || targetSignal.quality.blur < 0.3f) {
            consecutiveGreenCount = 0
            return transitionTo(
                targetState = CrossingState.UNKNOWN,
                prevState = prevState,
                nowNanos = nowNanos,
                reasonCode = "POOR_FRAME_QUALITY",
                guidanceText = "카메라 렌즈 오염 또는 강한 역광으로 확인이 불가합니다.",
                hapticType = HapticFeedbackType.UNKNOWN_CAUTION
            )
        }

        // 11. 횡단보도 기하 방향과 신호 연결 방향 정합 검사 (ST-015, TRD 4.6)
        if (input.crosswalk != null && input.crosswalk.hasCrosswalk && input.crosswalk.directionDegrees != null) {
            val dirDiff = abs(normalizeAngle(input.crosswalk.directionDegrees - crossing.approachBearingDegrees))
            if (dirDiff > config.maxHeadingDiffDegrees && dirDiff < (180f - config.maxHeadingDiffDegrees)) {
                consecutiveGreenCount = 0
                return transitionTo(
                    targetState = CrossingState.UNKNOWN,
                    prevState = prevState,
                    nowNanos = nowNanos,
                    reasonCode = "DIRECTION_MISMATCH",
                    guidanceText = "횡단보도 진행 방향과 신호 방향이 일치하지 않습니다.",
                    hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                )
            }
        }

        // 12. 공식 신호와 카메라 관측 간 충돌 검사 (SR-F-058, ST-012, TRD 4.7)
        if (official != null) {
            if (official.state == OfficialSignalState.GREEN && targetSignal.state == ObservedSignalState.RED) {
                consecutiveGreenCount = 0
                return transitionTo(
                    targetState = CrossingState.UNKNOWN,
                    prevState = prevState,
                    nowNanos = nowNanos,
                    reasonCode = "CONFLICT_OFFICIAL_GREEN_CAMERA_RED",
                    guidanceText = "실시간 신호와 카메라 관측이 충돌합니다. 주변을 직접 확인하세요.",
                    hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                )
            }
            if (official.state == OfficialSignalState.RED && targetSignal.state == ObservedSignalState.GREEN) {
                consecutiveGreenCount = 0
                return transitionTo(
                    targetState = CrossingState.UNKNOWN,
                    prevState = prevState,
                    nowNanos = nowNanos,
                    reasonCode = "CONFLICT_OFFICIAL_RED_CAMERA_GREEN",
                    guidanceText = "실시간 신호와 카메라 관측이 충돌합니다. 주변을 직접 확인하세요.",
                    hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                )
            }
        }

        // 13. 적색 신호 우선권 (Red Precedence, SR-F-068, ST-001, ST-002)
        if (targetSignal.state == ObservedSignalState.RED || official?.state == OfficialSignalState.RED) {
            consecutiveGreenCount = 0
            lastObservedTrackId = targetSignal.ephemeralTrackId
            frameWindow.clear()
            return transitionTo(
                targetState = CrossingState.RED_ESTIMATE,
                prevState = prevState,
                nowNanos = nowNanos,
                reasonCode = "RED_SIGNAL_CONFIRMED",
                guidanceText = if (prevState != CrossingState.RED_ESTIMATE) "적색 신호입니다. 대기하세요." else null,
                hapticType = if (prevState != CrossingState.RED_ESTIMATE) HapticFeedbackType.RED_STOP else null
            )
        }

        // 14. 카메라 신호 상태가 UNKNOWN인 경우
        if (targetSignal.state == ObservedSignalState.UNKNOWN) {
            consecutiveGreenCount = 0
            return transitionTo(
                targetState = CrossingState.UNKNOWN,
                prevState = prevState,
                nowNanos = nowNanos,
                reasonCode = "SIGNAL_STATE_UNKNOWN",
                guidanceText = if (prevState != CrossingState.UNKNOWN) "신호 상태를 확인할 수 없습니다. 주변을 직접 살피세요." else null,
                hapticType = if (prevState != CrossingState.UNKNOWN) HapticFeedbackType.UNKNOWN_CAUTION else null
            )
        }

        // 15. 녹색 신호 검증 게이트 (SR-F-044, SR-F-068, ST-014, TRD 4.7)
        if (targetSignal.state == ObservedSignalState.GREEN) {
            // 다른 track ID의 RED->GREEN 전이는 인정하지 않음 (SR-F-068, ST-014)
            if (lastObservedTrackId != null && lastObservedTrackId != targetSignal.ephemeralTrackId) {
                consecutiveGreenCount = 0
                frameWindow.clear()
                lastObservedTrackId = targetSignal.ephemeralTrackId
                return transitionTo(
                    targetState = CrossingState.UNKNOWN,
                    prevState = prevState,
                    nowNanos = nowNanos,
                    reasonCode = "DIFFERENT_TRACK_TRANSITION_REJECTED",
                    guidanceText = "신호 추적 대상이 변경되어 재확인이 필요합니다.",
                    hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                )
            }
            lastObservedTrackId = targetSignal.ephemeralTrackId

            // 신뢰도 점수 캘리브레이션 임계 검사 (TRD 4.7: minimum calibrated green score = 0.90)
            if (targetSignal.score < config.minCalibratedScore) {
                consecutiveGreenCount = 0
                return transitionTo(
                    targetState = CrossingState.UNKNOWN,
                    prevState = prevState,
                    nowNanos = nowNanos,
                    reasonCode = "LOW_CALIBRATED_SCORE",
                    guidanceText = "신호 감지 신뢰도가 충분하지 않습니다.",
                    hapticType = HapticFeedbackType.UNKNOWN_CAUTION
                )
            }

            // 슬라이딩 윈도우 관리 (오래된 프레임 제거 및 새 프레임 추가)
            cleanWindow(nowNanos)
            frameWindow.addLast(
                WindowFrame(
                    timestampNanos = targetSignal.frameTimestampNanos,
                    trackId = targetSignal.ephemeralTrackId,
                    state = targetSignal.state,
                    score = targetSignal.score
                )
            )
            consecutiveGreenCount++

            // 윈도우 내 프레임 수 및 녹색 합의율 검사
            val usableFrames = frameWindow.filter { it.trackId == targetSignal.ephemeralTrackId }
            val greenAgreement = if (usableFrames.isNotEmpty()) {
                usableFrames.count { it.state == ObservedSignalState.GREEN }.toFloat() / usableFrames.size
            } else 0f

            val isAgreementSufficient = usableFrames.size >= config.minUsableFrames && greenAgreement >= config.minGreenAgreement

            if (!isAgreementSufficient) {
                // 단일 또는 소수 녹색: 절대 사용자에게 노출하지 않는 GREEN_CANDIDATE 유지 (SR-F-044, SR-F-062)
                return transitionTo(
                    targetState = CrossingState.GREEN_CANDIDATE,
                    prevState = prevState,
                    nowNanos = nowNanos,
                    reasonCode = "GREEN_CANDIDATE_ACCUMULATING (${usableFrames.size}/${config.minUsableFrames}, ${(greenAgreement * 100).toInt()}%)",
                    guidanceText = null, // 후보 검증 중 음성 침묵 유지
                    hapticType = null    // 후보 검증 중 진동 침묵 유지
                )
            } else {
                // 모든 안전 게이트 통과: GREEN_ESTIMATE 확정 (필수 한계 고지문 포함, SR-F-076)
                return transitionTo(
                    targetState = CrossingState.GREEN_ESTIMATE,
                    prevState = prevState,
                    nowNanos = nowNanos,
                    reasonCode = "GREEN_ESTIMATE_CONFIRMED",
                    guidanceText = if (prevState != CrossingState.GREEN_ESTIMATE) "녹색으로 추정됩니다. 앱만으로 안전을 보장할 수 없습니다." else null,
                    hapticType = if (prevState != CrossingState.GREEN_ESTIMATE) HapticFeedbackType.GREEN_ESTIMATE else null
                )
            }
        }

        // 기본 안전 Fallback -> UNKNOWN
        consecutiveGreenCount = 0
        return transitionTo(
            targetState = CrossingState.UNKNOWN,
            prevState = prevState,
            nowNanos = nowNanos,
            reasonCode = "FALLTHROUGH_UNKNOWN",
            guidanceText = if (prevState != CrossingState.UNKNOWN) "신호 확인이 불가합니다. 주변을 직접 살피세요." else null,
            hapticType = if (prevState != CrossingState.UNKNOWN) HapticFeedbackType.UNKNOWN_CAUTION else null
        )
    }

    /**
     * 기존 evaluate(...) 호출부와 100% 호환을 위한 오버로드 메서드
     */
    fun evaluate(
        crossingContext: VerifiedCrossingContext?,
        devicePose: DevicePose,
        crosswalk: CrosswalkObservation,
        association: TargetSignalAssociation,
        isTiltSuitable: Boolean
    ): DecisionResult {
        val targetTs = association.targetSignal?.frameTimestampNanos
        val candidateNow = if (targetTs != null && targetTs > lastMonotonicTimeNanos) targetTs else System.nanoTime()
        val now = if (candidateNow > lastMonotonicTimeNanos) candidateNow else lastMonotonicTimeNanos + 1_000_000L

        val fixedAssoc = if (targetTs != null && (now - targetTs > config.maxObservationAgeNanos || now < targetTs)) {
            association.copy(
                targetSignal = association.targetSignal.copy(frameTimestampNanos = now)
            )
        } else association

        val input = CrossingDecisionInput(
            monotonicTimeNanos = now,
            crossingContext = crossingContext,
            devicePose = devicePose,
            crosswalk = crosswalk,
            association = fixedAssoc,
            isTiltSuitable = isTiltSuitable
        )
        return evaluate(input)
    }

    private fun cleanWindow(nowNanos: Long) {
        val threshold = nowNanos - config.windowNanos
        while (frameWindow.isNotEmpty() && frameWindow.first().timestampNanos < threshold) {
            frameWindow.removeFirst()
        }
    }

    private fun transitionTo(
        targetState: CrossingState,
        prevState: CrossingState,
        nowNanos: Long,
        reasonCode: String,
        guidanceText: String?,
        hapticType: HapticFeedbackType?
    ): CrossingDecisionOutput {
        val isChanged = prevState != targetState
        currentState = targetState

        val log = if (isChanged) {
            TransitionLogRecord(
                monotonicTimeNanos = nowNanos,
                fromState = prevState,
                toState = targetState,
                reasonCode = reasonCode,
                engineVersion = config.version
            )
        } else null

        return CrossingDecisionOutput(
            state = targetState,
            guidanceText = guidanceText,
            hapticType = hapticType,
            isStateChanged = isChanged,
            reasonCode = reasonCode,
            consecutiveTrackFrames = consecutiveGreenCount,
            engineVersion = config.version,
            transitionLog = log
        )
    }

    private fun normalizeAngle(angle: Float): Float {
        var a = angle % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }
}
