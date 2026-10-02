package kr.safecross.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kr.safecross.mobile.data.repository.FakeRouteRepository
import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.perception.DevicePose
import kr.safecross.mobile.sensor.GeospatialHeadingMath
import kr.safecross.mobile.sensor.VpsHeadingSample
import kr.safecross.mobile.ui.screens.navigation.HeadingSource
import kr.safecross.mobile.ui.screens.navigation.NavigationViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * ARCore VPS 방향 적용 (ADR-0037): 축 선택 계산과 방향 출처 결정 테스트.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VpsHeadingFusionTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- 쿼터니언 헬퍼 (x, y, z, w) ----
    private fun axisAngle(ax: Double, ay: Double, az: Double, deg: Double): DoubleArray {
        val h = Math.toRadians(deg) / 2
        return doubleArrayOf(ax * sin(h), ay * sin(h), az * sin(h), cos(h))
    }

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
    )

    private fun heading(q: DoubleArray) =
        GeospatialHeadingMath.navigationHeading(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat())

    @Test
    fun uprightPhoneUsesCameraDirection() {
        // 항등 회전: 휴대폰을 세워 카메라가 북쪽을 봄
        val north = heading(doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        assertEquals(0.0, north.headingDegrees, 0.5)
        assertFalse(north.isTopAxis)

        // 세운 채로 동쪽을 봄 (Up축 기준 -90° 회전)
        val east = heading(axisAngle(0.0, 1.0, 0.0, -90.0))
        assertEquals(90.0, east.headingDegrees, 0.5)
        assertFalse(east.isTopAxis)
    }

    @Test
    fun flatPhoneUsesTopEdgeDirectionLikeCompass() {
        // 화면을 위로 눕히고 윗변이 북쪽 (X축 기준 -90° 회전)
        val flat = axisAngle(1.0, 0.0, 0.0, -90.0)
        val north = heading(flat)
        assertEquals(0.0, north.headingDegrees, 0.5)
        assertTrue(north.isTopAxis)

        // 눕힌 채로 윗변이 동쪽
        val east = heading(mul(axisAngle(0.0, 1.0, 0.0, -90.0), flat))
        assertEquals(90.0, east.headingDegrees, 0.5)
        assertTrue(east.isTopAxis)
    }

    // ---- 방향 출처 결정 ----
    private fun newNavigation(): NavigationViewModel {
        val vm = NavigationViewModel()
        val route = kotlinx.coroutines.runBlocking {
            FakeRouteRepository().getPedestrianRoute(LocationPoint(35.1595, 126.8526), LocationPoint(35.1610, 126.8550)).getOrThrow()
        }
        vm.setRoute(route)
        return vm
    }

    private fun pose(compass: Float) = DevicePose(pitchDegrees = -80f, rollDegrees = 0f, headingDegrees = compass)

    private fun vps(heading: Double, yawAcc: Double = 3.0, topAxis: Boolean = true) =
        VpsHeadingSample(headingDegrees = heading, yawAccuracyDegrees = yawAcc, isTopAxis = topAxis, elapsedRealtimeMs = 0L)

    @Test
    fun accurateVpsOverridesBiasedCompass() {
        val vm = newNavigation()
        vm.onVpsHeading(vps(100.0), receivedAtMs = 10_000L)
        vm.processDevicePose(pose(70f), currentTimeMs = 10_100L) // 나침반이 30° 틀어진 상태
        assertEquals(100f, vm.uiState.value.currentHeadingDegrees, 0.5f)
        assertEquals(HeadingSource.VPS, vm.uiState.value.headingSource)
    }

    @Test
    fun compassIsCorrectedWithMeasuredBiasAfterVpsIsLost() {
        val vm = newNavigation()
        vm.onVpsHeading(vps(100.0), receivedAtMs = 10_000L)
        vm.processDevicePose(pose(70f), currentTimeMs = 10_100L) // 보정값 +30° 측정

        // VPS 끊김(휴대폰을 바닥으로 숙임) 5초 후: 나침반 75° + 30° = 105°
        vm.processDevicePose(pose(75f), currentTimeMs = 15_000L)
        assertEquals(105f, vm.uiState.value.currentHeadingDegrees, 0.5f)
        assertEquals(HeadingSource.VPS_CORRECTED_COMPASS, vm.uiState.value.headingSource)

        // 60초 경과 후에는 보정값 폐기, 순수 나침반
        vm.processDevicePose(pose(75f), currentTimeMs = 80_000L)
        assertEquals(75f, vm.uiState.value.currentHeadingDegrees, 0.5f)
        assertEquals(HeadingSource.COMPASS, vm.uiState.value.headingSource)
    }

    @Test
    fun inaccurateVpsIsIgnored() {
        val vm = newNavigation()
        vm.onVpsHeading(vps(100.0, yawAcc = 30.0), receivedAtMs = 10_000L) // 실내 등 VPS 미확정
        vm.processDevicePose(pose(70f), currentTimeMs = 10_100L)
        assertEquals(70f, vm.uiState.value.currentHeadingDegrees, 0.5f)
        assertEquals(HeadingSource.COMPASS, vm.uiState.value.headingSource)
    }

    @Test
    fun uprightVpsDoesNotMeasureCompassBias() {
        val vm = newNavigation()
        // 세워 든 상태(카메라 축)에서는 나침반(윗변 축)과 축이 달라 보정값을 재지 않음
        vm.onVpsHeading(vps(100.0, topAxis = false), receivedAtMs = 10_000L)
        vm.processDevicePose(pose(70f), currentTimeMs = 10_100L)
        assertEquals(HeadingSource.VPS, vm.uiState.value.headingSource)

        vm.processDevicePose(pose(75f), currentTimeMs = 15_000L)
        assertEquals(HeadingSource.COMPASS, vm.uiState.value.headingSource)
        assertEquals(75f, vm.uiState.value.currentHeadingDegrees, 0.5f)
    }
}
