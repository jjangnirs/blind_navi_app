package kr.safecross.mobile.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.safecross.mobile.perception.DevicePose
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 카메라 각도 및 기울기 가이던스 상태
 */
enum class TiltGuidance(val instruction: String, val isSuitable: Boolean) {
    SUITABLE("적절한 촬영 각도입니다.", true),
    TILT_UP("카메라를 정면으로 들어주세요.", false),
    TILT_DOWN("카메라를 조금 내려주세요.", false),
    LEVEL_PHONE("스마트폰을 똑바로 들어주세요.", false)
}

/**
 * 기기 자세/기울기 센서 추적기 인터페이스
 */
interface DevicePoseTracker {
    val currentPose: StateFlow<DevicePose>
    val tiltGuidance: StateFlow<TiltGuidance>

    fun startTracking()
    fun stopTracking()
}

/**
 * Android SensorManager 기반 실제 가속도/기울기 추적기
 */
class ProductionDevicePoseTracker(
    context: Context
) : DevicePoseTracker, SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _currentPose = MutableStateFlow(DevicePose(pitchDegrees = 0f, rollDegrees = 0f, headingDegrees = 0f))
    override val currentPose: StateFlow<DevicePose> = _currentPose.asStateFlow()

    private val _tiltGuidance = MutableStateFlow(TiltGuidance.SUITABLE)
    override val tiltGuidance: StateFlow<TiltGuidance> = _tiltGuidance.asStateFlow()

    private var isTracking = false
    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)

    override fun startTracking() {
        if (!isTracking && sensorManager != null) {
            var registeredAny = false
            if (rotationVectorSensor != null) {
                sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
                registeredAny = true
            }
            if (accelerometer != null && rotationVectorSensor == null) {
                // 회전 벡터 센서가 없는 기기를 위한 가속도계 폴백
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
                registeredAny = true
            }
            isTracking = registeredAny
        }
    }

    override fun stopTracking() {
        if (isTracking && sensorManager != null) {
            sensorManager.unregisterListener(this)
            isTracking = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                try {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationValues)

                    // orientationValues[0]: Azimuth (-PI ~ +PI) -> 0.0 ~ 360.0 도
                    val azimuthRad = orientationValues[0].toDouble()
                    val heading = ((Math.toDegrees(azimuthRad) + 360.0) % 360.0).toFloat()

                    // 후면 카메라 시선 벡터 (기기 좌표계 (0, 0, -1)^T)의 월드 좌표계 z성분 (-R[8]):
                    // 카메라가 지평선을 바라보면 vz = 0, 하늘은 vz > 0, 바닥은 vz < 0.
                    val vz = -rotationMatrix[8].toDouble().coerceIn(-1.0, 1.0)
                    val pitch = Math.toDegrees(kotlin.math.asin(vz)).toFloat()

                    // 기기 가로축 (1, 0, 0)^T의 월드 z성분 (R[6]):
                    val vxZ = rotationMatrix[6].toDouble().coerceIn(-1.0, 1.0)
                    val roll = Math.toDegrees(kotlin.math.asin(vxZ)).toFloat()

                    val pose = DevicePose(pitchDegrees = pitch, rollDegrees = roll, headingDegrees = heading)
                    _currentPose.value = pose
                    _tiltGuidance.value = evaluateGuidance(pitch, roll, _tiltGuidance.value)
                } catch (_: Exception) {}
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]

                // 세로 모드(Portrait Camera) 기준 Pitch & Roll 계산 (단위: 도)
                // 전방 수평을 볼 때 pitch ≈ 0, 하늘 볼 때 pitch > 0, 바닥 볼 때 pitch < 0
                val pitch = Math.toDegrees(atan2(-az.toDouble(), sqrt((ax * ax + ay * ay).toDouble()))).toFloat()
                val roll = Math.toDegrees(atan2(ax.toDouble(), ay.toDouble())).toFloat()

                val currentHeading = _currentPose.value.headingDegrees
                val pose = DevicePose(pitchDegrees = pitch, rollDegrees = roll, headingDegrees = currentHeading)
                _currentPose.value = pose
                _tiltGuidance.value = evaluateGuidance(pitch, roll, _tiltGuidance.value)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        fun evaluateGuidance(
            pitch: Float,
            roll: Float,
            previous: TiltGuidance? = null
        ): TiltGuidance {
            // 1. 좌우 기울기(Roll): 수평에서 32도 이상 기울어졌을 때 수평 정렬 유도 (히스테리시스 5도 적용)
            val rollLimit = if (previous == TiltGuidance.LEVEL_PHONE) 25f else 32f
            if (kotlin.math.abs(roll) > rollLimit) {
                return TiltGuidance.LEVEL_PHONE
            }

            // 2. 상하 각도(Pitch, 전방 수평 기준 Elevation Angle):
            // - 수평선 기준 -20도(횡단보도 앞쪽) ~ +30도(건너편 높은 신호등)가 보행자 기준 완벽한 촬영 각도
            // - 카메라가 너무 바닥을 향하는 경우 (pitch < -22도 또는 이전 TILT_UP 상태 시 < -16도) -> TILT_UP
            val downLimit = if (previous == TiltGuidance.TILT_UP) -16f else -22f
            if (pitch < downLimit) {
                return TiltGuidance.TILT_UP
            }

            // - 카메라가 너무 하늘을 향하는 경우 (pitch > 35도 또는 이전 TILT_DOWN 상태 시 > 28도) -> TILT_DOWN
            val upLimit = if (previous == TiltGuidance.TILT_DOWN) 28f else 35f
            if (pitch > upLimit) {
                return TiltGuidance.TILT_DOWN
            }

            return TiltGuidance.SUITABLE
        }
    }
}

/**
 * 단위 테스트 및 시뮬레이션용 가짜 자세 추적기
 */
class FakeDevicePoseTracker(
    initialPose: DevicePose = DevicePose(pitchDegrees = 15f, rollDegrees = 0f, headingDegrees = 0f)
) : DevicePoseTracker {

    private val _currentPose = MutableStateFlow(initialPose)
    override val currentPose: StateFlow<DevicePose> = _currentPose.asStateFlow()

    private val _tiltGuidance = MutableStateFlow(ProductionDevicePoseTracker.evaluateGuidance(initialPose.pitchDegrees, initialPose.rollDegrees))
    override val tiltGuidance: StateFlow<TiltGuidance> = _tiltGuidance.asStateFlow()

    var isTracking = false
        private set

    override fun startTracking() {
        isTracking = true
    }

    override fun stopTracking() {
        isTracking = false
    }

    fun setPose(pitch: Float, roll: Float, heading: Float = 0f) {
        val newPose = DevicePose(pitch, roll, heading)
        _currentPose.value = newPose
        _tiltGuidance.value = ProductionDevicePoseTracker.evaluateGuidance(pitch, roll, _tiltGuidance.value)
    }
}
