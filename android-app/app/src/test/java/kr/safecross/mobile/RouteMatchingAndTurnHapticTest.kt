package kr.safecross.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.safecross.mobile.domain.model.DirectionAction
import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.model.Maneuver
import kr.safecross.mobile.domain.model.PedestrianRoute
import kr.safecross.mobile.domain.model.RouteSegment
import kr.safecross.mobile.guidance.HapticFeedbackType
import kr.safecross.mobile.location.LocationSample
import kr.safecross.mobile.navigation.engine.RouteProgressEngine
import kr.safecross.mobile.perception.DevicePose
import kr.safecross.mobile.ui.screens.navigation.NavigationEffect
import kr.safecross.mobile.ui.screens.navigation.NavigationViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 10/02 현장 문제 회귀 테스트:
 * - 지그재그 경로에서 경로상 위치가 옆 구간으로 점프하던 문제
 * - 화살표 아래 문구가 이미 지난 분기점 내용을 보여주던 문제
 * - 좌/우 회전 진동(왼쪽 짧게, 오른쪽 길게)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteMatchingAndTurnHapticTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // 기준점에서 동(x)·북(y) 방향 미터 오프셋 좌표
    private val baseLat = 35.0
    private val baseLon = 127.0
    private fun pt(xEast: Double, yNorth: Double) = LocationPoint(
        baseLat + yNorth / 111_320.0,
        baseLon + xEast / (111_320.0 * Math.cos(Math.toRadians(baseLat)))
    )

    /**
     * U자(지그재그) 경로: A(0,0) → 북쪽 30m → B(0,30) → 동쪽 6m → C(6,30) → 남쪽 70m → D(6,-40).
     * 돌아오는 C→D 구간이 A→B 구간과 6m 간격으로 나란히 지난다 (도착점은 출발점에서 멀리 둠).
     */
    private val uRoute: PedestrianRoute by lazy {
        val a = pt(0.0, 0.0); val b = pt(0.0, 30.0); val c = pt(6.0, 30.0); val d = pt(6.0, -40.0)
        PedestrianRoute(
            totalDistanceMeters = 106,
            totalDurationSeconds = 60,
            fullGeometry = listOf(a, b, c, d),
            maneuvers = listOf(
                Maneuver(0, 0, a, "북쪽으로 30m 직진", turnType = 11),
                Maneuver(1, 1, b, "우회전 후 6m 이동", turnType = 13),
                Maneuver(2, 2, c, "우회전 후 70m 이동", turnType = 13),
                Maneuver(3, 3, d, "도착", turnType = 201)
            ),
            segments = listOf(RouteSegment(0, "u", 106, 90, listOf(a, b, c, d)))
        )
    }

    private fun sample(p: LocationPoint, timeMs: Long) =
        LocationSample(lat = p.lat, lon = p.lon, accuracyMeters = 5f, speedMps = 1.2f, timestampEpochMs = timeMs)

    @Test
    fun progressDoesNotJumpToParallelLaterSegment() {
        val engine = RouteProgressEngine(uRoute)
        engine.updateProgress(sample(pt(0.5, 8.0), 1_000L))
        // GPS 오차로 돌아오는 구간(C→D, x=6)에 더 가까운 위치(x=3.5): 거리상으론 C→D가 더 가깝지만
        // 1초 만에 경로상 46m를 건너뛸 수 없으므로 A→B(경로상 약 10m)에 머물러야 함
        val p = engine.updateProgress(sample(pt(3.5, 10.0), 2_000L))
        assertEquals(10.0, p.distanceAlongRouteMeters, 1.5)
    }

    @Test
    fun upcomingManeuverIsTheNextNotYetReachedPoint() {
        val engine = RouteProgressEngine(uRoute)
        val before = engine.updateProgress(sample(pt(0.0, 10.0), 1_000L))
        assertEquals(1, before.upcomingManeuverIndex)
        assertEquals(20.0, before.distanceToUpcomingManeuverMeters, 1.0)

        // B를 지나 동쪽으로 2m: 다음 분기점은 C(경로상 36m), 남은 거리 약 4m
        val after = engine.updateProgress(sample(pt(2.0, 30.0), 20_000L))
        assertEquals(2, after.upcomingManeuverIndex)
        assertEquals(4.0, after.distanceToUpcomingManeuverMeters, 1.0)
    }

    @Test
    fun turnHapticsAreShortForLeftAndLongForRight() {
        assertEquals(HapticFeedbackType.TURN_LEFT, HapticFeedbackType.forTurn(DirectionAction.LEFT))
        assertEquals(HapticFeedbackType.TURN_LEFT, HapticFeedbackType.forTurn(DirectionAction.SLIGHT_LEFT))
        assertEquals(HapticFeedbackType.TURN_RIGHT, HapticFeedbackType.forTurn(DirectionAction.RIGHT))
        assertEquals(HapticFeedbackType.TURN_RIGHT, HapticFeedbackType.forTurn(DirectionAction.SLIGHT_RIGHT))
        assertEquals(null, HapticFeedbackType.forTurn(DirectionAction.STRAIGHT))
        val leftMs = HapticFeedbackType.TURN_LEFT.patternMs.sum()
        val rightMs = HapticFeedbackType.TURN_RIGHT.patternMs.sum()
        assertTrue("왼쪽($leftMs ms)은 오른쪽($rightMs ms)보다 짧아야 함", leftMs * 4 <= rightMs)
    }

    @Test
    fun misalignedBodyTriggersDirectionalHapticEveryThreeSeconds() = runTest(testDispatcher) {
        val vm = NavigationViewModel()
        val haptics = mutableListOf<HapticFeedbackType>()
        val job = launch { vm.effects.collect { if (it is NavigationEffect.Haptic) haptics.add(it.type) } }
        vm.setRoute(uRoute)
        vm.processLocationSample(sample(pt(0.0, 5.0), 1_000L), currentTimeMs = 1_000L)

        // 경로는 북쪽(0°). 동쪽(90°)을 보면 왼쪽으로 돌아야 함 → 짧은 진동
        vm.processDevicePose(DevicePose(-70f, 0f, 90f), currentTimeMs = 10_000L)
        vm.processDevicePose(DevicePose(-70f, 0f, 90f), currentTimeMs = 11_000L) // 3초 이내 반복 없음
        // 서쪽(270°)을 보면 오른쪽으로 돌아야 함 → 긴 진동
        vm.processDevicePose(DevicePose(-70f, 0f, 270f), currentTimeMs = 13_100L)
        vm.processDevicePose(DevicePose(-70f, 0f, 270f), currentTimeMs = 16_200L)
        // 정면을 보면 진동 없음
        vm.processDevicePose(DevicePose(-70f, 0f, 0f), currentTimeMs = 19_300L)
        vm.processDevicePose(DevicePose(-70f, 0f, 0f), currentTimeMs = 22_400L)
        advanceUntilIdle()
        job.cancel()

        assertEquals(HapticFeedbackType.TURN_LEFT, haptics.first())
        assertTrue(haptics.contains(HapticFeedbackType.TURN_RIGHT))
        assertEquals("정면 정렬 후에는 방향 진동이 멈춰야 함", HapticFeedbackType.TURN_RIGHT, haptics.last())
        assertTrue(haptics.size <= 3)
    }
}
