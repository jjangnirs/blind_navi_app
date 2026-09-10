package kr.safecross.mobile.network

import java.util.regex.Pattern

/**
 * 모바일 네트워크 요청/응답 개인정보 비식별화 로깅 인터셉터 (SR-NF-022, SR-NF-041).
 *
 * 위도/경도 부동소수점, 목적지 명칭, 카메라 프레임 바이너리가 로그에 남지 않도록
 * 원문을 "[REDACTED]"로 강제 치환합니다.
 */
class RedactionLoggingInterceptor {

    companion object {
        private val LAT_LON_PARAM_PATTERN = Pattern.compile(
            "(?i)(latitude|longitude|lat|lon|startX|startY|endX|endY|origin|destination)=([^&\\s,]+)"
        )

        private val JSON_COORD_PATTERN = Pattern.compile(
            "\"(latitude|longitude|lat|lon|startX|startY|endX|endY|destination|address)\"\\s*:\\s*(\"[^\"]*\"|[-0-9.]+)"
        )

        private val JSON_FRAME_PATTERN = Pattern.compile(
            "\"(frame|image|pixels|binaryPayload)\"\\s*:\\s*(\"[^\"]*\"|\\[[^\\]]*\\])"
        )

        /**
         * URL 쿼리 파라미터 및 본문 문자열에서 위치, 목적지, 프레임 정보를 마스킹합니다.
         */
        fun redactLog(message: String): String {
            var redacted = message

            // 1. URL 쿼리 파라미터 마스킹
            val urlMatcher = LAT_LON_PARAM_PATTERN.matcher(redacted)
            redacted = urlMatcher.replaceAll("$1=[REDACTED]")

            // 2. JSON 좌표 및 목적지 마스킹
            val jsonCoordMatcher = JSON_COORD_PATTERN.matcher(redacted)
            redacted = jsonCoordMatcher.replaceAll("\"$1\": \"[REDACTED]\"")

            // 3. JSON 프레임 / 이미지 마스킹
            val jsonFrameMatcher = JSON_FRAME_PATTERN.matcher(redacted)
            redacted = jsonFrameMatcher.replaceAll("\"$1\": \"[REDACTED]\"")

            return redacted
        }
    }

    /**
     * 네트워크 요청 메시지를 비식별화하여 반환합니다.
     */
    fun sanitizeRequestLog(url: String, body: String? = null): String {
        val safeUrl = redactLog(url)
        val safeBody = body?.let { redactLog(it) } ?: ""
        return "REQUEST -> URL: $safeUrl | BODY: $safeBody"
    }

    /**
     * 네트워크 응답 메시지를 비식별화하여 반환합니다.
     */
    fun sanitizeResponseLog(statusCode: Int, body: String? = null): String {
        val safeBody = body?.let { redactLog(it) } ?: ""
        return "RESPONSE <- STATUS: $statusCode | BODY: $safeBody"
    }
}
