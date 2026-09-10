package kr.safecross.mobile.guidance

/**
 * 중재 대상 음성·진동 안내 메시지 모델.
 */
data class GuidanceMessage(
    val id: String,
    val text: String,
    val priority: GuidancePriority,
    val category: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val ttlMs: Long = 10_000L,
    val hapticType: HapticFeedbackType? = null
) {
    /**
     * 유효 시간 만료 여부 판정.
     */
    fun isExpired(currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        return (currentTimeMs - timestampMs) > ttlMs
    }
}
