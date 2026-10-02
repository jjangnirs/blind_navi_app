package kr.safecross.mobile

import kr.safecross.mobile.navigation.crossing.CrossingAutoTriggerDecision
import kr.safecross.mobile.navigation.crossing.CrossingAutoTriggerPolicy
import kr.safecross.mobile.navigation.crossing.RouteCrosswalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 횡단보도 자동 전환 정책: 경로상 15m 1회 전환, 30m 이내 2초 정지 시 전환, GPS 불량 시 수동 권유.
 */
class CrossingAutoTriggerPolicyTest {

    private val crosswalkA = RouteCrosswalk(maneuverIndex = 2, alongRouteMeters = 100.0, instruction = "횡단보도 A")
    private val crosswalkB = RouteCrosswalk(maneuverIndex = 5, alongRouteMeters = 250.0, instruction = "횡단보도 B")

    private fun policy() = CrossingAutoTriggerPolicy(listOf(crosswalkA, crosswalkB))

    @Test
    fun triggersOnceWithin15MetersAlongRoute() {
        val p = policy()
        assertNull(p.evaluate(70.0, 1.2f, 8f, false, 0L))   // 30m 앞
        assertNull(p.evaluate(84.0, 1.2f, 8f, false, 1_000L)) // 16m 앞

        val d = p.evaluate(86.0, 1.2f, 8f, false, 2_000L)     // 14m 앞
        assertTrue(d is CrossingAutoTriggerDecision.Trigger)
        d as CrossingAutoTriggerDecision.Trigger
        assertEquals(2, d.crosswalk.maneuverIndex)
        assertEquals(14.0, d.remainingMeters, 0.01)
        assertEquals("DISTANCE_15M", d.reason)

        // 8m, 2m, 통과 직후: 같은 횡단보도는 다시 전환하지 않음
        assertNull(p.evaluate(92.0, 1.2f, 8f, false, 3_000L))
        assertNull(p.evaluate(98.0, 1.2f, 8f, false, 4_000L))
        assertNull(p.evaluate(103.0, 1.2f, 8f, false, 5_000L))
    }

    @Test
    fun nextCrosswalkTriggersIndependently() {
        val p = policy()
        assertTrue(p.evaluate(90.0, 1.2f, 8f, false, 0L) is CrossingAutoTriggerDecision.Trigger)
        assertNull(p.evaluate(200.0, 1.2f, 8f, false, 60_000L))
        val d = p.evaluate(238.0, 1.2f, 8f, false, 70_000L)
        assertTrue(d is CrossingAutoTriggerDecision.Trigger)
        assertEquals(5, (d as CrossingAutoTriggerDecision.Trigger).crosswalk.maneuverIndex)
    }

    @Test
    fun stoppingWithin30MetersForTwoSecondsTriggers() {
        val p = policy()
        // 25m 앞에서 멈춤 (GPS가 실제 위치보다 늦게 따라오는 상황)
        assertNull(p.evaluate(75.0, 0.1f, 8f, false, 0L))
        assertNull(p.evaluate(75.0, 0.1f, 8f, false, 1_000L))
        val d = p.evaluate(75.0, 0.1f, 8f, false, 2_100L)
        assertTrue(d is CrossingAutoTriggerDecision.Trigger)
        assertEquals("STOPPED_NEAR_CROSSWALK", (d as CrossingAutoTriggerDecision.Trigger).reason)
    }

    @Test
    fun brieflySlowingDownDoesNotTrigger() {
        val p = policy()
        assertNull(p.evaluate(75.0, 0.1f, 8f, false, 0L))
        assertNull(p.evaluate(76.0, 1.1f, 8f, false, 1_000L)) // 다시 걷기 시작 -> 정지 타이머 초기화
        assertNull(p.evaluate(77.0, 0.1f, 8f, false, 2_500L))
    }

    @Test
    fun stoppingFarFromCrosswalkDoesNotTrigger() {
        val p = policy()
        assertNull(p.evaluate(50.0, 0.0f, 8f, false, 0L))
        assertNull(p.evaluate(50.0, 0.0f, 8f, false, 5_000L)) // 50m 앞 정지
    }

    @Test
    fun poorGpsSuggestsManualOnceThenTriggersWhenGpsRecovers() {
        val p = policy()
        val first = p.evaluate(88.0, 1.2f, 32f, false, 0L)
        assertTrue(first is CrossingAutoTriggerDecision.SuggestManual)
        assertNull(p.evaluate(90.0, 1.2f, 32f, false, 1_000L)) // 권유는 1회만
        assertTrue(p.evaluate(92.0, 1.2f, 10f, false, 2_000L) is CrossingAutoTriggerDecision.Trigger)
    }

    @Test
    fun offRouteNeverTriggers() {
        val p = policy()
        assertNull(p.evaluate(95.0, 1.2f, 8f, true, 0L))
    }
}
