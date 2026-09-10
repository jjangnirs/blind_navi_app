package kr.safecross.mobile.perception

/**
 * 횡단보도 문맥, 기기 자세, 인식된 횡단보도 형상 및 신호 박스를 결합하여
 * 목표 보행신호기를 1:1로 확정하는 연결기 인터페이스 (SR-F-053, SR-F-054, TRD 4.6).
 */
interface TargetSignalAssociator {
    fun associate(
        crossing: VerifiedCrossingContext?,
        devicePose: DevicePose,
        crosswalk: CrosswalkObservation,
        signals: List<SignalObservation>
    ): TargetSignalAssociation
}

/**
 * 모의 연결기 (unique, ambiguous, wrong-direction 시나리오 완비)
 */
class FakeSignalAssociator(
    var scenario: Scenario = Scenario.AUTO_EVALUATE
) : TargetSignalAssociator {

    enum class Scenario {
        AUTO_EVALUATE,
        UNIQUE,
        AMBIGUOUS,
        WRONG_DIRECTION
    }

    override fun associate(
        crossing: VerifiedCrossingContext?,
        devicePose: DevicePose,
        crosswalk: CrosswalkObservation,
        signals: List<SignalObservation>
    ): TargetSignalAssociation {
        // 명시적 강제 시나리오가 지정된 경우
        when (scenario) {
            Scenario.UNIQUE -> {
                val target = signals.firstOrNull()
                return TargetSignalAssociation(
                    isUnique = target != null,
                    targetSignal = target,
                    reason = if (target != null) "MATCHED_UNIQUE_TARGET" else "NO_SIGNALS_DETECTED",
                    confidence = 0.95f
                )
            }
            Scenario.AMBIGUOUS -> {
                return TargetSignalAssociation(
                    isUnique = false,
                    targetSignal = null,
                    reason = "AMBIGUOUS_MULTIPLE_CANDIDATES",
                    confidence = 0.40f
                )
            }
            Scenario.WRONG_DIRECTION -> {
                return TargetSignalAssociation(
                    isUnique = false,
                    targetSignal = null,
                    reason = "DIRECTION_MISMATCH",
                    confidence = 0.20f
                )
            }
            Scenario.AUTO_EVALUATE -> {
                // 1. 횡단보도 문맥 부재 시 즉시 거부 (SR-F-054)
                if (crossing == null) {
                    return TargetSignalAssociation(
                        isUnique = false,
                        targetSignal = null,
                        reason = "NO_CROSSING_CONTEXT",
                        confidence = 0.0f
                    )
                }

                // 2. 검출된 신호가 없는 경우
                if (signals.isEmpty()) {
                    return TargetSignalAssociation(
                        isUnique = false,
                        targetSignal = null,
                        reason = "NO_SIGNALS_DETECTED",
                        confidence = 0.0f
                    )
                }

                // 3. 복수의 보행신호가 있어 목표 방향이 특정되지 않는 경우 (SR-F-046)
                if (signals.size > 1) {
                    return TargetSignalAssociation(
                        isUnique = false,
                        targetSignal = null,
                        reason = "AMBIGUOUS_MULTIPLE_SIGNALS",
                        confidence = 0.30f
                    )
                }

                // 4. 횡단보도 진행 방향과 심각한 각도 차이 발생 (각도오차 > 45도)
                val cwDir = crosswalk.directionDegrees
                if (cwDir != null && kotlin.math.abs(cwDir) > 45.0f) {
                    return TargetSignalAssociation(
                        isUnique = false,
                        targetSignal = null,
                        reason = "CROSSWALK_DIRECTION_MISMATCH",
                        confidence = 0.25f
                    )
                }

                // 5. 단일 신호 정상 정합
                return TargetSignalAssociation(
                    isUnique = true,
                    targetSignal = signals.first(),
                    reason = "SINGLE_TARGET_CONFIRMED",
                    confidence = 0.92f
                )
            }
        }
    }
}
