package kr.safecross.mobile.ui.screens.navigation

import kr.safecross.mobile.domain.model.Maneuver
import kr.safecross.mobile.domain.model.PedestrianRoute
import kr.safecross.mobile.domain.model.WalkingMode

/**
 * 실시간 보행 내비게이션 UI 상태.
 *
 * SR-NF-004 준수:
 * 프로세스 복구 직후 초기 횡단 상태는 무조건 IDLE에서 시작하여
 * 미검증 상태에서의 위험한 횡단 진입을 방지합니다.
 */
data class NavigationUiState(
    val route: PedestrianRoute? = null,
    val currentManeuverIndex: Int = 0,
    val walkingMode: WalkingMode = WalkingMode.IDLE,
    val distanceToNextManeuverMeters: Int = 0,
    val distanceAlongRouteMeters: Int = 0,
    val remainingDistanceMeters: Int = 0,
    val isOffRoute: Boolean = false,
    val isGpsDegraded: Boolean = false,
    val statusAnnouncement: String = "",
    val isFinished: Boolean = false,
    val gpsSignalStrengthPercent: Int = 0,
    val gpsAccuracyMeters: Float = 0f,
    val currentHeadingDegrees: Float = 0f,
    val isOrientationAligned: Boolean = true,
    val alignmentPromptMessage: String = "",
    val currentLocation: kr.safecross.mobile.domain.model.LocationPoint? = null,
    // 저시력 방향 가이드 화살표 각도: 몸(기기) 정면 기준 가야 할 방향 (-180~180, 양수=오른쪽). 산출 불가 시 null
    val relativeDirectionDegrees: Float? = null,
    // 지도 회전 전용 헤딩 (나침반 흔들림을 강하게 평활화한 값)
    val mapHeadingDegrees: Float = 0f,
    // 지도 표시용 위치 (경로 위에 있을 때는 경로선에 맞춘 위치)
    val mapLocation: kr.safecross.mobile.domain.model.LocationPoint? = null,
    // 현재 방향 출처 (ADR-0037): VPS / VPS 보정 나침반 / 나침반
    val headingSource: HeadingSource = HeadingSource.COMPASS,
    // 경로상 위치 기준 다음 분기점 (화살표 아래 문구·거리 표시용)
    val upcomingManeuverIndex: Int? = null,
    val distanceToUpcomingManeuverMeters: Int = 0
) {
    val currentManeuver: Maneuver?
        get() = route?.maneuvers?.getOrNull(currentManeuverIndex)

    val nextManeuver: Maneuver?
        get() = route?.maneuvers?.getOrNull(currentManeuverIndex + 1)

    val currentDirectionAction: kr.safecross.mobile.domain.model.DirectionAction
        get() = kr.safecross.mobile.domain.model.DirectionAction.fromManeuver(currentManeuver)

    /** 아직 도달하지 않은 다음 분기점 (경로상 위치 기준). 산출 전이면 기존 다음 분기점 */
    val upcomingManeuver: Maneuver?
        get() = upcomingManeuverIndex?.let { route?.maneuvers?.getOrNull(it) } ?: nextManeuver ?: currentManeuver

    val upcomingDirectionAction: kr.safecross.mobile.domain.model.DirectionAction
        get() = kr.safecross.mobile.domain.model.DirectionAction.fromManeuver(upcomingManeuver)

    val upcomingDistanceMeters: Int
        get() = if (upcomingManeuverIndex != null) distanceToUpcomingManeuverMeters else distanceToNextManeuverMeters
}

sealed interface NavigationEffect {
    data class SpeakGuidance(
        val text: String,
        val hapticType: kr.safecross.mobile.guidance.HapticFeedbackType? = null,
        val queueFlush: Boolean = false
    ) : NavigationEffect
    data class ShowOffRouteAlert(val message: String) : NavigationEffect
    /** 음성 없이 진동만 (좌회전 짧은 진동 / 우회전 긴 진동 등) */
    data class Haptic(val type: kr.safecross.mobile.guidance.HapticFeedbackType) : NavigationEffect
    data class ShowGpsDegradedAlert(val message: String) : NavigationEffect
    data object NavigationFinished : NavigationEffect
    data object TriggerCrossingAssist : NavigationEffect
}

/**
 * 보행 방향(헤딩) 출처.
 */
enum class HeadingSource(val label: String) {
    VPS("VPS 정밀 방향"),
    VPS_CORRECTED_COMPASS("VPS 보정 나침반"),
    COMPASS("나침반")
}
