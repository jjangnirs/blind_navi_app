package kr.safecross.mobile.guidance

/**
 * 햅틱 진동 피드백 어휘 (Vibration Vocabulary, SR-F-073 & TRD 4.8 준수).
 *
 * 시각장애인 보행자가 단말기를 주머니에 넣거나 손에 쥐었을 때,
 * 진동 패턴만으로 신호 추정 상태 및 안전 경고를 즉각 직관적으로 구별할 수 있도록 설계되었습니다.
 */
enum class HapticFeedbackType(
    val description: String,
    val patternMs: LongArray,
    val amplitudes: IntArray
) {
    /**
     * 적색 신호 추정 / 정지 요구.
     * 강하고 단호한 2회 정지 진동 (400ms 진동 - 150ms 쉼 - 400ms 진동).
     */
    RED_STOP(
        description = "적색 신호 정지 (강한 2회 진동)",
        patternMs = longArrayOf(0, 400, 150, 400),
        amplitudes = intArrayOf(0, 255, 0, 255)
    ),

    /**
     * 녹색 신호 추정 (한계 포함).
     * 경쾌하고 리드미컬한 3회 진동 (100ms - 100ms 쉼 - 100ms - 100ms 쉼 - 150ms).
     */
    GREEN_ESTIMATE(
        description = "녹색 신호 추정 (경쾌한 3회 진동)",
        patternMs = longArrayOf(0, 100, 100, 100, 100, 150),
        amplitudes = intArrayOf(0, 180, 0, 180, 0, 220)
    ),

    /**
     * 모호 / 확인 불가 / 주의.
     * 은은하고 부드러운 단일 주의 진동 (200ms).
     */
    UNKNOWN_CAUTION(
        description = "신호 확인 불가 및 주의 (단일 진동)",
        patternMs = longArrayOf(0, 200),
        amplitudes = intArrayOf(0, 120)
    ),

    /**
     * 안전 경고 / 경로 이탈 / GPS 신호 불량.
     * 고강도 연속 3회 긴급 경고 진동 (500ms - 200ms 쉼 - 500ms - 200ms 쉼 - 500ms).
     */
    SAFETY_WARNING(
        description = "안전 긴급 경고 (고강도 3회 진동)",
        patternMs = longArrayOf(0, 500, 200, 500, 200, 500),
        amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
    )
}
