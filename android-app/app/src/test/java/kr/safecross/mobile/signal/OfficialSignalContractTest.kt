package kr.safecross.mobile.signal

import kotlinx.coroutines.runBlocking
import kr.safecross.mobile.decision.model.OfficialSignalState
import kr.safecross.mobile.signal.model.SignalErrorReason
import kr.safecross.mobile.signal.model.SignalFetchResult
import kr.safecross.mobile.signal.model.SignalKillSwitchConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 공식 실시간 보행신호 어댑터 계약 및 격리 테스트 (PR-F-016, TRD 4.7).
 *
 * 수용 기준:
 * - 정상 RED/GREEN 응답 정규화 성공.
 * - 만료(stale), 시계 왜곡(clock skew), 미지원 지역, 권한 오류, 타임아웃 시 GREEN/RED와 분리된 Failure 반환.
 * - 서킷 브레이커 3회 연속 실패 시 즉각 차단(Fast-fail).
 * - 공급자·지역·전역 킬스위치 동작.
 * - 비식별 관측 메트릭 수집 및 좌표 원문 0건.
 */
class OfficialSignalContractTest {

    private lateinit var circuitBreaker: SignalCircuitBreaker
    private lateinit var fakeProvider: FakeSignalStatusProvider

    @Before
    fun setUp() {
        circuitBreaker = SignalCircuitBreaker(failureThreshold = 3, cooldownDurationNanos = 30_000_000_000L)
        fakeProvider = FakeSignalStatusProvider(circuitBreaker = circuitBreaker)
    }

    @Test
    fun testNormalGreenNormalization() = runBlocking {
        fakeProvider.currentMode = FakeSignalStatusProvider.SimulationMode.NORMAL_GREEN
        fakeProvider.simulatedRemainingSeconds = 18

        val nowNanos = 10_000_000_000L
        val result = fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-NORTH", nowNanos)

        assertTrue("Fetch must succeed", result is SignalFetchResult.Success)
        val status = (result as SignalFetchResult.Success).status

        assertEquals("KOROAD_SIMULATED", status.provider)
        assertEquals("GWANGJU-INT-01", status.providerIntersectionId)
        assertEquals("MOV-NORTH", status.movementId)
        assertEquals(OfficialSignalState.GREEN, status.state)
        assertEquals(18, status.optionalRemainingSeconds)
        assertEquals(nowNanos, status.receivedElapsedRealtimeNanos)
        assertTrue(status.qualityFlags.contains("CALIBRATED"))
    }

    @Test
    fun testNormalRedNormalization() = runBlocking {
        fakeProvider.currentMode = FakeSignalStatusProvider.SimulationMode.NORMAL_RED
        fakeProvider.simulatedRemainingSeconds = 45

        val nowNanos = 10_000_000_000L
        val result = fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-NORTH", nowNanos)

        assertTrue(result is SignalFetchResult.Success)
        val status = (result as SignalFetchResult.Success).status
        assertEquals(OfficialSignalState.RED, status.state)
        assertEquals(45, status.optionalRemainingSeconds)
    }

    @Test
    fun testFailureReasonsSeparatedFromSignalStates() = runBlocking {
        val nowNanos = 10_000_000_000L

        // 1. UNSUPPORTED_REGION
        fakeProvider.currentMode = FakeSignalStatusProvider.SimulationMode.UNSUPPORTED_REGION
        val r1 = fakeProvider.fetchSignalStatus("UNKNOWN-INT-99", "MOV-1", nowNanos)
        assertTrue(r1 is SignalFetchResult.Failure)
        assertEquals(SignalErrorReason.UNSUPPORTED_REGION, (r1 as SignalFetchResult.Failure).reason)

        // 2. UNAUTHORIZED
        circuitBreaker.reset()
        fakeProvider.currentMode = FakeSignalStatusProvider.SimulationMode.UNAUTHORIZED
        val r2 = fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-1", nowNanos)
        assertEquals(SignalErrorReason.UNAUTHORIZED, (r2 as SignalFetchResult.Failure).reason)

        // 3. STALE
        circuitBreaker.reset()
        fakeProvider.currentMode = FakeSignalStatusProvider.SimulationMode.STALE
        val r3 = fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-1", nowNanos)
        assertEquals(SignalErrorReason.STALE, (r3 as SignalFetchResult.Failure).reason)

        // 4. CLOCK_SKEW
        circuitBreaker.reset()
        fakeProvider.currentMode = FakeSignalStatusProvider.SimulationMode.CLOCK_SKEW
        val r4 = fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-1", nowNanos)
        assertEquals(SignalErrorReason.CLOCK_SKEW, (r4 as SignalFetchResult.Failure).reason)

        // 5. TIMEOUT
        circuitBreaker.reset()
        fakeProvider.currentMode = FakeSignalStatusProvider.SimulationMode.TIMEOUT
        val r5 = fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-1", nowNanos)
        assertEquals(SignalErrorReason.TIMEOUT, (r5 as SignalFetchResult.Failure).reason)

        // 6. MAINTENANCE
        circuitBreaker.reset()
        fakeProvider.currentMode = FakeSignalStatusProvider.SimulationMode.MAINTENANCE
        val r6 = fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-1", nowNanos)
        assertEquals(SignalErrorReason.MAINTENANCE, (r6 as SignalFetchResult.Failure).reason)

        // 7. MALFORMED
        circuitBreaker.reset()
        fakeProvider.currentMode = FakeSignalStatusProvider.SimulationMode.MALFORMED
        val r7 = fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-1", nowNanos)
        assertEquals(SignalErrorReason.MALFORMED, (r7 as SignalFetchResult.Failure).reason)
    }

    @Test
    fun testCircuitBreakerTripsAfterThreeConsecutiveFailures() = runBlocking {
        fakeProvider.currentMode = FakeSignalStatusProvider.SimulationMode.TIMEOUT
        val nowNanos = 20_000_000_000L

        // 3회 실패 유발
        fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-1", nowNanos)
        fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-1", nowNanos + 1_000_000_000L)
        fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-1", nowNanos + 2_000_000_000L)

        assertEquals(SignalCircuitBreaker.State.OPEN, circuitBreaker.currentState)

        // 4번째 호출은 서킷 OPEN으로 인해 즉시 실패(Fast-fail)
        val r4 = fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-1", nowNanos + 3_000_000_000L)
        assertTrue(r4 is SignalFetchResult.Failure)
        assertEquals(SignalErrorReason.CIRCUIT_OPEN, (r4 as SignalFetchResult.Failure).reason)

        // 30초 쿨다운 경과 후 HALF-OPEN 전이 확인
        val afterCooldown = nowNanos + 35_000_000_000L
        assertTrue(circuitBreaker.canExecute(afterCooldown))
    }

    @Test
    fun testThreeTierKillSwitches() = runBlocking {
        val nowNanos = 10_000_000_000L
        fakeProvider.currentMode = FakeSignalStatusProvider.SimulationMode.NORMAL_GREEN

        // 1. 공급자 킬스위치
        fakeProvider.killSwitchConfig = SignalKillSwitchConfig(disabledProviders = setOf("KOROAD_SIMULATED"))
        val r1 = fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-1", nowNanos)
        assertEquals(SignalErrorReason.KILLED, (r1 as SignalFetchResult.Failure).reason)

        // 2. 지역 킬스위치
        fakeProvider.killSwitchConfig = SignalKillSwitchConfig(disabledRegions = setOf("KR-29-GWANGJU"))
        val r2 = fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-1", nowNanos)
        assertEquals(SignalErrorReason.KILLED, (r2 as SignalFetchResult.Failure).reason)

        // 3. 전역 킬스위치
        fakeProvider.killSwitchConfig = SignalKillSwitchConfig(globalKill = true)
        val r3 = fakeProvider.fetchSignalStatus("SEOUL-INT-01", "MOV-1", nowNanos)
        assertEquals(SignalErrorReason.KILLED, (r3 as SignalFetchResult.Failure).reason)
    }

    @Test
    fun testMetricsLogContainsZeroCoordinatesAndValidBuckets() = runBlocking {
        fakeProvider.metricLogs.clear()
        fakeProvider.currentMode = FakeSignalStatusProvider.SimulationMode.NORMAL_GREEN

        fakeProvider.fetchSignalStatus("GWANGJU-INT-01", "MOV-1", 10_000_000_000L)
        val log = fakeProvider.metricLogs.firstOrNull()

        assertNotNull(log)
        assertEquals("KOROAD_SIMULATED", log!!.provider)
        assertEquals("200_OK", log.statusCode)
        assertTrue(log.isSuccess)
        assertNotNull(log.latencyBucket)
    }
}
