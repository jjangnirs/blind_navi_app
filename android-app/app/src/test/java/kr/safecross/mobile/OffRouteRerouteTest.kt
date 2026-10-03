package kr.safecross.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.model.Maneuver
import kr.safecross.mobile.domain.model.PedestrianRoute
import kr.safecross.mobile.domain.model.RouteSegment
import kr.safecross.mobile.domain.repository.RouteRepository
import kr.safecross.mobile.guidance.GuidanceArbiter
import kr.safecross.mobile.guidance.HapticFeedbackType
import kr.safecross.mobile.location.LocationSample
import kr.safecross.mobile.navigation.crossing.CrossingAutoTriggerPolicy
import kr.safecross.mobile.navigation.crossing.RouteCrosswalk
import kr.safecross.mobile.navigation.engine.RouteProgressEngine
import kr.safecross.mobile.perception.DevicePose
import kr.safecross.mobile.ui.screens.navigation.NavigationEffect
import kr.safecross.mobile.ui.screens.navigation.NavigationViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 10/03 현장 문제 회귀 테스트:
 * - 정확한 GPS로 경로와 23~27 m 떨어져 2분간 머물렀지만 35 m 기준에 못 미쳐 재탐색이 안 되던 문제
 * - 몸 방향이 크게 어긋나도 화면에만 표시되고 음성 안내가 없던 문제
 * - 출발점에서 31 m 떨어진 곳에서 '횡단보도 남은 거리 0 m'로 카메라 화면이 바로 열리던 문제
 * - 재탐색 시 현재 보행 방향을 경로 탐색에 넘기지 않던 문제
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OffRouteRerouteTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val baseLat = 35.0
    private val baseLon = 127.0
    private fun pt(xEast: Double, yNorth: Double) = LocationPoint(
        baseLat + yNorth / 111_320.0,
        baseLon + xEast / (111_320.0 * Math.cos(Math.toRadians(baseLat)))
    )

    /** 북쪽으로 곧게 300 m */
    private val northRoute: PedestrianRoute by lazy {
        val a = pt(0.0, 0.0); val b = pt(0.0, 300.0)
        PedestrianRoute(
            totalDistanceMeters = 300,
            totalDurationSeconds = 240,
            fullGeometry = listOf(a, b),
            maneuvers = listOf(
                Maneuver(0, 0, a, "북쪽으로 300m 직진", turnType = 11),
                Maneuver(1, 1, b, "도착", turnType = 201)
            ),
            segments = listOf(RouteSegment(0, "n", 300, 240, listOf(a, b)))
        )
    }

    private fun sample(p: LocationPoint, timeMs: Long, acc: Float = 4f, speed: Float = 1.2f, bearing: Float? = null) =
        LocationSample(lat = p.lat, lon = p.lon, accuracyMeters = acc, speedMps = speed, bearingDegrees = bearing, timestampEpochMs = timeMs)

    // ---------------- 지속 이탈 판정 ----------------

    @Test
    fun sustainedModerateDeviationWithGoodGpsBecomesOffRoute() {
        val engine = RouteProgressEngine(northRoute, offRouteThresholdMeters = 35.0, minConsecutiveOffRoute = 4)
        // 경로에서 동쪽으로 25 m (35 m 기준 미만) 떨어져 1초 간격으로 북쪽으로 걸음
        var last = engine.updateProgress(sample(pt(25.0, 50.0), 0L))
        assertFalse(last.isOffRoute)
        for (i in 1..9) last = engine.updateProgress(sample(pt(25.0, 50.0 + i), i * 1_000L))
        assertFalse("10초가 지나기 전에는 이탈이 아님", last.isOffRoute)
        last = engine.updateProgress(sample(pt(25.0, 60.0), 10_000L))
        assertTrue("25 m 이탈이 10초 지속되면 이탈", last.isOffRoute)
        assertEquals("SUSTAINED", last.offRouteReason)

        // 경로 가까이(10 m) 돌아오면 해제
        last = engine.updateProgress(sample(pt(10.0, 61.0), 11_000L))
        assertFalse(last.isOffRoute)
    }

    @Test
    fun sustainedRuleIgnoresInaccurateGpsAndParallelSidewalk() {
        val engine = RouteProgressEngine(northRoute, offRouteThresholdMeters = 35.0, minConsecutiveOffRoute = 4)
        var last = engine.updateProgress(sample(pt(25.0, 50.0), 0L, acc = 14f))
        // 정확도 14 m → 기준 28 m: 25 m 떨어져도 이탈로 세지 않음
        for (i in 1..15) last = engine.updateProgress(sample(pt(25.0, 50.0 + i), i * 1_000L, acc = 14f))
        assertFalse(last.isOffRoute)

        // 길 건너편 보도처럼 12 m 떨어져 계속 걸어도 이탈 아님
        val engine2 = RouteProgressEngine(northRoute, offRouteThresholdMeters = 35.0, minConsecutiveOffRoute = 4)
        for (i in 0..30) last = engine2.updateProgress(sample(pt(12.0, 20.0 + i), i * 1_000L))
        assertFalse(last.isOffRoute)
    }

    // ---------------- 횡단보도 자동 전환 ----------------

    @Test
    fun crosswalkAtRouteStartDoesNotTriggerWhenFarFromRoute() {
        val policy = CrossingAutoTriggerPolicy(listOf(RouteCrosswalk(0, 0.0, "횡단보도 후 16m 이동")))
        // 경로 시작점 투영으로 남은 거리 0 m이지만 실제로는 경로에서 31 m 떨어져 있음
        assertNull(policy.evaluate(0.0, 0f, 6.9f, false, 1_000L, crossTrackMeters = 31.0))
        // 경로 위(4 m)에 있으면 전환
        assertNotNull(policy.evaluate(0.0, 0f, 6.9f, false, 2_000L, crossTrackMeters = 4.0))
    }

    // ---------------- 회전 음성 안내 ----------------

    @Test
    fun misalignedHeadingIsSpokenAfterThreeSecondsAndRepeatedEveryTen() = runTest(testDispatcher) {
        val vm = NavigationViewModel(guidanceArbiter = GuidanceArbiter(safetyCooldownMs = 0L, crossingCooldownMs = 0L))
        val speech = mutableListOf<NavigationEffect.SpeakGuidance>()
        val job = launch { vm.effects.collect { if (it is NavigationEffect.SpeakGuidance) speech.add(it) } }
        vm.setRoute(northRoute)
        vm.processLocationSample(sample(pt(0.0, 5.0), 1_000L), currentTimeMs = 1_000L)
        advanceUntilIdle()
        speech.clear()

        // 경로는 북쪽인데 남쪽(180°)을 보고 걸음
        for (t in 10_000L..24_000L step 500L) vm.processDevicePose(DevicePose(-70f, 0f, 180f), currentTimeMs = t)
        advanceUntilIdle()
        job.cancel()

        val turns = speech.filter { it.text.contains("몸을 돌려") }
        assertEquals("3초 뒤 1회, 10초 뒤 1회: ${speech.map { it.text }}", 2, turns.size)
        assertTrue(turns.first().hapticType == HapticFeedbackType.TURN_LEFT || turns.first().hapticType == HapticFeedbackType.TURN_RIGHT)
    }

    @Test
    fun turnSpeechIsSilentWhileCrossingCameraIsOpen() = runTest(testDispatcher) {
        val vm = NavigationViewModel()
        val speech = mutableListOf<NavigationEffect.SpeakGuidance>()
        val job = launch { vm.effects.collect { if (it is NavigationEffect.SpeakGuidance) speech.add(it) } }
        vm.setRoute(northRoute)
        vm.processLocationSample(sample(pt(0.0, 5.0), 1_000L), currentTimeMs = 1_000L)
        vm.onCrossingAssistOpened(1_000L)
        advanceUntilIdle()
        speech.clear()
        for (t in 10_000L..24_000L step 500L) vm.processDevicePose(DevicePose(-70f, 0f, 180f), currentTimeMs = t)
        advanceUntilIdle()
        job.cancel()
        assertTrue(speech.none { it.text.contains("몸을 돌려") })
    }

    // ---------------- 재탐색 ----------------

    private class CapturingRepository(private val route: PedestrianRoute) : RouteRepository {
        var lastHeading: Int? = null
        var calls = 0
        override suspend fun getPedestrianRoute(
            origin: LocationPoint,
            destination: LocationPoint,
            originName: String,
            destinationName: String,
            excludeStairs: Boolean,
            startHeadingDegrees: Int?
        ): Result<PedestrianRoute> {
            calls++
            lastHeading = startHeadingDegrees
            return Result.success(route)
        }
    }

    @Test
    fun sustainedDeviationReroutesWithWalkingDirection() = runTest(testDispatcher) {
        val repo = CapturingRepository(northRoute)
        val vm = NavigationViewModel(routeRepository = repo)
        vm.setRoute(northRoute)
        // 출발점 보정 재탐색이 끼지 않도록 경로 위에서 시작
        vm.processLocationSample(sample(pt(0.0, 3.0), 0L), currentTimeMs = 0L)
        // 동쪽으로 25 m 벗어나 동쪽(90°)으로 걸으며 12초 유지
        for (i in 1..12) {
            vm.processLocationSample(sample(pt(25.0 + i * 0.2, 10.0), i * 1_000L, bearing = 90f), currentTimeMs = i * 1_000L)
        }
        advanceUntilIdle()
        assertEquals(1, repo.calls)
        assertEquals(90, repo.lastHeading)
    }

    @Test
    fun rerouteWaitsUntilCrossingCameraCloses() = runTest(testDispatcher) {
        val repo = CapturingRepository(northRoute)
        val vm = NavigationViewModel(routeRepository = repo)
        vm.setRoute(northRoute)
        vm.processLocationSample(sample(pt(0.0, 3.0), 0L), currentTimeMs = 0L)
        vm.onCrossingAssistOpened(0L)
        // 횡단보도에서 신호 대기: 경로와 25 m 떨어진 채 정지
        for (i in 1..15) vm.processLocationSample(sample(pt(25.0, 10.0), i * 1_000L, speed = 0f), currentTimeMs = i * 1_000L)
        advanceUntilIdle()
        assertEquals("카메라 화면이 열려 있는 동안은 재탐색 보류", 0, repo.calls)

        vm.onCrossingAssistClosed()
        vm.processLocationSample(sample(pt(25.0, 10.0), 16_000L, speed = 0f), currentTimeMs = 16_000L)
        advanceUntilIdle()
        assertEquals("화면이 닫히면 바로 재탐색", 1, repo.calls)
    }
}
