package kr.safecross.mobile.decision.model

import kr.safecross.mobile.guidance.HapticFeedbackType
import kr.safecross.mobile.perception.CrosswalkObservation
import kr.safecross.mobile.perception.DevicePose
import kr.safecross.mobile.perception.TargetSignalAssociation
import kr.safecross.mobile.perception.VerifiedCrossingContext

/**
 * 횡단 보조 안전 8대 상태 (SR-F-060~062, TRD 4.6, 4.7).
 *
 * [상태 전이 흐름]
 * IDLE -> APPROACH -> STOP_REQUIRED -> SCANNING -> RED_ESTIMATE / GREEN_CANDIDATE -> GREEN_ESTIMATE / UNKNOWN
 */
enum class CrossingState(val label: String, val description: String) {
    IDLE("대기", "횡단 보조 대기 중입니다."),
    APPROACH("접근 중", "전방 횡단보도에 접근 중입니다."),
    STOP_REQUIRED("정지 준비", "횡단보도 시작점에 근접했습니다. 멈출 준비를 하세요."),
    SCANNING("신호 탐색 중", "전방 횡단보도와 신호등을 탐색 중입니다."),
    RED_ESTIMATE("적색 신호", "적색 신호가 감지되었습니다. 멈추세요."),
    GREEN_CANDIDATE("녹색 후보 검증 중", "녹색 신호 후보를 분석 중입니다."), // 절대 사용자에게 노출하지 않는 내부 상태 (SR-F-062)
    GREEN_ESTIMATE("녹색 신호 추정", "녹색으로 추정됩니다. 앱만으로 안전을 보장할 수 없습니다."),
    UNKNOWN("확인 불가", "신호 확인이 불가합니다. 주변 소리와 상황을 직접 확인하세요.")
}

/**
 * 기존 인터페이스 호환을 위한 타입 별칭
 */
typealias CrossingAssistDecisionState = CrossingState

/**
 * 공식 실시간 신호 상태 (SR-F-056)
 */
enum class OfficialSignalState {
    RED,
    GREEN,
    UNKNOWN
}

/**
 * 사용자 명시 동작 트리거 (SR-F-040, SR-F-060, SR-F-061)
 */
enum class UserTriggerAction {
    NONE,
    APPROACH_CROSSING,   // 경로상 횡단보도 접근 (40m 이내)
    REACH_STOP_LINE,     // 정지선 근접 (15m 이내)
    START_SCANNING,      // 사용자가 신호 확인 명시적 요청
    CANCEL_OR_BACKGROUND // 취소 또는 앱 백그라운드 전환
}

/**
 * 위치 샘플 데이터 (순수 Kotlin, SR-NF-022, TRD 4.2)
 */
data class DecisionLocation(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val bearingDegrees: Float?,
    val timestampNanos: Long,
    val isMock: Boolean = false
)

/**
 * 공식 실시간 신호 관측치 (SR-F-056, SR-F-057, TRD 4.7)
 */
data class OfficialSignalObservation(
    val provider: String,
    val intersectionId: String,
    val movementId: String,
    val state: OfficialSignalState,
    val sourceTimestampEpochMs: Long,
    val receivedMonotonicNanos: Long,
    val maxAgeNanos: Long = 5_000_000_000L, // 5초
    val optionalRemainingSeconds: Int? = null,
    val qualityFlags: List<String> = emptyList()
) {
    companion object {
        fun fromNormalized(status: kr.safecross.mobile.signal.model.NormalizedSignalStatus, maxAgeNanos: Long = 5_000_000_000L): OfficialSignalObservation {
            return OfficialSignalObservation(
                provider = status.provider,
                intersectionId = status.providerIntersectionId,
                movementId = status.movementId,
                state = status.state,
                sourceTimestampEpochMs = status.sourceTimestampEpochMs,
                receivedMonotonicNanos = status.receivedElapsedRealtimeNanos,
                maxAgeNanos = maxAgeNanos,
                optionalRemainingSeconds = status.optionalRemainingSeconds,
                qualityFlags = status.qualityFlags
            )
        }
    }
}

/**
 * 원격/로컬 킬스위치 상태 (SR-F-051, TRD 4.5)
 */
data class KillSwitchStatus(
    val isKilled: Boolean = false,
    val reason: String? = null,
    val version: String = "1.0.0"
)

/**
 * 횡단 보조 안전 상태기계 종합 입력 스냅샷 (SR-F-066, TRD 4.7)
 *
 * 모든 입력에 단조 시각(monotonic timestamp)과 최대 허용 나이(TTL)를 적용합니다.
 */
data class CrossingDecisionInput(
    val monotonicTimeNanos: Long,
    val crossingContext: VerifiedCrossingContext? = null,
    val location: DecisionLocation? = null,
    val devicePose: DevicePose? = null,
    val crosswalk: CrosswalkObservation? = null,
    val association: TargetSignalAssociation? = null,
    val officialSignal: OfficialSignalObservation? = null,
    val killSwitch: KillSwitchStatus = KillSwitchStatus(),
    val userTrigger: UserTriggerAction = UserTriggerAction.NONE,
    val isTiltSuitable: Boolean = true,
    val distanceToStopLineM: Float? = null,
    val allowOfficialOnlyGreen: Boolean = false // 공식 신호 단독 GREEN 출력은 안전 승인 전까지 기본 OFF (PR-F-016)
)

/**
 * 개인정보 비식별 전이 로그 (SR-F-064, TRD 4.7)
 *
 * 위경도 좌표, bounding box, 카메라 영상 프레임 원문은 일체 기록하지 않으며,
 * 오직 사유 코드(reasonCode)와 엔진 버전만 보존합니다.
 */
data class TransitionLogRecord(
    val monotonicTimeNanos: Long,
    val fromState: CrossingState,
    val toState: CrossingState,
    val reasonCode: String,
    val engineVersion: String
)

/**
 * 안전 결정 결과 (SR-F-062, SR-F-064)
 */
data class CrossingDecisionOutput(
    val state: CrossingState,
    val guidanceText: String?,
    val hapticType: HapticFeedbackType?,
    val isStateChanged: Boolean,
    val reasonCode: String,
    val consecutiveTrackFrames: Int,
    val engineVersion: String,
    val transitionLog: TransitionLogRecord? = null
) {
    val consecutiveGreenCount: Int get() = consecutiveTrackFrames
}

/**
 * 기존 DecisionResult 호환 클래스
 */
typealias DecisionResult = CrossingDecisionOutput
