package kr.safecross.mobile

import kr.safecross.mobile.domain.model.WalkingMode
import kr.safecross.mobile.location.LocationSample
import kr.safecross.mobile.navigation.crossing.CrossingApproachEngine
import kr.safecross.mobile.navigation.crossing.CrossingFacility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingApproachEngineTest {

    private val verifiedFacility = CrossingFacility(
        id = "cw-verified-1",
        lat = 35.159800,
        lon = 126.852900,
        approachBearingDeg = 45.0,
        isFieldVerified = true,
        acousticSignal = true,
        tactilePaving = true,
        curbCut = true,
        roadName = "시청로"
    )

    private val unverifiedFacility = CrossingFacility(
        id = "cw-unverified-2",
        lat = 35.159800,
        lon = 126.852900,
        approachBearingDeg = 45.0,
        isFieldVerified = false,
        acousticSignal = null,
        tactilePaving = null,
        curbCut = null,
        roadName = "시청로"
    )

    @Test
    fun testNormalApproachLifecycleWithVerifiedFacility() {
        val engine = CrossingApproachEngine(listOf(verifiedFacility))
        val nowNanos = 20_000_000_000L

        // 1. 38m 전방 접근 (사전 알림)
        val sampleAdvance = LocationSample(
            lat = 35.159570,
            lon = 126.852670,
            accuracyMeters = 5.0f,
            bearingDegrees = 45.0f,
            elapsedRealtimeNanos = nowNanos - 1_000_000_000L,
            isMock = true
        )
        val alert1 = engine.evaluateApproach(
            sample = sampleAdvance,
            currentElapsedNanos = nowNanos,
            currentTimeMs = 100_000L,
            allowMock = true
        )
        assertNotNull(alert1)
        assertEquals(WalkingMode.APPROACHING_CROSSING, alert1!!.targetMode)
        assertTrue("사전 알림 문구 포함", alert1.message.contains("접근 중"))

        // 2. 14m 전방 정지 준비 (음향신호기 안내 포함)
        val sampleStop = LocationSample(
            lat = 35.159720,
            lon = 126.852820,
            accuracyMeters = 4.0f,
            bearingDegrees = 45.0f,
            elapsedRealtimeNanos = nowNanos - 1_000_000_000L,
            isMock = true
        )
        val alert2 = engine.evaluateApproach(
            sample = sampleStop,
            currentElapsedNanos = nowNanos,
            currentTimeMs = 170_000L, // 쿨다운 60초 경과 후
            allowMock = true
        )
        assertNotNull(alert2)
        assertEquals(WalkingMode.APPROACHING_CROSSING, alert2!!.targetMode)
        assertTrue("정지 준비 문구 포함", alert2.message.contains("정지 준비"))
        assertTrue("음향신호기 설치 안내 포함", alert2.message.contains("음향신호기가 설치되어 있습니다"))

        // 3. 4m 이내 진입 (횡단보도 진입 중)
        val sampleCrossing = LocationSample(
            lat = 35.159780,
            lon = 126.852880,
            accuracyMeters = 3.0f,
            bearingDegrees = 45.0f,
            elapsedRealtimeNanos = nowNanos - 1_000_000_000L,
            isMock = true
        )
        val alert3 = engine.evaluateApproach(
            sample = sampleCrossing,
            currentElapsedNanos = nowNanos,
            currentTimeMs = 240_000L,
            allowMock = true
        )
        assertNotNull(alert3)
        assertEquals(WalkingMode.CROSSING, alert3!!.targetMode)
        assertTrue("진입 문구 포함", alert3.message.contains("횡단보도 진입 중"))
    }

    @Test
    fun testUnverifiedFacilityMentionsUnverifiedStatus() {
        val engine = CrossingApproachEngine(listOf(unverifiedFacility))
        val nowNanos = 20_000_000_000L

        val sample = LocationSample(
            lat = 35.159720,
            lon = 126.852820,
            accuracyMeters = 5.0f,
            bearingDegrees = 45.0f,
            elapsedRealtimeNanos = nowNanos - 1_000_000_000L,
            isMock = true
        )
        val alert = engine.evaluateApproach(
            sample = sample,
            currentElapsedNanos = nowNanos,
            currentTimeMs = 100_000L,
            allowMock = true
        )
        assertNotNull(alert)
        assertTrue("미검증 안내가 명시되어야 함", alert!!.message.contains("미검증"))
    }

    @Test
    fun testOppositeLaneBearingMismatchSuppressesAlert() {
        // 반대편 차선/도로 보행 (진행 방위각 225도 vs 횡단보도 45도: 차이 180도)
        val engine = CrossingApproachEngine(listOf(verifiedFacility))
        val nowNanos = 20_000_000_000L

        val oppositeSample = LocationSample(
            lat = 35.159720,
            lon = 126.852820,
            accuracyMeters = 4.0f,
            bearingDegrees = 225.0f, // 180도 반대 방향
            elapsedRealtimeNanos = nowNanos - 1_000_000_000L,
            isMock = true
        )

        val alert = engine.evaluateApproach(
            sample = oppositeSample,
            currentElapsedNanos = nowNanos,
            currentTimeMs = 100_000L,
            allowMock = true
        )
        assertNull("방위각이 정반대인 경우 알림이 발생하지 않아야 함", alert)
    }

    @Test
    fun testPoorGpsAccuracyPreventsPreciseCrossingMode() {
        val engine = CrossingApproachEngine(listOf(verifiedFacility))
        val nowNanos = 20_000_000_000L

        // 거리는 10m 이내이지만 GPS 정확도가 55m로 불량
        val poorAccuracySample = LocationSample(
            lat = 35.159750,
            lon = 126.852850,
            accuracyMeters = 55.0f, // 20m 초과 불량
            bearingDegrees = 45.0f,
            elapsedRealtimeNanos = nowNanos - 1_000_000_000L,
            isMock = true
        )

        val alert = engine.evaluateApproach(
            sample = poorAccuracySample,
            currentElapsedNanos = nowNanos,
            currentTimeMs = 100_000L,
            allowMock = true
        )
        assertNotNull(alert)
        assertTrue("GPS 저하(Degraded) 플래그가 true여야 함", alert!!.isDegraded)
        assertEquals(WalkingMode.WALKING, alert.targetMode)
        assertTrue("GPS 신호 약함 안내 포함", alert.message.contains("GPS 신호가 약하여"))
    }
}
