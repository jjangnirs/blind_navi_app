package kr.safecross.mobile

import kr.safecross.mobile.location.LocationQualityGate
import kr.safecross.mobile.location.LocationQualityStatus
import kr.safecross.mobile.location.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityGateTest {

    private val nowNanos = 20_000_000_000L // 20초

    @Test
    fun testUsableLocation() {
        val sample = LocationSample(
            lat = 35.1595,
            lon = 126.8526,
            accuracyMeters = 5.0f,
            elapsedRealtimeNanos = nowNanos - 2_000_000_000L, // 2초 전
            isMock = false
        )

        val status = LocationQualityGate.evaluateQuality(sample, currentElapsedNanos = nowNanos)
        assertEquals(LocationQualityStatus.USABLE, status)
        assertTrue(LocationQualityGate.isLocationUsable(sample, currentElapsedNanos = nowNanos))
    }

    @Test
    fun testAccuracyTooLowRejected() {
        val sample = LocationSample(
            lat = 35.1595,
            lon = 126.8526,
            accuracyMeters = 25.0f, // 20m 초과 불량
            elapsedRealtimeNanos = nowNanos - 1_000_000_000L,
            isMock = false
        )

        val status = LocationQualityGate.evaluateQuality(sample, currentElapsedNanos = nowNanos)
        assertEquals(LocationQualityStatus.ACCURACY_TOO_LOW, status)
        assertFalse(LocationQualityGate.isLocationUsable(sample, currentElapsedNanos = nowNanos))
    }

    @Test
    fun testStaleSampleRejected() {
        val sample = LocationSample(
            lat = 35.1595,
            lon = 126.8526,
            accuracyMeters = 5.0f,
            elapsedRealtimeNanos = nowNanos - 12_000_000_000L, // 12초 전 (10초 초과 만료)
            isMock = false
        )

        val status = LocationQualityGate.evaluateQuality(sample, currentElapsedNanos = nowNanos)
        assertEquals(LocationQualityStatus.STALE_SAMPLE, status)
        assertFalse(LocationQualityGate.isLocationUsable(sample, currentElapsedNanos = nowNanos))
    }

    @Test
    fun testMockLocationRejectedInProduction() {
        val sample = LocationSample(
            lat = 35.1595,
            lon = 126.8526,
            accuracyMeters = 5.0f,
            elapsedRealtimeNanos = nowNanos - 1_000_000_000L,
            isMock = true // 모의 위치
        )

        // 운영 모드 (allowMock = false)
        val status = LocationQualityGate.evaluateQuality(sample, allowMock = false, currentElapsedNanos = nowNanos)
        assertEquals(LocationQualityStatus.MOCK_NOT_ALLOWED, status)
        assertFalse(LocationQualityGate.isLocationUsable(sample, allowMock = false, currentElapsedNanos = nowNanos))

        // 테스트 모드 (allowMock = true)
        assertTrue(LocationQualityGate.isLocationUsable(sample, allowMock = true, currentElapsedNanos = nowNanos))
    }

    @Test
    fun testOutOfBoundsRejected() {
        val sample = LocationSample(
            lat = 45.0, // 대한민국 위도 32~39도 밖
            lon = 126.8526,
            accuracyMeters = 5.0f,
            elapsedRealtimeNanos = nowNanos - 1_000_000_000L,
            isMock = false
        )

        val status = LocationQualityGate.evaluateQuality(sample, currentElapsedNanos = nowNanos)
        assertEquals(LocationQualityStatus.OUT_OF_BOUNDS, status)
        assertFalse(LocationQualityGate.isLocationUsable(sample, currentElapsedNanos = nowNanos))
    }

    @Test
    fun testToStringDoesNotLeakRawCoordinates() {
        // SR-NF-022, SR-NF-041: 좌표 원문 비식별화 검증
        val sample = LocationSample(
            lat = 35.15951234,
            lon = 126.85265678,
            accuracyMeters = 5.0f
        )
        val str = sample.toString()
        assertFalse("위도 원문이 로그 문자열에 노출되면 안 됨", str.contains("35.15951234"))
        assertFalse("경도 원문이 로그 문자열에 노출되면 안 됨", str.contains("126.85265678"))
        assertTrue("마스킹 태그가 포함되어야 함", str.contains("[REDACTED]"))
    }
}
