package kr.safecross.mobile.guidance

import kr.safecross.mobile.domain.model.DirectionAction
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 신체 정대 안내 결과.
 */
data class OrientationPrompt(
    val isAligned: Boolean,
    val relativeDegrees: Int,
    val message: String
)

/**
 * 시각장애인 보행 특성에 최적화된 음성 안내 변환 포맷터.
 *
 * 1. 시계 방향(Clock Face): 시선/기기 헤딩 대비 분기점 각도를 1~12시 방향으로 변환
 * 2. 걸음 수 환산(Step Count): 추상적인 미터(m) 거리를 평균 보폭(0.65m) 기반 걸음 수로 변환
 * 3. 시각 단서 정제: TMAP 원문의 상호명, 건물명, "OO방면" 등 시각장애인에게 무의미한 시각 표지 제거
 * 4. 신체 회전각 안내: 제자리 정지 또는 정대 오차 시 좌/우 회전각 제시
 * 5. 안전 원칙 준수: SR-F-048 절대 금지 표현(단정적 안전 수식어 및 보행 강제 지시 표현) 배제
 */
object BlindGuidanceFormatter {

    const val AVERAGE_STEP_LENGTH_METERS = 0.65

    /**
     * 거리를 보폭 기준 걸음 수로 환산합니다.
     */
    fun distanceToSteps(distanceMeters: Double, stepLengthM: Double = AVERAGE_STEP_LENGTH_METERS): Int {
        if (distanceMeters <= 0.0) return 0
        return (distanceMeters / stepLengthM).roundToInt().coerceAtLeast(1)
    }

    /**
     * 거리와 걸음 수를 함께 표현하는 문구를 생성합니다.
     */
    fun formatDistanceWithSteps(distanceMeters: Double): String {
        val distInt = distanceMeters.toInt()
        return if (distInt <= 6) {
            "잠시 후"
        } else {
            val steps = distanceToSteps(distanceMeters)
            "약 ${steps}걸음 앞(${distInt}미터)"
        }
    }

    /**
     * 상대 방위각(-180.0° ~ +180.0° 또는 0.0° ~ 360.0°)을 12개 시계 방향으로 변환합니다.
     */
    fun relativeBearingToClockDirection(relativeBearingDeg: Double): String {
        // 0.0 ~ 360.0 정규화
        val normalized = ((relativeBearingDeg % 360.0) + 360.0) % 360.0

        return when {
            normalized >= 345.0 || normalized < 15.0 -> "12시 방향(정면)"
            normalized in 15.0..<45.0 -> "1시 방향"
            normalized in 45.0..<75.0 -> "2시 방향"
            normalized in 75.0..<105.0 -> "3시 방향(우측)"
            normalized in 105.0..<135.0 -> "4시 방향"
            normalized in 135.0..<165.0 -> "5시 방향"
            normalized in 165.0..<195.0 -> "6시 방향(뒤쪽)"
            normalized in 195.0..<225.0 -> "7시 방향"
            normalized in 225.0..<255.0 -> "8시 방향"
            normalized in 255.0..<285.0 -> "9시 방향(좌측)"
            normalized in 285.0..<315.0 -> "10시 방향"
            normalized in 315.0..<345.0 -> "11시 방향"
            else -> "정면 방향"
        }
    }

    /**
     * TMAP의 시각적 랜드마크(건물명, 상호명, 출구 방면 등)를 제거하고 행동 중심으로 정제합니다.
     */
    fun cleanInstruction(rawInstruction: String?): String {
        if (rawInstruction.isNullOrBlank()) return "직진"

        var cleaned = rawInstruction
            // "OO방면으로", "OO방면", "OO쪽으로" 등 시각 지향 접미사 제거
            .replace(Regex(".*?방면(으로)?\\s*"), "")
            .replace(Regex(".*?출구(으로)?\\s*"), "")
            .trim()

        if (cleaned.isEmpty()) {
            cleaned = rawInstruction
        }

        // 보행 핵심 행동이 누락되지 않도록 정리
        return when {
            cleaned.contains("횡단보도") -> "횡단보도 건너기"
            cleaned.contains("좌회전") -> if (cleaned.contains("약간")) "약간 좌회전" else "좌회전"
            cleaned.contains("우회전") -> if (cleaned.contains("약간")) "약간 우회전" else "우회전"
            cleaned.contains("직진") -> "직진"
            cleaned.contains("유턴") -> "유턴"
            else -> cleaned
        }
    }

    /**
     * 분기점 사전 접근 안내 문구를 생성합니다.
     */
    fun formatApproachGuidance(
        action: DirectionAction,
        distanceMeters: Double,
        relativeBearingDeg: Double? = null
    ): String {
        val distText = formatDistanceWithSteps(distanceMeters)
        val clockText = if (relativeBearingDeg != null) {
            val clock = relativeBearingToClockDirection(relativeBearingDeg)
            if (clock != "12시 방향(정면)") "$clock " else ""
        } else {
            ""
        }

        val actionLabel = when (action) {
            DirectionAction.CROSSWALK -> "횡단보도"
            DirectionAction.LEFT -> "좌회전"
            DirectionAction.RIGHT -> "우회전"
            DirectionAction.SLIGHT_LEFT -> "약간 좌회전"
            DirectionAction.SLIGHT_RIGHT -> "약간 우회전"
            DirectionAction.STRAIGHT -> "직진"
            DirectionAction.DESTINATION -> "목적지"
            else -> action.label
        }

        return if (action == DirectionAction.CROSSWALK) {
            "$distText ${clockText}횡단보도입니다. 멈추어 점자블록을 확인하세요."
        } else {
            "$distText ${clockText}${actionLabel}입니다. 주변을 살피고 보행하세요."
        }
    }

    /**
     * 사용자의 현재 나침반 헤딩과 목표 경로 각도 간의 정대(Alignment) 상태를 평가합니다.
     *
     * @param currentHeadingDeg 현재 스마트폰이 가리키는 방위각 (0~360)
     * @param targetBearingDeg 가야 할 경로의 방위각 (0~360)
     * @param toleranceDeg 정대 인정 허용 오차 각도 (기본 18도)
     */
    fun evaluateOrientation(
        currentHeadingDeg: Double,
        targetBearingDeg: Double,
        toleranceDeg: Double = 18.0
    ): OrientationPrompt {
        // signed diff (-180.0 ~ +180.0): 양수면 오른쪽, 음수면 왼쪽
        val diff = ((targetBearingDeg - currentHeadingDeg + 540.0) % 360.0) - 180.0
        val diffInt = diff.roundToInt()

        return if (abs(diff) <= toleranceDeg) {
            OrientationPrompt(
                isAligned = true,
                relativeDegrees = diffInt,
                message = "올바른 진행 방향입니다. 전방을 주의하며 걸으세요."
            )
        } else if (diff > 0) {
            val clock = relativeBearingToClockDirection(diff)
            OrientationPrompt(
                isAligned = false,
                relativeDegrees = diffInt,
                message = "오른쪽으로 ${abs(diffInt)}도 몸을 돌려 $clock 을 향하세요."
            )
        } else {
            val clock = relativeBearingToClockDirection(diff)
            OrientationPrompt(
                isAligned = false,
                relativeDegrees = diffInt,
                message = "왼쪽으로 ${abs(diffInt)}도 몸을 돌려 $clock 을 향하세요."
            )
        }
    }
}
