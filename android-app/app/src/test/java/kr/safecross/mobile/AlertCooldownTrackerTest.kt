package kr.safecross.mobile

import kr.safecross.mobile.navigation.crossing.AlertCooldownTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertCooldownTrackerTest {

    @Test
    fun testCooldownSuppressesDuplicateAlerts() {
        val tracker = AlertCooldownTracker(cooldownMs = 60_000L)
        val startTime = 100_000L

        // 1. 첫 번째 알림: 허용
        assertTrue(tracker.shouldAlert("crossing-1", 45.0, startTime))

        // 2. 10초 후 동일 시설, 동일 방향: 차단
        assertFalse(tracker.shouldAlert("crossing-1", 45.0, startTime + 10_000L))

        // 3. 30초 후 동일 시설, 유사 각도(50도 -> 같은 45도 버킷): 차단
        assertFalse(tracker.shouldAlert("crossing-1", 50.0, startTime + 30_000L))

        // 4. 20초 후 동일 시설이지만 직교 방향(135도): 허용 (다른 방향 접근)
        assertTrue(tracker.shouldAlert("crossing-1", 135.0, startTime + 20_000L))

        // 5. 61초 후 동일 시설, 최초 방향(45도): 허용 (쿨다운 만료)
        assertTrue(tracker.shouldAlert("crossing-1", 45.0, startTime + 61_000L))
    }

    @Test
    fun testDifferentCrossingsAreTrackedIndependently() {
        val tracker = AlertCooldownTracker(cooldownMs = 60_000L)
        val startTime = 100_000L

        assertTrue(tracker.shouldAlert("crossing-1", 45.0, startTime))
        assertTrue("다른 횡단보도는 즉시 허용되어야 함", tracker.shouldAlert("crossing-2", 45.0, startTime))
    }
}
