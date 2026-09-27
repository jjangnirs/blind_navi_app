package kr.safecross.mobile.signal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.safecross.mobile.decision.model.OfficialSignalState
import kr.safecross.mobile.signal.model.NormalizedSignalStatus
import kr.safecross.mobile.signal.model.ProviderConfig
import kr.safecross.mobile.signal.model.SignalErrorReason
import kr.safecross.mobile.signal.model.SignalFetchResult
import kr.safecross.mobile.signal.model.SignalKillSwitchConfig
import kr.safecross.mobile.signal.model.SignalMetricLog
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 대한민국 경찰청 및 광주광역시 C-ITS 실시간 보행 신호 연동 프로바이더 (PR-F-016, TRD 4.7).
 *
 * 공공데이터포털(data.go.kr) 및 UTIC OpenAPI 실시간 신호정보(SPaT)를 수신하여
 * 보행신호등의 정확한 적색/녹색 상태 및 잔여 시간을 정규화하여 반환합니다.
 */
class CitsRealSignalStatusProvider(
    val apiKey: String = "ca0040c954d4d1f212324e4bcb9b98e92929623bfcc03b53a7f835bf62a49484",
    val baseUrl: String = "https://apis.data.go.kr/B553766/realtimeSignal/getSignal",
    override val providerId: String = "CITS_KOROAD_GWANGJU",
    override val supportedRegions: Set<String> = setOf("KR-29-GWANGJU", "KR-11-SEOUL", "KR-ALL"),
    override val config: ProviderConfig = ProviderConfig(providerId = providerId, timeoutMs = 3000L),
    private val circuitBreaker: SignalCircuitBreaker = SignalCircuitBreaker(),
    var killSwitchConfig: SignalKillSwitchConfig = SignalKillSwitchConfig(),
    private val networkFetcher: (suspend (requestUrl: String) -> String)? = null
) : SignalStatusProvider {

    val metricLogs = mutableListOf<SignalMetricLog>()

    override suspend fun fetchSignalStatus(
        intersectionId: String,
        movementId: String,
        currentElapsedRealtimeNanos: Long
    ): SignalFetchResult {
        val startTime = System.currentTimeMillis()

        // 1. 킬스위치 확인
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

        // 3. 실시간 HTTP 요청 실행
        return try {
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            val requestUrl = "$baseUrl?serviceKey=$encodedKey&itstId=$intersectionId&mvmId=$movementId&type=json"

            val responseBody = if (networkFetcher != null) {
                networkFetcher.invoke(requestUrl)
            } else {
                fetchHttp(requestUrl, config.timeoutMs.toInt())
            }

            // 4. 응답 파싱 및 정규화
            val parseResult = parseSignalResponse(
                jsonStr = responseBody,
                intersectionId = intersectionId,
                movementId = movementId,
                currentElapsedRealtimeNanos = currentElapsedRealtimeNanos
            )

            when (parseResult) {
                is SignalFetchResult.Success -> {
                    circuitBreaker.recordSuccess()
                    recordMetric(startTime, "200_OK", true)
                    parseResult
                }
                is SignalFetchResult.Failure -> {
                    circuitBreaker.recordFailure(currentElapsedRealtimeNanos)
                    recordMetric(startTime, parseResult.reason.code, false)
                    parseResult
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            circuitBreaker.recordFailure(currentElapsedRealtimeNanos)
            recordMetric(startTime, SignalErrorReason.TIMEOUT.code, false)
            SignalFetchResult.Failure(SignalErrorReason.TIMEOUT, "C-ITS Signal request timed out: ${e.message}")
        } catch (e: Exception) {
            circuitBreaker.recordFailure(currentElapsedRealtimeNanos)
            recordMetric(startTime, SignalErrorReason.MALFORMED.code, false)
            SignalFetchResult.Failure(SignalErrorReason.MALFORMED, "C-ITS Signal fetch error: ${e.message}")
        }
    }

    private suspend fun fetchHttp(urlStr: String, timeoutMs: Int): String = withContext(Dispatchers.IO) {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
        }

        try {
            val responseCode = conn.responseCode
            if (responseCode == 401 || responseCode == 403) {
                throw SecurityException("UNAUTHORIZED: HTTP $responseCode")
            }
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP error code: $responseCode")
            }

            BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { reader ->
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                sb.toString()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSignalResponse(
        jsonStr: String,
        intersectionId: String,
        movementId: String,
        currentElapsedRealtimeNanos: Long
    ): SignalFetchResult {
        try {
            val root = JSONObject(jsonStr)

            // 에러 코드 응답 확인
            val resultCode = root.optJSONObject("header")?.optString("resultCode")
                ?: root.optString("resultCode", "")
            if (resultCode.isNotEmpty() && resultCode != "00" && resultCode != "200") {
                val errMsg = root.optJSONObject("header")?.optString("resultMsg") ?: "Error code: $resultCode"
                if (resultCode in listOf("30", "01", "AUTH_ERROR")) {
                    return SignalFetchResult.Failure(SignalErrorReason.UNAUTHORIZED, errMsg)
                }
                return SignalFetchResult.Failure(SignalErrorReason.MAINTENANCE, errMsg)
            }

            // 본문 데이터 추출 (body / item / items)
            val body = root.optJSONObject("body") ?: root
            val item = body.optJSONObject("items")?.optJSONObject("item")
                ?: body.optJSONArray("items")?.optJSONObject(0)
                ?: body.optJSONObject("item")
                ?: body

            val signalStateRaw = item.optString("signalColor", item.optString("state", item.optString("sigStatus", "")))
            val remainingSec = when {
                item.has("remainingSeconds") -> item.optInt("remainingSeconds")
                item.has("remSec") -> item.optInt("remSec")
                item.has("sec") -> item.optInt("sec")
                else -> null
            }

            val officialState = when (signalStateRaw.uppercase()) {
                "GREEN", "G", "WALK", "3", "4" -> OfficialSignalState.GREEN
                "RED", "R", "DONT_WALK", "1", "2" -> OfficialSignalState.RED
                else -> OfficialSignalState.UNKNOWN
            }

            if (officialState == OfficialSignalState.UNKNOWN) {
                return SignalFetchResult.Failure(SignalErrorReason.MALFORMED, "Unknown signal state raw value: '$signalStateRaw'")
            }

            return SignalFetchResult.Success(
                NormalizedSignalStatus(
                    provider = providerId,
                    providerIntersectionId = intersectionId,
                    movementId = movementId,
                    state = officialState,
                    sourceTimestampEpochMs = System.currentTimeMillis(),
                    receivedElapsedRealtimeNanos = currentElapsedRealtimeNanos,
                    optionalRemainingSeconds = remainingSec,
                    qualityFlags = listOf("CITS_LIVE", "VERIFIED_KEY")
                )
            )
        } catch (e: Exception) {
            return SignalFetchResult.Failure(SignalErrorReason.MALFORMED, "JSON parsing error: ${e.message}")
        }
    }

    private fun recordMetric(startTime: Long, statusCode: String, isSuccess: Boolean) {
        val durationMs = System.currentTimeMillis() - startTime
        metricLogs.add(
            SignalMetricLog(
                provider = providerId,
                statusCode = statusCode,
                latencyBucket = SignalMetricLog.computeLatencyBucket(durationMs),
                isSuccess = isSuccess
            )
        )
    }
}
