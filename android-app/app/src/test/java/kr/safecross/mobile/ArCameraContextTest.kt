package kr.safecross.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.safecross.mobile.camera.ArFrameContext
import kr.safecross.mobile.camera.FakeCameraPipeManager
import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.decision.CrossingDecisionEngine
import kr.safecross.mobile.guidance.GuidanceArbiter
import kr.safecross.mobile.guidance.HapticFeedbackType
import kr.safecross.mobile.navigation.crossing.CrossingAim
import kr.safecross.mobile.perception.CameraVisionSignalEstimator
import kr.safecross.mobile.perception.FakeCrosswalkEstimator
import kr.safecross.mobile.perception.FrameQuality
import kr.safecross.mobile.perception.LockOnSignalAssociator
import kr.safecross.mobile.perception.NormalizedBox
import kr.safecross.mobile.perception.ObservedSignalState
import kr.safecross.mobile.perception.PedestrianSignalEstimator
import kr.safecross.mobile.perception.SignalObservation
import kr.safecross.mobile.perception.TwoTierHybridSignalEstimator
import kr.safecross.mobile.perception.VerifiedCrossingContext
import kr.safecross.mobile.sensor.FakeDevicePoseTracker
import kr.safecross.mobile.sensor.GeospatialHeadingMath
import kr.safecross.mobile.ui.screens.crossingassist.CrossingAssistEffect
import kr.safecross.mobile.ui.screens.crossingassist.CrossingAssistViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * ARCore 카메라 공간 정보(흔들림 추적·VPS 조준) 단위 테스트 (ADR-0041).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArCameraContextTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** 세로축(Y) 기준 왼쪽으로 yawDeg 돌린 카메라 */
    private fun yawed(yawDeg: Double, geo: ArFrameContext.GeoCamera? = null, sensorRotation: Int = 90): ArFrameContext {
        val half = Math.toRadians(yawDeg) / 2
        return ArFrameContext(
            rotation = ArFrameContext.rotationFromQuaternion(0f, sin(half).toFloat(), 0f, cos(half).toFloat()),
            focalLengthPx = 500f,
            portraitWidth = 480,
            portraitHeight = 640,
            geo = geo,
            sensorRotationDegrees = sensorRotation
        )
    }

    private val fiveDegreeShiftU = 0.5f + (tan(Math.toRadians(5.0)) * 500.0 / 480.0).toFloat()

    @Test
    fun worldDirectionAndProjectRoundTrip() {
        val ctx = yawed(0.0)
        val dir = ctx.worldDirection(0.3f, 0.7f)
        val (u, v) = ctx.project(dir)!!
        assertEquals(0.3f, u, 1e-4f)
        assertEquals(0.7f, v, 1e-4f)
        // 카메라 뒤쪽 방향은 투영되지 않음
        assertNull(ctx.project(floatArrayOf(0f, 0f, 1f)))
    }

    @Test
    fun turningCameraLeftMovesFixedTargetRightOnScreen() {
        val dir = yawed(0.0).worldDirection(0.5f, 0.4f)
        val (u, v) = yawed(5.0).project(dir)!!
        assertEquals(fiveDegreeShiftU, u, 0.01f)
        assertEquals(0.4f, v, 0.01f)
    }

    @Test
    fun portraitToSensorCoordinates() {
        assertEquals(0.2f to 0.9f, yawed(0.0, sensorRotation = 90).toSensorNormalized(0.1f, 0.2f))
        assertEquals(0.8f to 0.1f, yawed(0.0, sensorRotation = 270).toSensorNormalized(0.1f, 0.2f))
    }

    @Test
    fun geospatialCameraForwardHeadingAndPitch() {
        // 단위 쿼터니언: 카메라 -Z(정면) = 남쪽의 반대 = 북쪽, 수평
        val (h0, p0) = GeospatialHeadingMath.cameraForward(0f, 0f, 0f, 1f)
        assertEquals(0.0, (h0 + 360.0) % 360.0, 0.5)
        assertEquals(0.0, p0, 0.5)
        // 위(Y)축 기준 +90° 회전(왼쪽으로 돌림) → 서쪽(270°)
        val s = sin(Math.toRadians(45.0)).toFloat()
        val c = cos(Math.toRadians(45.0)).toFloat()
        val (h1, _) = GeospatialHeadingMath.cameraForward(0f, s, 0f, c)
        assertEquals(270.0, (h1 + 360.0) % 360.0, 0.5)
        // 동(X)축 기준 +20° 회전 → 위로 20°
        val sp = sin(Math.toRadians(10.0)).toFloat()
        val cp = cos(Math.toRadians(10.0)).toFloat()
        val (_, p2) = GeospatialHeadingMath.cameraForward(sp, 0f, 0f, cp)
        assertEquals(20.0, p2, 0.5)
    }

    /** 첫 프레임에만 적색 신호등을 검출하는 가짜 모델 */
    private class OneShotDetector : PedestrianSignalEstimator {
        var calls = 0
        override suspend fun estimate(frame: FrameRef): List<SignalObservation> {
            calls++
            if (calls > 1) return emptyList()
            return listOf(
                SignalObservation(
                    ephemeralTrackId = "yolo-1",
                    state = ObservedSignalState.RED,
                    score = 0.9f,
                    box = NormalizedBox(0.48f, 0.38f, 0.52f, 0.42f),
                    frameTimestampNanos = frame.timestampNanos,
                    quality = FrameQuality(0.8f, 0.9f, true),
                    modelVersion = "test"
                )
            )
        }
    }

    private fun newEstimator() = TwoTierHybridSignalEstimator(
        primaryDetector = OneShotDetector(),
        colorAnalyzer = CameraVisionSignalEstimator(testFallbackState = ObservedSignalState.RED)
    )

    @Test
    fun heldTargetFollowsCameraShakeWithArPose() = runBlocking {
        val estimator = newEstimator()
        estimator.estimate(FrameRef.createForTesting(timestampNanos = 1_000_000_000L, arContext = yawed(0.0)))
        // 2초 뒤 모델이 놓쳤고 카메라는 왼쪽으로 5° 흔들림 → 신호등 박스는 오른쪽으로 옮겨 유지
        val target = estimator.estimate(
            FrameRef.createForTesting(timestampNanos = 3_000_000_000L, arContext = yawed(5.0))
        ).first()
        assertTrue("AR 유지 중에는 UNKNOWN이 아니어야 함: ${target.state}", target.state != ObservedSignalState.UNKNOWN)
        assertEquals(fiveDegreeShiftU, target.box.centerX, 0.01f)

        // AR이 없으면 1.5초 유지 한도를 넘어 놓친 것으로 처리
        val plain = newEstimator()
        plain.estimate(FrameRef.createForTesting(timestampNanos = 1_000_000_000L))
        val lost = plain.estimate(FrameRef.createForTesting(timestampNanos = 3_000_000_000L)).first()
        assertEquals(ObservedSignalState.UNKNOWN, lost.state)
    }

    @Test
    fun preciseVpsOverridesCompassForAim() = runTest(testDispatcher) {
        // 나침반은 북쪽(정렬)이라고 하지만, VPS는 카메라가 동쪽(90°)을 본다고 함 → 왼쪽으로 돌리라는 안내
        val pose = FakeDevicePoseTracker()
        val vm = CrossingAssistViewModel(
            cameraPipeManager = FakeCameraPipeManager(),
            crosswalkEstimator = FakeCrosswalkEstimator(),
            signalEstimator = object : PedestrianSignalEstimator {
                override suspend fun estimate(frame: FrameRef) = emptyList<SignalObservation>()
            },
            signalAssociator = LockOnSignalAssociator(),
            decisionEngine = CrossingDecisionEngine(),
            poseTracker = pose,
            guidanceArbiter = GuidanceArbiter(),
            cameraVerticalFovDegrees = 74f
        )
        val effects = mutableListOf<CrossingAssistEffect.SpeakGuidance>()
        val job = launch { vm.effects.collect { if (it is CrossingAssistEffect.SpeakGuidance) effects.add(it) } }
        vm.onCameraPermissionGranted(VerifiedCrossingContext("CW-TEST-1", 0f, true))
        vm.updateAim(
            CrossingAim(
                targetBearingDeg = 0.0, distanceMeters = 20.0, compassBiasDeg = null, updatedAtMs = 0L,
                farEndLat = 35.00018, farEndLon = 126.0
            )
        )
        advanceUntilIdle()
        pose.setPoseWithCameraHeading(pitch = 4f, cameraHeading = 0f)
        val geo = ArFrameContext.GeoCamera(35.0, 126.0, 3.0, headingDeg = 90.0, pitchDeg = 4.0, yawAccuracyDeg = 4.0)
        vm.processFrame(FrameRef.createForTesting(timestampNanos = 2_000_000_000L, arContext = yawed(0.0, geo)))
        advanceUntilIdle()
        job.cancel()

        val aimSpeech = effects.firstOrNull { it.text.startsWith("왼쪽으로") }
        assertNotNull("VPS 기준 조준 안내가 나와야 함: ${effects.map { it.text }}", aimSpeech)
        assertEquals(HapticFeedbackType.TURN_LEFT, aimSpeech!!.hapticType)
    }
}
