package kr.safecross.mobile.signal

import kotlinx.coroutines.runBlocking
import kr.safecross.mobile.decision.model.OfficialSignalState
import kr.safecross.mobile.signal.model.SignalErrorReason
import kr.safecross.mobile.signal.model.SignalFetchResult
import kr.safecross.mobile.signal.model.SignalKillSwitchConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C-ITS 실시간 신호 연동 프로바이더 단위 테스트 (PR-F-016, TRD 4.7).
 */
class CitsRealSignalStatusProviderTest {

    private lateinit var circuitBreaker: SignalCircuitBreaker
    private val testApiKey = "ca0040c954d4d1f212324e4bcb9b98e92929623bfcc03b53a7f835bf62a49484"

    @Before
    fun setUp() {
        circuitBreaker = SignalCircuitBreaker(failureThreshold = 3, cooldownDurationNanos = 30_000_000_000L)
    }

    @Test
    fun testDefaultApiKeyMatchesUserKey() {
        val provider = CitsRealSignalStatusProvider()
        assertEquals(testApiKey, provider.apiKey)
        assertEquals("CITS_KOROAD_GWANGJU", provider.providerId)
    }

    @Test
    fun testNormalGreenSignalParsing() = runBlocking {
        val sampleJson = """
            {
                "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
                "body": {
                    "item": {
                        "itstId": "GWANGJU-SANGMU-01",
                        "mvmId": "PED-01",
                        "signalColor": "GREEN",
                        "remainingSeconds": 15
                    }
                }
            }
        """.trimIndent()

        val provider = CitsRealSignalStatusProvider(
            apiKey = testApiKey,
            circuitBreaker = circuitBreaker,
            networkFetcher = { sampleJson }
        )

        val result = provider.fetchSignalStatus("GWANGJU-SANGMU-01", "PED-01", 1000L)
        assertTrue(result is SignalFetchResult.Success)

        val status = (result as SignalFetchResult.Success).status
        assertEquals(OfficialSignalState.GREEN, status.state)
        assertEquals(15, status.optionalRemainingSeconds)
        assertEquals("GWANGJU-SANGMU-01", status.providerIntersectionId)
        assertEquals("PED-01", status.movementId)
        assertTrue(status.qualityFlags.contains("CITS_LIVE"))
        assertTrue(status.qualityFlags.contains("VERIFIED_KEY"))
    }

    @Test
    fun testNormalRedSignalParsing() = runBlocking {
        val sampleJson = """
            {
                "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
                "body": {
                    "items": [
                        {
                            "itstId": "GWANGJU-GEUMNAM-04",
                            "mvmId": "PED-02",
                            "sigStatus": "RED",
                            "remSec": 28
                        }
                    ]
                }
            }
        """.trimIndent()

        val provider = CitsRealSignalStatusProvider(
            apiKey = testApiKey,
            circuitBreaker = circuitBreaker,
            networkFetcher = { sampleJson }
        )

        val result = provider.fetchSignalStatus("GWANGJU-GEUMNAM-04", "PED-02", 2000L)
        assertTrue(result is SignalFetchResult.Success)

        val status = (result as SignalFetchResult.Success).status
        assertEquals(OfficialSignalState.RED, status.state)
        assertEquals(28, status.optionalRemainingSeconds)
    }

    @Test
    fun testUnauthorizedErrorHandling() = runBlocking {
        val authErrorJson = """
            {
                "header": { "resultCode": "30", "resultMsg": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR" }
            }
        """.trimIndent()

        val provider = CitsRealSignalStatusProvider(
            apiKey = "invalid_key",
            circuitBreaker = circuitBreaker,
            networkFetcher = { authErrorJson }
        )

        val result = provider.fetchSignalStatus("GWANGJU-SANGMU-01", "PED-01", 3000L)
        assertTrue(result is SignalFetchResult.Failure)
        assertEquals(SignalErrorReason.UNAUTHORIZED, (result as SignalFetchResult.Failure).reason)
    }

    @Test
    fun testKillSwitchEnforcement() = runBlocking {
        val killConfig = SignalKillSwitchConfig(globalKill = true)
        val provider = CitsRealSignalStatusProvider(
            apiKey = testApiKey,
            killSwitchConfig = killConfig,
            circuitBreaker = circuitBreaker,
            networkFetcher = { "{}" }
        )

        val result = provider.fetchSignalStatus("GWANGJU-SANGMU-01", "PED-01", 4000L)
        assertTrue(result is SignalFetchResult.Failure)
        assertEquals(SignalErrorReason.KILLED, (result as SignalFetchResult.Failure).reason)
    }

    @Test
    fun testCircuitBreakerFastFail() = runBlocking {
        val provider = CitsRealSignalStatusProvider(
            apiKey = testApiKey,
            circuitBreaker = circuitBreaker,
            networkFetcher = { throw java.net.SocketTimeoutException("Simulated Timeout") }
        )

        // 3회 실패 유발
        provider.fetchSignalStatus("GWANGJU-SANGMU-01", "PED-01", 100L)
        provider.fetchSignalStatus("GWANGJU-SANGMU-01", "PED-01", 200L)
        provider.fetchSignalStatus("GWANGJU-SANGMU-01", "PED-01", 300L)

        assertEquals(SignalCircuitBreaker.State.OPEN, circuitBreaker.currentState)

        // 4회째 호출은 서킷 브레이커에 의해 즉시 차단
        val fastFailResult = provider.fetchSignalStatus("GWANGJU-SANGMU-01", "PED-01", 400L)
        assertTrue(fastFailResult is SignalFetchResult.Failure)
        assertEquals(SignalErrorReason.CIRCUIT_OPEN, (fastFailResult as SignalFetchResult.Failure).reason)
    }
}
