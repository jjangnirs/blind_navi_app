package kr.safecross.mobile

import kr.safecross.mobile.location.LocationOutlierFilter
import kr.safecross.mobile.location.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 2026-09-26 실측 로그 기반 GPS 이상치 및 기지국 순간이동 기각 필터 단위 테스트.
 */
class LocationOutlierFilterTest {

    private lateinit var filter: LocationOutlierFilter

    @Before
    fun setUp() {
        filter = LocationOutlierFilter()
    }

    @Test
    fun `normal walking samples are accepted smoothly`() {
        val baseNanos = 10_000_000_000L

        // 1초 간격 1.2m 정상 보행
        val sample1 = LocationSample(
            lat = 35.159500,
            lon = 126.852600,
            accuracyMeters = 3.0f,
            speedMps = 1.2f,
            elapsedRealtimeNanos = baseNanos
        )
        val result1 = filter.filter(sample1)
        assertNotNull("첫 번째 유효 샘플은 수용되어야 함", result1)

        val sample2 = LocationSample(
            lat = 35.159510, // 약 1.1m 북쪽 이동
            lon = 126.852600,
            accuracyMeters = 3.2f,
            speedMps = 1.1f,
            elapsedRealtimeNanos = baseNanos + 1_000_000_000L // 1.0초 후
        )
        val result2 = filter.filter(sample2)
        assertNotNull("정상 보행 속도의 샘플은 수용되어야 함", result2)
        assertEquals(35.159510, result2!!.lat, 0.000001)
    }

    @Test
    fun `sept 26 log 435m teleport jump in 102ms is rejected`() {
        val baseNanos = 20_000_000_000L

        // 실측: [13:59:18.218] lat=35.12920, lon=126.85230, acc=4.0m
        val goodSample = LocationSample(
            lat = 35.12920,
            lon = 126.85230,
            accuracyMeters = 4.0f,
            elapsedRealtimeNanos = baseNanos
        )
        assertNotNull(filter.filter(goodSample))

        // 실측: 102ms 후 [13:59:18.320] 435m 떨어진 기지국 위치로 순간이동 (acc=100m, speed=4200 m/s)
        val teleportSample = LocationSample(
            lat = 35.13310, // 약 435m 북쪽 점프
            lon = 126.85230,
            accuracyMeters = 100.0f,
            elapsedRealtimeNanos = baseNanos + 102_000_000L // 102ms 후
        )
        val rejected = filter.filter(teleportSample)
        assertNull("102ms 만에 435m 순간이동한 기지국 샘플은 즉각 기각되어야 함", rejected)
    }

    @Test
    fun `sept 26 log 212m jump in 270ms is rejected`() {
        val baseNanos = 30_000_000_000L

        // 실측: [15:21:04.969] lat=35.15000, lon=126.85000, acc=3.5m
        val goodSample = LocationSample(
            lat = 35.15000,
            lon = 126.85000,
            accuracyMeters = 3.5f,
            elapsedRealtimeNanos = baseNanos
        )
        assertNotNull(filter.filter(goodSample))

        // 실측: 270ms 후 212m 순간 점프 (acc=38.7m)
        val teleportSample = LocationSample(
            lat = 35.15190, // 약 212m 이동
            lon = 126.85000,
            accuracyMeters = 38.7f,
            elapsedRealtimeNanos = baseNanos + 270_000_000L // 270ms 후
        )
        val rejected = filter.filter(teleportSample)
        assertNull("270ms 만에 212m 순간이동한 이상치는 기각되어야 함", rejected)
    }

    @Test
    fun `isolated cell tower low accuracy fix is rejected when GPS fix is healthy`() {
        val baseNanos = 40_000_000_000L

        // 고정밀 GPS 수신 상태
        val healthyGps = LocationSample(
            lat = 35.15950,
            lon = 126.85260,
            accuracyMeters = 3.0f,
            elapsedRealtimeNanos = baseNanos
        )
        assertNotNull(filter.filter(healthyGps))

        // 단발성 기지국 위치 난입 (거리 10m 이내라도 정확도가 75m로 급락)
        val cellTowerSample = LocationSample(
            lat = 35.15955,
            lon = 126.85260,
            accuracyMeters = 75.0f,
            elapsedRealtimeNanos = baseNanos + 1_000_000_000L
        )
        val rejected = filter.filter(cellTowerSample)
        assertNull("양호한 GPS 확보 후 단발성 75m 기지국 저정밀도 픽스는 기각되어야 함", rejected)
    }

    @Test
    fun `consecutive high speed movements accept new location to avoid deadlock`() {
        val baseNanos = 50_000_000_000L

        val initSample = LocationSample(
            lat = 35.15000,
            lon = 126.85000,
            accuracyMeters = 4.0f,
            elapsedRealtimeNanos = baseNanos
        )
        assertNotNull(filter.filter(initSample))

        // 차량 또는 버스 탑승으로 60m씩 계속 이동 (4회 연속)
        var timeNanos = baseNanos + 1_000_000_000L
        var lat = 35.15060

        // 1회차 이상치: 기각
        assertNull(filter.filter(LocationSample(lat, 126.85000, 18.0f, elapsedRealtimeNanos = timeNanos)))

        // 2회차 이상치: 기각
        timeNanos += 1_000_000_000L
        lat += 0.00060
        assertNull(filter.filter(LocationSample(lat, 126.85000, 18.0f, elapsedRealtimeNanos = timeNanos)))

        // 3회차 이상치: 기각
        timeNanos += 1_000_000_000L
        lat += 0.00060
        assertNull(filter.filter(LocationSample(lat, 126.85000, 18.0f, elapsedRealtimeNanos = timeNanos)))

        // 4회차 이상치: 차량/대중교통 탑승 판정으로 신규 위치 강제 수용 (데드락 방지)
        timeNanos += 1_000_000_000L
        lat += 0.00060
        val accepted = filter.filter(LocationSample(lat, 126.85000, 18.0f, elapsedRealtimeNanos = timeNanos))
        assertNotNull("4회 연속 이동 지속 시 데드락 방지를 위해 신규 위치를 수용해야 함", accepted)
    }
}
