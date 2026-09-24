package kr.safecross.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.safecross.mobile.data.repository.FakeRouteRepository
import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.model.WalkingMode
import kr.safecross.mobile.ui.screens.navigation.NavigationEffect
import kr.safecross.mobile.ui.screens.navigation.NavigationViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: NavigationViewModel
    private lateinit var fakeRepository: FakeRouteRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = NavigationViewModel()
        fakeRepository = FakeRouteRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setRoute initializes navigation to WALKING state with guidance speech`() = runTest(testDispatcher) {
        val effects = mutableListOf<NavigationEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        val routeResult = fakeRepository.getPedestrianRoute(
            origin = LocationPoint(35.1595, 126.8526),
            destination = LocationPoint(35.1610, 126.8550)
        )
        val route = routeResult.getOrThrow()

        viewModel.setRoute(route)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.route)
        assertEquals(0, state.currentManeuverIndex)
        assertEquals(WalkingMode.WALKING, state.walkingMode)
        assertFalse(state.isFinished)

        // 음성 지침 발행 확인
        assertTrue(effects.isNotEmpty())
        val speech = (effects[0] as NavigationEffect.SpeakGuidance).text
        assertTrue(speech.contains("일반 보행 중"))
        assertTrue(speech.contains("점자블록"))

        job.cancel()
    }

    @Test
    fun `advanceToNextManeuver transitions through 4-stage walking state machine`() = runTest(testDispatcher) {
        val effects = mutableListOf<NavigationEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        val route = fakeRepository.getPedestrianRoute(
            origin = LocationPoint(35.1595, 126.8526),
            destination = LocationPoint(35.1610, 126.8550)
        ).getOrThrow()

        viewModel.setRoute(route)
        advanceUntilIdle()
        assertEquals(WalkingMode.WALKING, viewModel.uiState.value.walkingMode)

        // 1회 전진 -> 횡단보도 시설(facilityType == "횡단보도") Maneuver -> APPROACHING_CROSSING 전이
        viewModel.advanceToNextManeuver()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.currentManeuverIndex)
        assertEquals(WalkingMode.APPROACHING_CROSSING, viewModel.uiState.value.walkingMode)

        // 2회 전진 -> CROSSING 또는 WALKING 전이
        viewModel.advanceToNextManeuver()
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.currentManeuverIndex)

        // 3회 전진 -> 마지막 지점 도달 시 NavigationFinished 발행 및 IDLE 전이
        viewModel.advanceToNextManeuver()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isFinished)
        assertEquals(WalkingMode.IDLE, viewModel.uiState.value.walkingMode)
        assertTrue(effects.any { it is NavigationEffect.NavigationFinished })

        job.cancel()
    }

    @Test
    fun `stopNavigation marks finished and emits NavigationFinished`() = runTest(testDispatcher) {
        val effects = mutableListOf<NavigationEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        viewModel.stopNavigation()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFinished)
        assertEquals(WalkingMode.IDLE, viewModel.uiState.value.walkingMode)
        assertTrue(effects.any { it is NavigationEffect.NavigationFinished })

        job.cancel()
    }

    @Test
    fun `processLocationSample speaks guidance when approaching maneuver`() = runTest(testDispatcher) {
        val effects = mutableListOf<NavigationEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        val route = fakeRepository.getPedestrianRoute(
            origin = LocationPoint(35.1595, 126.8526),
            destination = LocationPoint(35.1610, 126.8550)
        ).getOrThrow()

        viewModel.setRoute(route)
        advanceUntilIdle()
        effects.clear()

        val nextManeuver = route.maneuvers.getOrNull(1)
        assertNotNull(nextManeuver)
        val targetLat = nextManeuver!!.location.lat
        val targetLon = nextManeuver.location.lon

        // 약 25m 남은 위치 (위도 0.0002도 차이 약 22m)
        val sample25m = kr.safecross.mobile.location.LocationSample(
            lat = targetLat - 0.0002,
            lon = targetLon,
            accuracyMeters = 3.0f,
            elapsedRealtimeNanos = System.nanoTime(),
            signalStrengthPercent = 90
        )
        viewModel.processLocationSample(sample25m)
        advanceUntilIdle()

        val hasApproachSpeech = effects.any {
            it is NavigationEffect.SpeakGuidance && (it.text.contains("앞") || it.text.contains("횡단보도") || it.text.contains("미터") || it.text.contains("걸음"))
        }
        assertTrue(hasApproachSpeech)

        job.cancel()
    }

    @Test
    fun `processDevicePose detects misalignment and alignment with haptic feedback`() = runTest(testDispatcher) {
        val effects = mutableListOf<NavigationEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        val fakePoseTracker = kr.safecross.mobile.sensor.FakeDevicePoseTracker()
        viewModel.setDevicePoseTracker(fakePoseTracker)

        val route = fakeRepository.getPedestrianRoute(
            origin = LocationPoint(35.1595, 126.8526),
            destination = LocationPoint(35.1610, 126.8550)
        ).getOrThrow()

        viewModel.setRoute(route, poseTracker = fakePoseTracker)
        advanceUntilIdle()
        effects.clear()

        // 1. 목표 방향과 180도 반대 방향 (헤딩 불일치)
        val targetBearing = viewModel.calculateTargetBearing() ?: 45.0
        val oppositeHeading = ((targetBearing + 180.0) % 360.0).toFloat()
        fakePoseTracker.setPose(pitch = 10f, roll = 0f, heading = oppositeHeading)
        advanceUntilIdle()

        assertFalse("180도 반대 방향일 때 미정대 상태여야 함", viewModel.uiState.value.isOrientationAligned)
        assertTrue("몸 회전 지시 안내 문구가 포함되어야 함", viewModel.uiState.value.alignmentPromptMessage.contains("돌려") || viewModel.uiState.value.alignmentPromptMessage.contains("향하세요"))

        // 2. 가야 할 진행 방향으로 몸 회전 정대 완료
        val alignedHeading = targetBearing.toFloat()
        fakePoseTracker.setPose(pitch = 10f, roll = 0f, heading = alignedHeading)
        advanceUntilIdle()

        assertTrue("진행 방향 정대 상태여야 함", viewModel.uiState.value.isOrientationAligned)
        assertTrue(
            "정대 확인 햅틱 또는 음성 지침이 발행되어야 함",
            effects.any {
                it is NavigationEffect.SpeakGuidance &&
                (it.hapticType == kr.safecross.mobile.guidance.HapticFeedbackType.ORIENTATION_ALIGNED ||
                 it.text.contains("올바른 진행 방향입니다"))
            }
        )

        job.cancel()
    }

    @Test
    fun `crossing facility approach triggers TriggerCrossingAssist effect`() = runTest(testDispatcher) {
        val effects = mutableListOf<NavigationEffect>()
        val job = launch {
            viewModel.effects.collect { effects.add(it) }
        }

        val route = fakeRepository.getPedestrianRoute(
            origin = LocationPoint(35.1595, 126.8526),
            destination = LocationPoint(35.1610, 126.8550)
        ).getOrThrow()

        val facilities = listOf(
            kr.safecross.mobile.navigation.crossing.CrossingFacility(
                id = "CW-TEST-1",
                lat = 35.1600,
                lon = 126.8530,
                roadName = "테스트 횡단보도",
                approachBearingDeg = 45.0
            )
        )

        viewModel.setRoute(route, facilities = facilities)
        advanceUntilIdle()
        effects.clear()

        // 횡단보도 정지선 대기 지점 (약 5m 이내)
        val sampleAtCrossing = kr.safecross.mobile.location.LocationSample(
            lat = 35.160001,
            lon = 126.853001,
            accuracyMeters = 2.0f,
            bearingDegrees = 45.0f,
            elapsedRealtimeNanos = System.nanoTime(),
            signalStrengthPercent = 95
        )

        viewModel.processLocationSample(sampleAtCrossing)
        advanceUntilIdle()

        val triggered = effects.any { it is NavigationEffect.TriggerCrossingAssist }
        assertTrue("횡단보도 접근 시 자동으로 카메라 보조 트리거가 발생해야 함", triggered)

        job.cancel()
    }

    @Test
    fun `walking speed blends GPS course with compass heading to suppress arm sway`() = runTest(testDispatcher) {
        val route = fakeRepository.getPedestrianRoute(
            origin = LocationPoint(35.1595, 126.8526),
            destination = LocationPoint(35.1610, 126.8550)
        ).getOrThrow()

        viewModel.setRoute(route)
        advanceUntilIdle()

        // 1. 보행 중 GPS 샘플 수신 (속도 1.2 m/s, GPS 진행 궤적 90도)
        val movingSample = kr.safecross.mobile.location.LocationSample(
            lat = 35.1595,
            lon = 126.8526,
            accuracyMeters = 3.0f,
            speedMps = 1.2f,
            bearingDegrees = 90.0f,
            elapsedRealtimeNanos = System.nanoTime()
        )
        viewModel.processLocationSample(movingSample)
        advanceUntilIdle()

        // 2. 보행 중 팔 흔들림으로 나침반 헤딩이 110도로 흔들린 센서 포즈 유입
        val swayPose = kr.safecross.mobile.perception.DevicePose(
            pitchDegrees = -30f,
            rollDegrees = 0f,
            headingDegrees = 110.0f
        )
        viewModel.processDevicePose(swayPose)
        advanceUntilIdle()

        // 3. 상보 필터(GPS 65% + 나침반 35%)로 융합되어 110도에서 90도 방향으로 당겨져야 함
        // delta = 90 - 110 = -20, fused = 110 + (-20 * 0.65) = 97.0도
        val currentHeading = viewModel.uiState.value.currentHeadingDegrees
        assertTrue(
            "융합된 헤딩($currentHeading)은 순수 나침반(110도)보다 GPS 진행 방향(90도)에 더 가까워야 함",
            currentHeading in 95.0f..100.0f
        )
    }
}

