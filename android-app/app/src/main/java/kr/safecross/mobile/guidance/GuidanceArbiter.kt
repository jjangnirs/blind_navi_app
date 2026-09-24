package kr.safecross.mobile.guidance

import java.util.PriorityQueue

/**
 * 중재기 판정 결과 액션.
 */
enum class ArbiterAction {
    PLAY_IMMEDIATELY,
    PREEMPT_AND_PLAY,
    QUEUE,
    SUPPRESSED_COOLDOWN,
    DROPPED_EXPIRED
}

/**
 * 중재기 판정 결과.
 */
data class ArbiterDecision(
    val action: ArbiterAction,
    val message: GuidanceMessage?,
    val droppedMessageIds: List<String> = emptyList()
)

/**
 * 음성·진동 안내 중재기 (순수 Kotlin 모듈, TRD 4.8 준수).
 *
 * SAFETY > CROSSING > ROUTE > INFO 우선순위 관리,
 * 긴급 안전 메시지 선점(Preemption) 및 오래된 길안내 큐 제거,
 * 카테고리별 쿨다운 반복 억제, 다시 듣기 및 전체 중지를 관장합니다.
 */
class GuidanceArbiter(
    private val safetyCooldownMs: Long = 3_000L,
    private val crossingCooldownMs: Long = 15_000L,
    private val routeCooldownMs: Long = 10_000L,
    private val infoCooldownMs: Long = 30_000L
) {
    // 우선순위 큐: 레벨 높은 순(내림차순) -> 같은 레벨이면 먼저 들어온 순(오름차순)
    private val messageQueue = PriorityQueue<GuidanceMessage> { m1, m2 ->
        val levelDiff = m2.priority.level.compareTo(m1.priority.level)
        if (levelDiff != 0) levelDiff else m1.timestampMs.compareTo(m2.timestampMs)
    }

    // 카테고리별 마지막 발화 시각
    private val lastSpokenTimestamps = mutableMapOf<String, Long>()

    // 마지막으로 발화된 안내 메시지 (다시 듣기 지원)
    private var lastSpokenMessage: GuidanceMessage? = null

    // 현재 발화 중인 메시지
    private var currentlySpeakingMessage: GuidanceMessage? = null

    /**
     * 신규 안내 메시지를 중재 큐에 인입하고 판정 결과를 반환합니다.
     */
    @Synchronized
    fun enqueue(
        message: GuidanceMessage,
        currentTimeMs: Long = System.currentTimeMillis()
    ): ArbiterDecision {
        // 1. 만료 메시지 검사
        if (message.isExpired(currentTimeMs)) {
            return ArbiterDecision(
                action = ArbiterAction.DROPPED_EXPIRED,
                message = message
            )
        }

        // 2. 카테고리 쿨다운 검사
        val cooldown = getCooldownForPriority(message.priority)
        val lastTime = lastSpokenTimestamps[message.category]
        if (lastTime != null && (currentTimeMs - lastTime) < cooldown) {
            return ArbiterDecision(
                action = ArbiterAction.SUPPRESSED_COOLDOWN,
                message = message
            )
        }

        // 3. 만료된 대기 메시지 정리
        purgeExpiredMessages(currentTimeMs)

        // 4. 긴급 안전 메시지(SAFETY) 인입 시: 대기 중인 낮은 우선순위(ROUTE, INFO) 메시지 일괄 제거(Preemption)
        val droppedIds = mutableListOf<String>()
        if (message.priority == GuidancePriority.SAFETY) {
            val iterator = messageQueue.iterator()
            while (iterator.hasNext()) {
                val queued = iterator.next()
                if (queued.priority.level < GuidancePriority.CROSSING.level) {
                    droppedIds.add(queued.id)
                    iterator.remove()
                }
            }

            // 현재 발화 중인 메시지가 낮은 우선순위(ROUTE, INFO)이거나, 적색 발화 중 녹색 신호로 전환된 경우 즉시 선점 중단 판정
            val cur = currentlySpeakingMessage
            val isGreenOverridingRed = (message.category == "signal_decision_green" || message.text.contains("녹색")) &&
                    (cur?.category == "signal_decision_red" || cur?.text?.contains("적색") == true)
            if (cur != null && (cur.priority.level < GuidancePriority.CROSSING.level || isGreenOverridingRed)) {
                recordSpoken(message, currentTimeMs)
                return ArbiterDecision(
                    action = ArbiterAction.PREEMPT_AND_PLAY,
                    message = message,
                    droppedMessageIds = droppedIds
                )
            }
        }

        // 5. 현재 발화 중인 메시지가 없는 경우 즉시 재생
        if (currentlySpeakingMessage == null) {
            recordSpoken(message, currentTimeMs)
            return ArbiterDecision(
                action = ArbiterAction.PLAY_IMMEDIATELY,
                message = message,
                droppedMessageIds = droppedIds
            )
        }

        // 6. 현재 발화 중인 메시지보다 더 높은 우선순위인 경우 선점 재생
        val cur = currentlySpeakingMessage
        if (cur != null && message.priority.level > cur.priority.level) {
            recordSpoken(message, currentTimeMs)
            return ArbiterDecision(
                action = ArbiterAction.PREEMPT_AND_PLAY,
                message = message,
                droppedMessageIds = droppedIds
            )
        }

        // 7. 그 외: 큐에 대기 등록
        messageQueue.offer(message)
        return ArbiterDecision(
            action = ArbiterAction.QUEUE,
            message = message,
            droppedMessageIds = droppedIds
        )
    }

    /**
     * 발화가 완료되었을 때 호출하여 다음 대기 메시지를 꺼냅니다.
     */
    @Synchronized
    fun onSpeechCompleted(currentTimeMs: Long = System.currentTimeMillis()): GuidanceMessage? {
        currentlySpeakingMessage = null
        purgeExpiredMessages(currentTimeMs)

        val next = messageQueue.poll()
        if (next != null) {
            recordSpoken(next, currentTimeMs)
        }
        return next
    }

    /**
     * "다시 듣기": 마지막으로 발화된 주요 메시지를 쿨다운과 무관하게 반환합니다.
     */
    @Synchronized
    fun repeatLastGuidance(): GuidanceMessage? {
        return lastSpokenMessage
    }

    /**
     * 전체 중지: 대기 큐를 모두 비우고 현재 발화 상태를 초기화합니다.
     */
    @Synchronized
    fun stopAll() {
        messageQueue.clear()
        currentlySpeakingMessage = null
    }

    /**
     * 현재 큐에 대기 중인 메시지 개수.
     */
    @Synchronized
    fun getQueueSize(): Int = messageQueue.size

    private fun recordSpoken(message: GuidanceMessage, currentTimeMs: Long) {
        currentlySpeakingMessage = message
        lastSpokenMessage = message
        lastSpokenTimestamps[message.category] = currentTimeMs
    }

    private fun purgeExpiredMessages(currentTimeMs: Long) {
        val it = messageQueue.iterator()
        while (it.hasNext()) {
            if (it.next().isExpired(currentTimeMs)) {
                it.remove()
            }
        }
    }

    private fun getCooldownForPriority(priority: GuidancePriority): Long {
        return when (priority) {
            GuidancePriority.SAFETY -> safetyCooldownMs
            GuidancePriority.CROSSING -> crossingCooldownMs
            GuidancePriority.ROUTE -> routeCooldownMs
            GuidancePriority.INFO -> infoCooldownMs
        }
    }
}
