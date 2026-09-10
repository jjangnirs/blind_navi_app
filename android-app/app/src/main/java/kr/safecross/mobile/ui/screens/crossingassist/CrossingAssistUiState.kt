package kr.safecross.mobile.ui.screens.crossingassist

import kr.safecross.mobile.decision.CrossingAssistDecisionState
import kr.safecross.mobile.sensor.TiltGuidance

/**
 * 횡단 보조 화면 UI 상태 (SR-F-040, SR-F-070).
 */
data class CrossingAssistUiState(
    val hasCameraPermission: Boolean = false,
    val isCameraBound: Boolean = false,
    val tiltGuidance: TiltGuidance = TiltGuidance.SUITABLE,
    val decisionState: CrossingAssistDecisionState = CrossingAssistDecisionState.IDLE,
    val crosswalkDetected: Boolean = false,
    val isTerminated: Boolean = false,
    val statusMessage: String = "카메라를 횡단보도 전방으로 향해주세요.",
    val detectedSignalBox: kr.safecross.mobile.perception.NormalizedBox? = null,
    val detectedSignalColor: kr.safecross.mobile.perception.ObservedSignalState? = null
)

sealed interface CrossingAssistEffect {
    data class SpeakGuidance(
        val text: String,
        val hapticType: kr.safecross.mobile.guidance.HapticFeedbackType? = null,
        val queueFlush: Boolean = true
    ) : CrossingAssistEffect

    data object FinishScreen : CrossingAssistEffect
}
