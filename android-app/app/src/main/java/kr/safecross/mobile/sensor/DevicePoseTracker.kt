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

                    // orientationValues[1]: Pitch, orientationValues[2]: Roll
                    val pitch = Math.toDegrees(orientationValues[1].toDouble()).toFloat()
                    val roll = Math.toDegrees(orientationValues[2].toDouble()).toFloat()

                    val pose = DevicePose(pitchDegrees = pitch, rollDegrees = roll, headingDegrees = heading)
                    _currentPose.value = pose
                    _tiltGuidance.value = evaluateGuidance(pitch, roll)
                } catch (_: Exception) {}
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]

                // 세로 모드(Portrait Camera) 기준 Pitch & Roll 계산 (단위: 도)
                val pitch = atan2(-az.toDouble(), sqrt((ax * ax + ay * ay).toDouble())).toFloat() * (180f / Math.PI.toFloat())
                val roll = atan2(ax.toDouble(), ay.toDouble()).toFloat() * (180f / Math.PI.toFloat())

                val currentHeading = _currentPose.value.headingDegrees
                val pose = DevicePose(pitchDegrees = pitch, rollDegrees = roll, headingDegrees = currentHeading)
                _currentPose.value = pose
                _tiltGuidance.value = evaluateGuidance(pitch, roll)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        fun evaluateGuidance(pitch: Float, roll: Float): TiltGuidance {
            // 좌우 기울기(Roll)가 28도 이상 틀어지면 수평 정렬 유도
            if (kotlin.math.abs(roll) > 28f) {
                return TiltGuidance.LEVEL_PHONE
            }
            // 상하 각도(Pitch): 서서 전방 횡단보도와 신호등을 바라볼 때 약 -25도(상단 신호등) ~ +45도(전방 횡단보도)가 이상적
            // 하늘을 너무 향하는 경우 (pitch < -25도)
            if (pitch < -25f) {
                return TiltGuidance.TILT_DOWN
            }
            // 바닥을 너무 향하는 경우 (pitch > 45도)
            if (pitch > 45f) {
                return TiltGuidance.TILT_UP
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
        _tiltGuidance.value = ProductionDevicePoseTracker.evaluateGuidance(pitch, roll)
    }
}
