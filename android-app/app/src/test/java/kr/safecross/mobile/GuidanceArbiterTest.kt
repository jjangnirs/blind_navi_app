package kr.safecross.mobile

import kr.safecross.mobile.guidance.ArbiterAction
import kr.safecross.mobile.guidance.GuidanceArbiter
import kr.safecross.mobile.guidance.GuidanceMessage
import kr.safecross.mobile.guidance.GuidancePriority
import kr.safecross.mobile.guidance.HapticFeedbackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceArbiterTest {

    @Test
    fun testPriorityOrderSafetyPreemptsRouteAndPurgesQueue() {
        val arbiter = GuidanceArbiter(
            safetyCooldownMs = 1_000L,
            routeCooldownMs = 1_000L
        )
        val now = 100_000L

        // 1. ROUTE 메시지 인입 -> 즉시 발화
        val routeMsg1 = GuidanceMessage(
            id = "route_1",
            text = "200미터 직진하세요.",
            priority = GuidancePriority.ROUTE,
            category = "route_step",
            timestampMs = now
        )
        val decision1 = arbiter.enqueue(routeMsg1, now)
        assertEquals(ArbiterAction.PLAY_IMMEDIATELY, decision1.action)

        // 2. 다른 ROUTE 메시지 인입 -> 대기 큐에 저장
        val routeMsg2 = GuidanceMessage(
            id = "route_2",
            text = "다음 교차로에서 우회전하세요.",
            priority = GuidancePriority.ROUTE,
            category = "route_step_2",
            timestampMs = now + 100L
        )
        val decision2 = arbiter.enqueue(routeMsg2, now + 100L)
        assertEquals(ArbiterAction.QUEUE, decision2.action)
        assertEquals(1, arbiter.getQueueSize())

        // 3. 긴급 SAFETY 메시지 인입 -> 대기 큐의 ROUTE 메시지를 일괄 제거하고 현재 발화 선점(Preempt)
        val safetyMsg = GuidanceMessage(
            id = "safety_1",
            text = "경로를 벗어났습니다. 경로를 다시 확인하세요.",
            priority = GuidancePriority.SAFETY,
            category = "safety_alert",
            timestampMs = now + 200L,
            hapticType = HapticFeedbackType.SAFETY_WARNING
        )
        val decision3 = arbiter.enqueue(safetyMsg, now + 200L)
        assertEquals(ArbiterAction.PREEMPT_AND_PLAY, decision3.action)
        assertTrue("대기 큐의 route_2가 제거되어야 함", decision3.droppedMessageIds.contains("route_2"))
        assertEquals("대기 큐 크기는 0이어야 함", 0, arbiter.getQueueSize())
    }

    @Test
    fun testCategoryCooldownSuppressesDuplicates() {
        val arbiter = GuidanceArbiter(
            safetyCooldownMs = 3_000L
        )
        val now = 100_000L

        val msg1 = GuidanceMessage(
            id = "gps_1",
            text = "GPS 신호가 약하여 위치를 확인 중입니다.",
            priority = GuidancePriority.SAFETY,
            category = "safety_gps",
            timestampMs = now
        )
        val d1 = arbiter.enqueue(msg1, now)
        assertEquals(ArbiterAction.PLAY_IMMEDIATELY, d1.action)

        // 1초 후 동일 카테고리 재인입 -> 쿨다운으로 차단
        val msg2 = GuidanceMessage(
            id = "gps_2",
            text = "GPS 신호가 약하여 위치를 확인 중입니다.",
            priority = GuidancePriority.SAFETY,
            category = "safety_gps",
            timestampMs = now + 1_000L
        )
        val d2 = arbiter.enqueue(msg2, now + 1_000L)
        assertEquals(ArbiterAction.SUPPRESSED_COOLDOWN, d2.action)

        // 4초 후(쿨다운 3초 만료 후) 재인입 -> 허용
        val msg3 = GuidanceMessage(
            id = "gps_3",
            text = "GPS 신호가 약하여 위치를 확인 중입니다.",
            priority = GuidancePriority.SAFETY,
            category = "safety_gps",
            timestampMs = now + 4_000L
        )
        arbiter.onSpeechCompleted(now + 4_000L)
        val d3 = arbiter.enqueue(msg3, now + 4_000L)
        assertEquals(ArbiterAction.PLAY_IMMEDIATELY, d3.action)
    }

    @Test
    fun testRepeatLastGuidance() {
        val arbiter = GuidanceArbiter()
        val now = 100_000L

        val msg = GuidanceMessage(
            id = "crossing_1",
            text = "15m 앞 횡단보도 접근 중입니다. 음향신호기 구비됨.",
            priority = GuidancePriority.CROSSING,
            category = "crossing_cw1",
            timestampMs = now
        )
        arbiter.enqueue(msg, now)

        // 다시 듣기 호출 시 쿨다운과 무관하게 마지막 메시지 반환
        val repeated = arbiter.repeatLastGuidance()
        assertNotNull(repeated)
        assertEquals("crossing_1", repeated!!.id)
        assertEquals(msg.text, repeated.text)
    }

    @Test
    fun testStopAllClearsQueueAndState() {
        val arbiter = GuidanceArbiter()
        val now = 100_000L

        arbiter.enqueue(
            GuidanceMessage(
                id = "m1",
                text = "현재 발화 중",
                priority = GuidancePriority.ROUTE,
                category = "cat1",
                timestampMs = now
            ),
            now
        )
        arbiter.enqueue(
            GuidanceMessage(
                id = "m2",
                text = "대기 중 1",
                priority = GuidancePriority.ROUTE,
                category = "cat2",
                timestampMs = now + 100L
            ),
            now + 100L
        )
        assertEquals(1, arbiter.getQueueSize())

        arbiter.stopAll()
        assertEquals(0, arbiter.getQueueSize())
        assertNull(arbiter.onSpeechCompleted())
    }

    @Test
    fun testGreenGuidancePreemptsCurrentlySpeakingRedGuidance() {
        val arbiter = GuidanceArbiter(
            safetyCooldownMs = 3_000L
        )
        val now = 100_000L

        // 1. 적색 신호 발화 시작 (현재 발화 중)
        val redMsg = GuidanceMessage(
            id = "red_1",
            text = "적색 신호입니다. 대기하세요.",
            priority = GuidancePriority.SAFETY,
            category = "signal_decision_red",
            timestampMs = now
        )
        val redDecision = arbiter.enqueue(redMsg, now)
        assertEquals(ArbiterAction.PLAY_IMMEDIATELY, redDecision.action)

        // 2. 400ms 후 녹색 신호로 상태 전이 -> 적색 발화를 즉시 선점(Preempt)하고 발화되어야 함!
        val greenMsg = GuidanceMessage(
            id = "green_1",
            text = "녹색으로 추정됩니다. 앱만으로 안전을 보장할 수 없습니다.",
            priority = GuidancePriority.SAFETY,
            category = "signal_decision_green",
            timestampMs = now + 400L
        )
        val greenDecision = arbiter.enqueue(greenMsg, now + 400L)
        assertEquals(
            "적색 발화 중 녹색 신호 인입 시 즉시 PREEMPT_AND_PLAY되어야 함",
            ArbiterAction.PREEMPT_AND_PLAY,
            greenDecision.action
        )
    }

    @Test
    fun testGreenGuidanceNotSuppressedByRecentRedCooldown() {
        val arbiter = GuidanceArbiter(
            safetyCooldownMs = 3_000L
        )
        val now = 100_000L

        // 1. 적색 발화 완료
        val redMsg = GuidanceMessage(
            id = "red_1",
            text = "적색 신호입니다. 대기하세요.",
            priority = GuidancePriority.SAFETY,
            category = "signal_decision_red",
            timestampMs = now
        )
        arbiter.enqueue(redMsg, now)
        arbiter.onSpeechCompleted(now + 1_000L)

        // 2. 적색 발화 완료 500ms 후 (이전 적색 인입 후 1.5초 후) 녹색 신호 인입
        //    (전체 카테고리가 아닌 개별 상태 카테고리이므로 3초 쿨다운에 걸리지 않고 즉시 발화되어야 함)
        val greenMsg = GuidanceMessage(
            id = "green_1",
            text = "녹색으로 추정됩니다. 앱만으로 안전을 보장할 수 없습니다.",
            priority = GuidancePriority.SAFETY,
            category = "signal_decision_green",
            timestampMs = now + 1_500L
        )
        val greenDecision = arbiter.enqueue(greenMsg, now + 1_500L)
        assertEquals(
            "적색 쿨다운이 녹색 안내를 차단해서는 안 됨",
            ArbiterAction.PLAY_IMMEDIATELY,
            greenDecision.action
        )
    }
}

