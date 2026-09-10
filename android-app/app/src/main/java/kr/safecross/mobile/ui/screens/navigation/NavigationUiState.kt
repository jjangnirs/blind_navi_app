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
    val gpsAccuracyMeters: Float = 0f
) {
    val currentManeuver: Maneuver?
        get() = route?.maneuvers?.getOrNull(currentManeuverIndex)

    val nextManeuver: Maneuver?
        get() = route?.maneuvers?.getOrNull(currentManeuverIndex + 1)

    val currentDirectionAction: kr.safecross.mobile.domain.model.DirectionAction
        get() = kr.safecross.mobile.domain.model.DirectionAction.fromManeuver(currentManeuver)
}

sealed interface NavigationEffect {
    data class SpeakGuidance(
        val text: String,
        val hapticType: kr.safecross.mobile.guidance.HapticFeedbackType? = null,
        val queueFlush: Boolean = false
    ) : NavigationEffect
    data class ShowOffRouteAlert(val message: String) : NavigationEffect
    data class ShowGpsDegradedAlert(val message: String) : NavigationEffect
    data object NavigationFinished : NavigationEffect
}
