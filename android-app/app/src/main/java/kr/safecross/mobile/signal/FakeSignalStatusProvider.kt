package kr.safecross.mobile.signal

import kr.safecross.mobile.decision.model.OfficialSignalState
import kr.safecross.mobile.signal.model.NormalizedSignalStatus
import kr.safecross.mobile.signal.model.ProviderConfig
import kr.safecross.mobile.signal.model.SignalErrorReason
import kr.safecross.mobile.signal.model.SignalFetchResult
import kr.safecross.mobile.signal.model.SignalKillSwitchConfig
import kr.safecross.mobile.signal.model.SignalMetricLog

/**
 * CI 및 로컬 환경에서 100% 결정론적으로 시험 가능한 Fake 신호 상태 제공자.
 */
class FakeSignalStatusProvider(
    override val providerId: String = "KOROAD_SIMULATED",
    override val supportedRegions: Set<String> = setOf("KR-29-GWANGJU", "KR-11-SEOUL"),
    override val config: ProviderConfig = ProviderConfig(providerId = providerId),
    private val circuitBreaker: SignalCircuitBreaker = SignalCircuitBreaker(),
    var killSwitchConfig: SignalKillSwitchConfig = SignalKillSwitchConfig()
) : SignalStatusProvider {

    enum class SimulationMode {
        NORMAL_GREEN,
        NORMAL_RED,
        UNSUPPORTED_REGION,
        UNAUTHORIZED,
        UNMAPPED_MOVEMENT,
        STALE,
        CLOCK_SKEW,
        TIMEOUT,
        MAINTENANCE,
        MALFORMED
    }

    var currentMode: SimulationMode = SimulationMode.NORMAL_RED
    var simulatedRemainingSeconds: Int? = 25
    val metricLogs = mutableListOf<SignalMetricLog>()

    override suspend fun fetchSignalStatus(
        intersectionId: String,
        movementId: String,
        currentElapsedRealtimeNanos: Long
    ): SignalFetchResult {
        val startTime = System.currentTimeMillis()

        // 1. 킬스위치 검사 (어댑터 / 전체)
        val region = if (intersectionId.startsWith("SEOUL")) "KR-11-SEOUL" else "KR-29-GWANGJU"
        if (killSwitchConfig.isKilled(providerId, region)) {
            val log = SignalMetricLog(
                provider = providerId,
                statusCode = SignalErrorReason.KILLED.code,
                latencyBucket = "<100ms",
                isSuccess = false
            )
            metricLogs.add(log)
            return SignalFetchResult.Failure(SignalErrorReason.KILLED, "Kill switch active for $providerId / $region")
        }

        // 2. 서킷 브레이커 검사
        if (!circuitBreaker.canExecute(currentElapsedRealtimeNanos)) {
            val log = SignalMetricLog(
                provider = providerId,
                statusCode = SignalErrorReason.CIRCUIT_OPEN.code,
                latencyBucket = "<100ms",
                isSuccess = false
            )
            metricLogs.add(log)
            return SignalFetchResult.Failure(SignalErrorReason.CIRCUIT_OPEN, "Circuit breaker is OPEN. Fast failing.")
        }

        // 3. 모드별 응답 분기
        val result = when (currentMode) {
            SimulationMode.NORMAL_GREEN -> {
                circuitBreaker.recordSuccess()
                SignalFetchResult.Success(
                    NormalizedSignalStatus(
                        provider = providerId,
                        providerIntersectionId = intersectionId,
                        movementId = movementId,
                        state = OfficialSignalState.GREEN,
                        sourceTimestampEpochMs = System.currentTimeMillis() - 200,
                        receivedElapsedRealtimeNanos = currentElapsedRealtimeNanos,
                        optionalRemainingSeconds = simulatedRemainingSeconds,
                        qualityFlags = listOf("CALIBRATED", "SYNCHRONIZED")
                    )
                )
            }
            SimulationMode.NORMAL_RED -> {
                circuitBreaker.recordSuccess()
                SignalFetchResult.Success(
                    NormalizedSignalStatus(
                        provider = providerId,
                        providerIntersectionId = intersectionId,
                        movementId = movementId,
                        state = OfficialSignalState.RED,
                        sourceTimestampEpochMs = System.currentTimeMillis() - 200,
                        receivedElapsedRealtimeNanos = currentElapsedRealtimeNanos,
                        optionalRemainingSeconds = simulatedRemainingSeconds,
                        qualityFlags = listOf("CALIBRATED", "SYNCHRONIZED")
                    )
                )
            }
            SimulationMode.UNSUPPORTED_REGION -> {
                circuitBreaker.recordFailure(currentElapsedRealtimeNanos)
                SignalFetchResult.Failure(SignalErrorReason.UNSUPPORTED_REGION, "Intersection $intersectionId is in unsupported region")
            }
            SimulationMode.UNAUTHORIZED -> {
                circuitBreaker.recordFailure(currentElapsedRealtimeNanos)
                SignalFetchResult.Failure(SignalErrorReason.UNAUTHORIZED, "Provider authorization expired or rejected")
            }
            SimulationMode.UNMAPPED_MOVEMENT -> {
                circuitBreaker.recordFailure(currentElapsedRealtimeNanos)
                SignalFetchResult.Failure(SignalErrorReason.UNMAPPED_MOVEMENT, "Movement ID $movementId has no verified link")
            }
            SimulationMode.STALE -> {
                circuitBreaker.recordFailure(currentElapsedRealtimeNanos)
                SignalFetchResult.Failure(SignalErrorReason.STALE, "Observation source timestamp exceeded maxAge (5000ms)")
            }
            SimulationMode.CLOCK_SKEW -> {
                circuitBreaker.recordFailure(currentElapsedRealtimeNanos)
                SignalFetchResult.Failure(SignalErrorReason.CLOCK_SKEW, "Source clock skewed ahead of monotonic clock")
            }
            SimulationMode.TIMEOUT -> {
                circuitBreaker.recordFailure(currentElapsedRealtimeNanos)
                SignalFetchResult.Failure(SignalErrorReason.TIMEOUT, "Signal provider request timed out (3000ms)")
            }
            SimulationMode.MAINTENANCE -> {
                circuitBreaker.recordFailure(currentElapsedRealtimeNanos)
                SignalFetchResult.Failure(SignalErrorReason.MAINTENANCE, "Traffic controller under maintenance")
            }
            SimulationMode.MALFORMED -> {
                circuitBreaker.recordFailure(currentElapsedRealtimeNanos)
                SignalFetchResult.Failure(SignalErrorReason.MALFORMED, "Malformed SPaT payload")
            }
        }

        // 4. 비식별 관측 메트릭 기록
        val durationMs = System.currentTimeMillis() - startTime
        val statusCode = when (result) {
            is SignalFetchResult.Success -> "200_OK"
            is SignalFetchResult.Failure -> result.reason.code
        }
        metricLogs.add(
            SignalMetricLog(
                provider = providerId,
                statusCode = statusCode,
                latencyBucket = SignalMetricLog.computeLatencyBucket(durationMs),
                isSuccess = result.isSuccess
            )
        )

        return result
    }
}
