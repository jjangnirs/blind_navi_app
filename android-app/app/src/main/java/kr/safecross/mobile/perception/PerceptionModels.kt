package kr.safecross.mobile.perception

/**
 * 2D 평면 정규화 좌표점 (0.0 ~ 1.0)
 */
data class PointF(
    val x: Float,
    val y: Float
)

/**
 * 정규화된 바운딩 박스 (0.0 ~ 1.0)
 */
data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

/**
 * 프레임 화질 및 신뢰성 메타데이터
 */
data class FrameQuality(
    val lighting: Float, // 0.0(극암/역광) ~ 1.0(양호)
    val blur: Float,     // 0.0(심한 흔들림) ~ 1.0(선명)
    val isUsable: Boolean
)

/**
 * 원시 신호 모델 출력 상태 (SR-F-042).
 * GREEN_ESTIMATE는 모델이 출력하지 않으며 오직 안전 결정 상태기계만 생성한다.
 */
enum class ObservedSignalState {
    RED,
    GREEN,
    UNKNOWN
}

/**
 * 단일 보행신호 검출 관측치 (TRD 4.6)
 */
data class SignalObservation(
    val ephemeralTrackId: String,
    val state: ObservedSignalState,
    val score: Float,
    val box: NormalizedBox,
    val frameTimestampNanos: Long,
    val quality: FrameQuality,
    val modelVersion: String = "fake-model-1.0.0"
)

/**
 * 횡단보도 장면 인식 관측치 (SR-F-052, TRD 4.6)
 */
data class CrosswalkObservation(
    val hasCrosswalk: Boolean,
    val polygon: List<PointF>?,
    val entrancePoint: PointF?,
    val directionDegrees: Float?,
    val quality: Float,
    val confidence: Float
)

/**
 * 기기 자세/기울기 센서 정보 (TRD 4.6)
 */
data class DevicePose(
    val pitchDegrees: Float,   // 상하 각도 (-90° 수직 하향 ~ +90° 수직 상향)
    val rollDegrees: Float,    // 좌우 회전 (-180° ~ +180°)
    val headingDegrees: Float, // 나침반 방위각 (0° ~ 360°)
    val timestampNanos: Long = 0L // 단조 시각 타임스탬프 (0L이면 레거시/미제공 호환)
)

/**
 * 현장 검증된 횡단보도 지도 문맥 (SR-F-054, TRD 4.6)
 */
data class VerifiedCrossingContext(
    val crossingId: String,
    val approachBearingDegrees: Float,
    val isFieldVerified: Boolean,
    val isAiAllowed: Boolean = true
)

/**
 * 목표 신호 연결 결과 (TRD 4.6)
 */
data class TargetSignalAssociation(
    val isUnique: Boolean,
    val targetSignal: SignalObservation?,
    val reason: String,
    val confidence: Float
)
