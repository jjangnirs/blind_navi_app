package kr.safecross.mobile.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 모바일 네트워크 인터셉터 개인정보 비식별화 테스트 (SR-NF-022, SR-NF-041).
 *
 * 수용 기준:
 * - 네트워크 로그에 위도/경도 부동소수점 원문이 노출되지 않음.
 * - 목적지 문자열이 로그에 노출되지 않음.
 * - 카메라 프레임/이미지 페이로드가 로그에 노출되지 않음.
 */
class NetworkRedactionTest {

    private val interceptor = RedactionLoggingInterceptor()

    @Test
    fun testUrlQueryParamsAreRedacted() {
        val rawUrl = "https://api.safecross.kr/v1/crossings/nearby?lat=35.1501234&lon=126.8504567&radius=30"
        val log = interceptor.sanitizeRequestLog(rawUrl)

        assertFalse("Raw latitude must NOT appear in network logs", log.contains("35.1501234"))
        assertFalse("Raw longitude must NOT appear in network logs", log.contains("126.8504567"))
        assertTrue("Log must contain masked parameter", log.contains("lat=[REDACTED]"))
        assertTrue("Log must contain masked parameter", log.contains("lon=[REDACTED]"))
    }

    @Test
    fun testDestinationAndCoordinatesInJsonBodyAreRedacted() {
        val rawBody = """
            {
                "startX": 126.8501234,
                "startY": 35.1501234,
                "endX": 126.8601234,
                "endY": 35.1601234,
                "destination": "광주광역시청 본관",
                "excludeStairs": true
            }
        """.trimIndent()

        val log = interceptor.sanitizeRequestLog("https://api.safecross.kr/v1/routes/pedestrian", rawBody)

        assertFalse("Raw startX coordinate must NOT appear", log.contains("126.8501234"))
        assertFalse("Raw startY coordinate must NOT appear", log.contains("35.1501234"))
        assertFalse("Raw destination name must NOT appear", log.contains("광주광역시청 본관"))
        assertTrue("Destination must be redacted", log.contains("\"destination\": \"[REDACTED]\""))
    }

    @Test
    fun testCameraFramePayloadInLogIsRedacted() {
        val rawBody = """
            {
                "sequenceId": "seq-404",
                "frame": "BASE64_IMAGE_PIXEL_DATA_BYTES_999999999"
            }
        """.trimIndent()

        val log = interceptor.sanitizeRequestLog("https://api.safecross.kr/v1/debug/report", rawBody)

        assertFalse("Raw frame binary data must NOT appear in logs", log.contains("BASE64_IMAGE_PIXEL_DATA"))
        assertTrue("Frame payload must be redacted", log.contains("\"frame\": \"[REDACTED]\""))
    }
}
