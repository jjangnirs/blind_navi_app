package kr.safecross.mobile.navigation.crossing

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * 건너려는 횡단보도 건너편 보행신호등을 향한 조준 정보 (ADR-0040).
 * 길안내 쪽에서 현재 GPS 위치와 경로의 건너편 끝 지점으로 계산해 카메라 화면에 전달한다.
 *
 * @param targetBearingDeg 현재 위치 → 건너편 끝(보행신호등 쪽) 방위각
 * @param distanceMeters 현재 위치 → 건너편 끝 수평 거리
 * @param compassBiasDeg 직전 VPS로 잰 나침반 보정값(VPS − 나침반). 없으면 나침반을 그대로 사용
 */
data class CrossingAim(
    val targetBearingDeg: Double,
    val distanceMeters: Double,
    val compassBiasDeg: Double?,
    val updatedAtMs: Long,
    // 건너편 끝 좌표 (ARCore VPS 위치로 방향·거리를 다시 계산할 때 사용, ADR-0041)
    val farEndLat: Double = Double.NaN,
    val farEndLon: Double = Double.NaN
)

/** 조준 안내 지시 */
enum class AimInstruction {
    ALIGNED,
    TURN_LEFT,
    TURN_RIGHT,
    TILT_UP,
    TILT_DOWN,
    UNKNOWN
}

data class AimResult(
    val instruction: AimInstruction,
    val horizontalErrorDeg: Double?,
    val verticalErrorDeg: Double?,
    val expectedElevationDeg: Double
) {
    /** 음성·화면 안내 문구 */
    val message: String
        get() = when (instruction) {
            AimInstruction.ALIGNED -> "신호등 방향입니다. 그대로 유지하세요."
            AimInstruction.TURN_LEFT -> "왼쪽으로 ${roundToFive(kotlin.math.abs(horizontalErrorDeg ?: 0.0))}도 돌리세요."
            AimInstruction.TURN_RIGHT -> "오른쪽으로 ${roundToFive(kotlin.math.abs(horizontalErrorDeg ?: 0.0))}도 돌리세요."
            AimInstruction.TILT_UP -> "휴대폰을 더 세워 앞쪽 위를 비추세요."
            AimInstruction.TILT_DOWN -> "휴대폰을 조금 내리세요."
            AimInstruction.UNKNOWN -> ""
        }

    private fun roundToFive(v: Double): Int = ((v / 5.0).roundToInt() * 5).coerceAtLeast(5)
}

/**
 * 보행신호등 조준·높이 계산 (순수 함수, 단위 테스트 대상).
 *
 * 기하 가정: 보행신호등 등화 중심 높이 약 2.8 m, 휴대폰 높이 약 1.3 m, 신호등은 건너편 끝 부근.
 */
object CrossingAimCalculator {
    const val PHONE_HEIGHT_M = 1.3
    const val SIGNAL_HEIGHT_M = 2.8
    const val MIN_SIGNAL_HEIGHT_M = 1.8
    const val MAX_SIGNAL_HEIGHT_M = 4.5
    const val ELEVATION_TOLERANCE_DEG = 1.5

    const val HORIZONTAL_TOLERANCE_DEG = 15.0
    const val VERTICAL_LOW_TOLERANCE_DEG = 10.0
    const val VERTICAL_HIGH_TOLERANCE_DEG = 15.0
    // 휴대폰이 땅을 향하면(기대 앙각보다 35° 이상 아래) 좌우보다 먼저 세우도록 안내
    // (10/03 현장: 기울기 -43~-82°로 땅을 비춘 채 '오른쪽으로 돌리세요'만 반복, 신호등이 화면에 들어오지 않음)
    const val VERTICAL_SEVERE_LOW_DEG = 35.0

    /** 신호등 높이를 볼 때의 기대 앙각(수평선 기준, 위가 +) */
    fun expectedElevationDeg(distanceMeters: Double, heightM: Double = SIGNAL_HEIGHT_M): Double =
        Math.toDegrees(atan2(heightM - PHONE_HEIGHT_M, distanceMeters.coerceAtLeast(1.0)))

    /**
     * 카메라 정면 방위각(나침반)과 기울기(pitch, 수평 0, 위 +)로 조준 지시를 만든다.
     * 휴대폰이 땅을 향하면 세우기를 가장 먼저, 그다음 좌우가 크게 어긋나면 좌우, 좌우가 맞으면 상하를 안내한다.
     */
    fun evaluate(aim: CrossingAim?, cameraHeadingDeg: Float?, pitchDeg: Float): AimResult {
        if (aim == null || cameraHeadingDeg == null) {
            return AimResult(AimInstruction.UNKNOWN, null, null, 0.0)
        }
        val heading = cameraHeadingDeg + (aim.compassBiasDeg ?: 0.0)
        val horizontalError = wrap(aim.targetBearingDeg - heading) // + = 오른쪽으로 돌려야 함
        val expected = expectedElevationDeg(aim.distanceMeters)
        val verticalError = pitchDeg - expected // + = 너무 위를 봄

        val instruction = when {
            verticalError < -VERTICAL_SEVERE_LOW_DEG -> AimInstruction.TILT_UP
            horizontalError <= -HORIZONTAL_TOLERANCE_DEG -> AimInstruction.TURN_LEFT
            horizontalError >= HORIZONTAL_TOLERANCE_DEG -> AimInstruction.TURN_RIGHT
            verticalError < -VERTICAL_LOW_TOLERANCE_DEG -> AimInstruction.TILT_UP
            verticalError > VERTICAL_HIGH_TOLERANCE_DEG -> AimInstruction.TILT_DOWN
            else -> AimInstruction.ALIGNED
        }
        return AimResult(instruction, horizontalError, verticalError, expected)
    }

    /**
     * 검출된 불빛의 화면 세로 위치(0=위, 1=아래)로 앙각을 구한다 (핀홀 카메라, 세로 화면).
     */
    fun detectionElevationDeg(boxCenterY: Float, pitchDeg: Float, verticalFovDeg: Float): Double {
        val halfFov = Math.toRadians(verticalFovDeg / 2.0)
        val offset = atan((0.5 - boxCenterY) * 2.0 * tan(halfFov))
        return pitchDeg + Math.toDegrees(offset)
    }

    /** 앙각과 거리로 추정한 실제 높이(m) */
    fun estimatedHeightMeters(elevationDeg: Double, distanceMeters: Double): Double =
        PHONE_HEIGHT_M + distanceMeters * tan(Math.toRadians(elevationDeg))

    /**
     * 검출된 불빛이 보행신호등 높이(1.8~4.5 m, 앙각 ±1.5° 허용)에 있을 수 있는지.
     * 차량 미등·브레이크등(약 1 m)과 도로 위 차량 신호등(5 m 이상)을 거른다.
     */
    fun isPlausibleSignalElevation(elevationDeg: Double, distanceMeters: Double): Boolean {
        val low = expectedElevationDeg(distanceMeters, MIN_SIGNAL_HEIGHT_M) - ELEVATION_TOLERANCE_DEG
        val high = expectedElevationDeg(distanceMeters, MAX_SIGNAL_HEIGHT_M) + ELEVATION_TOLERANCE_DEG
        return elevationDeg in low..high
    }

    private fun wrap(deg: Double): Double = ((deg % 360.0) + 540.0) % 360.0 - 180.0
}
