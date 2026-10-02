package kr.safecross.vpsprobe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.VpsAvailability
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ARCore Geospatial(VPS) 현장 정확도 측정 화면.
 *
 * - VPS 지원 여부, VPS 위치·방향 정확도, 휴대폰 GPS·나침반과의 차이를 실시간 표시
 * - 1초마다 CSV 기록 (Android/data/kr.safecross.vpsprobe/files/logs/)
 * - "지점 표시"로 현재 서 있는 곳(예: 횡단보도 이름 대신 번호)을 기록에 태그
 */
class MainActivity : Activity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var recordButton: Button

    private val backgroundRenderer = BackgroundRenderer()
    private var session: Session? = null
    private var installRequested = false
    private var sessionError: String? = null
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var displayGeometryDirty = true

    // 측정값 (GL 스레드에서 갱신, UI 스레드에서 읽음)
    @Volatile private var snapshot = Snapshot()
    @Volatile private var lastLocation: Location? = null
    @Volatile private var compassHeading: Double? = null
    @Volatile private var vpsAvailability: String = "미확인"

    private val recentHAcc = ArrayDeque<Pair<Long, Double>>()
    private val recentYawAcc = ArrayDeque<Pair<Long, Double>>()
    private val recentTracking = ArrayDeque<Pair<Long, Boolean>>()

    private var recordFile: File? = null
    private var lastRecordMs = 0L
    private var lastUiMs = 0L
    private var markCounter = 0
    @Volatile private var pendingMark: String? = null

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    data class Snapshot(
        val earthState: String = "-",
        val tracking: String = "-",
        val lat: Double? = null,
        val lon: Double? = null,
        val hAcc: Double? = null,
        val alt: Double? = null,
        val vAcc: Double? = null,
        val heading: Double? = null,
        val yawAcc: Double? = null
    )

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        if (!hasPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        if (!ensureSession()) {
            showError()
            return
        }
        try {
            session?.resume()
        } catch (e: Exception) {
            sessionError = "카메라를 시작할 수 없습니다: ${e.javaClass.simpleName} ${e.message ?: ""}"
            showError()
            return
        }
        surfaceView.onResume()
        startLocationAndCompass()
    }

    override fun onPause() {
        super.onPause()
        if (::surfaceView.isInitialized) surfaceView.onPause()
        session?.pause()
        stopLocationAndCompass()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!hasPermissions()) {
            statusText.text = "카메라와 위치 권한이 필요합니다. 앱 설정에서 허용해 주세요."
        }
    }

    /** 세션이 없으면 GL 루프가 화면을 갱신하지 않으므로 오류를 직접 표시 */
    private fun showError() {
        sessionError?.let { statusText.text = "⚠️ $it" }
    }

    private fun hasPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** ARCore 설치 확인 및 Geospatial 세션 생성 */
    private fun ensureSession(): Boolean {
        if (session != null) return true
        if (!BuildConfig.HAS_ARCORE_API_KEY) {
            sessionError = "ARCore API 키가 없습니다.\n.env 에 ARCORE_API_KEY=... 를 넣고 다시 빌드하세요.\n(Google Cloud 콘솔에서 'ARCore API' 사용 설정 후 키 발급)"
        }
        return try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    false
                }
                else -> {
                    val newSession = Session(this)
                    try {
                        if (!newSession.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                            sessionError = "이 기기는 ARCore Geospatial을 지원하지 않습니다."
                        }
                        newSession.configure(newSession.config.apply {
                            geospatialMode = Config.GeospatialMode.ENABLED
                            focusMode = Config.FocusMode.AUTO
                            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        })
                    } catch (e: Exception) {
                        newSession.close()
                        throw e
                    }
                    session = newSession
                    true
                }
            }
        } catch (e: Exception) {
            sessionError = "ARCore 세션 생성 실패: ${e.javaClass.simpleName} ${e.message ?: ""}"
            false
        }
    }

    // ---------------------------------------------------------------- GPS & compass

    private val locationListener = LocationListener { location -> lastLocation = location }

    private val compassListener = object : SensorEventListener {
        private val rotation = FloatArray(9)
        private val remapped = FloatArray(9)
        private val orientation = FloatArray(3)
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            // 휴대폰을 세워 카메라가 정면을 볼 때의 카메라 방향 방위각
            SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            SensorManager.getOrientation(remapped, orientation)
            compassHeading = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    @SuppressLint("MissingPermission")
    private fun startLocationAndCompass() {
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        try {
            locationManager.requestLocationUpdates(provider, 1000L, 0f, locationListener, mainLooper)
        } catch (_: Exception) {
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(compassListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopLocationAndCompass() {
        if (!::locationManager.isInitialized) return
        locationManager.removeUpdates(locationListener)
        sensorManager.unregisterListener(compassListener)
    }

    // ---------------------------------------------------------------- buttons

    private fun checkVpsAvailability() {
        val s = session ?: return
        val loc = lastLocation
        if (loc == null) {
            vpsAvailability = "GPS 위치 대기 중"
            return
        }
        vpsAvailability = "확인 중…"
        s.checkVpsAvailabilityAsync(loc.latitude, loc.longitude) { availability ->
            vpsAvailability = when (availability) {
                VpsAvailability.AVAILABLE -> "지원됨"
                VpsAvailability.UNAVAILABLE -> "지원 안 됨"
                VpsAvailability.ERROR_NOT_AUTHORIZED -> "오류: 인증 실패(API 키 확인)"
                VpsAvailability.ERROR_NETWORK_CONNECTION -> "오류: 네트워크"
                VpsAvailability.ERROR_RESOURCE_EXHAUSTED -> "오류: 요청 한도 초과"
                else -> "오류: $availability"
            }
        }
    }

    private fun toggleRecording() {
        if (recordFile == null) {
            val dir = getExternalFilesDir("logs") ?: filesDir
            val name = "vps_probe_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
            recordFile = File(dir, name).apply { writeText(CSV_HEADER + "\n") }
            recordButton.text = "기록 중지"
        } else {
            recordFile = null
            recordButton.text = "기록 시작"
        }
    }

    private fun markPoint() {
        markCounter += 1
        pendingMark = "지점${markCounter}"
    }

    // ---------------------------------------------------------------- GL renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        displayGeometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (displayGeometryDirty && viewportWidth > 0) {
            @Suppress("DEPRECATION")
            s.setDisplayGeometry(windowManager.defaultDisplay.rotation, viewportWidth, viewportHeight)
            displayGeometryDirty = false
        }
        try {
            s.setCameraTextureName(backgroundRenderer.textureId)
            val frame = s.update()
            backgroundRenderer.draw(frame)
            readEarth(s.earth)
        } catch (e: Exception) {
            sessionError = "프레임 처리 오류: ${e.javaClass.simpleName}"
        }

        val now = SystemClock.elapsedRealtime()
        if (recordFile != null && now - lastRecordMs >= 1000L) {
            lastRecordMs = now
            appendRecord()
        }
        if (now - lastUiMs >= 250L) {
            lastUiMs = now
            val text = buildStatusText(now)
            runOnUiThread { statusText.text = text }
        }
    }

    private fun readEarth(earth: Earth?) {
        if (earth == null) {
            snapshot = Snapshot(earthState = "Geospatial 비활성")
            return
        }
        val tracking = earth.trackingState
        val now = SystemClock.elapsedRealtime()
        recentTracking.addLast(now to (tracking == TrackingState.TRACKING))
        if (tracking != TrackingState.TRACKING) {
            snapshot = Snapshot(earthState = earth.earthState.name, tracking = tracking.name)
            trim(now)
            return
        }
        val pose = earth.cameraGeospatialPose
        val q = pose.eastUpSouthQuaternion
        val heading = ProbeMath.cameraHeadingFromEusQuaternion(q[0], q[1], q[2], q[3])
        snapshot = Snapshot(
            earthState = earth.earthState.name,
            tracking = tracking.name,
            lat = pose.latitude,
            lon = pose.longitude,
            hAcc = pose.horizontalAccuracy,
            alt = pose.altitude,
            vAcc = pose.verticalAccuracy,
            heading = heading,
            yawAcc = pose.orientationYawAccuracy
        )
        recentHAcc.addLast(now to pose.horizontalAccuracy)
        recentYawAcc.addLast(now to pose.orientationYawAccuracy)
        trim(now)
    }

    private fun trim(now: Long) {
        val cutoff = now - STATS_WINDOW_MS
        while (recentHAcc.isNotEmpty() && recentHAcc.first().first < cutoff) recentHAcc.removeFirst()
        while (recentYawAcc.isNotEmpty() && recentYawAcc.first().first < cutoff) recentYawAcc.removeFirst()
        while (recentTracking.isNotEmpty() && recentTracking.first().first < cutoff) recentTracking.removeFirst()
    }

    private fun buildStatusText(now: Long): String {
        sessionError?.let { return "⚠️ $it" }
        val snap = snapshot
        val loc = lastLocation
        val compass = compassHeading
        val sb = StringBuilder()
        sb.append("VPS 지원(현재 위치): $vpsAvailability\n")
        sb.append("Earth: ${snap.earthState} / ${snap.tracking}\n")
        if (snap.lat != null && snap.lon != null) {
            sb.append("VPS 위치 오차: ${fmt(snap.hAcc)} m   고도 오차: ${fmt(snap.vAcc)} m\n")
            sb.append("VPS 방향: ${fmt(snap.heading)}°   방향 오차: ${fmt(snap.yawAcc)}°\n")
        } else {
            sb.append("VPS 위치: 추적 전 (주변 건물을 향해 천천히 둘러보세요)\n")
        }
        sb.append("GPS 오차: ${fmt(loc?.accuracy?.toDouble())} m   나침반: ${fmt(compass)}°\n")
        if (snap.lat != null && snap.lon != null && loc != null) {
            val dist = ProbeMath.distanceMeters(snap.lat, snap.lon, loc.latitude, loc.longitude)
            sb.append("VPS↔GPS 위치 차이: ${fmt(dist)} m\n")
        }
        if (snap.heading != null && compass != null) {
            sb.append("VPS↔나침반 방향 차이: ${fmt(ProbeMath.headingDiff(snap.heading, compass))}°\n")
        }

        val hStats = ProbeMath.stats(recentHAcc.map { it.second })
        val yStats = ProbeMath.stats(recentYawAcc.map { it.second })
        val trackingRatio = if (recentTracking.isEmpty()) 0.0 else recentTracking.count { it.second } * 100.0 / recentTracking.size
        sb.append("\n최근 30초: 추적률 ${fmt(trackingRatio)}%")
        if (hStats != null && yStats != null) {
            sb.append("  위치오차 중앙 ${fmt(hStats.median)} / 90% ${fmt(hStats.p90)} m")
            sb.append("  방향오차 중앙 ${fmt(yStats.median)} / 90% ${fmt(yStats.p90)}°\n")
            sb.append("판정: ${verdict(hStats.p90, yStats.p90, trackingRatio)}\n")
        } else {
            sb.append("\n")
        }
        recordFile?.let { sb.append("● 기록 중: ${it.name}\n") }
        pendingMark?.let { sb.append("표시 대기: $it\n") }
        sb.append("※ 측정 중 카메라 영상 특징이 Google VPS 서버로 전송됩니다.")
        return sb.toString()
    }

    private fun verdict(hP90: Double, yawP90: Double, trackingRatio: Double): String = when {
        trackingRatio < 50 -> "부적합 (추적 불안정)"
        hP90 <= 5.0 && yawP90 <= 5.0 -> "사용 가능 (위치 ≤5m, 방향 ≤5°)"
        hP90 <= 10.0 && yawP90 <= 15.0 -> "제한적 (위치 ≤10m, 방향 ≤15°)"
        else -> "부적합 (정확도 부족)"
    }

    private fun appendRecord() {
        val file = recordFile ?: return
        val snap = snapshot
        val loc = lastLocation
        val compass = compassHeading
        val mark = pendingMark.also { pendingMark = null } ?: ""
        val posDiff = if (snap.lat != null && snap.lon != null && loc != null) {
            ProbeMath.distanceMeters(snap.lat, snap.lon, loc.latitude, loc.longitude)
        } else null
        val headingDiff = if (snap.heading != null && compass != null) ProbeMath.headingDiff(snap.heading, compass) else null
        val row = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date()),
            mark, snap.earthState, snap.tracking,
            snap.lat?.toString() ?: "", snap.lon?.toString() ?: "", fmt(snap.hAcc), fmt(snap.alt), fmt(snap.vAcc),
            fmt(snap.heading), fmt(snap.yawAcc),
            loc?.latitude?.toString() ?: "", loc?.longitude?.toString() ?: "", fmt(loc?.accuracy?.toDouble()),
            fmt(loc?.speed?.toDouble()), fmt(compass), fmt(posDiff), fmt(headingDiff), vpsAvailability
        ).joinToString(",")
        try {
            file.appendText(row + "\n")
        } catch (_: Exception) {
        }
    }

    private fun fmt(v: Double?): String = v?.let { String.format(Locale.US, "%.1f", it) } ?: ""

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): FrameLayout {
        val root = FrameLayout(this)
        surfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@MainActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            textSize = 15f
            setPadding(32, 150, 32, 24) // 상태 표시줄(시계·배터리) 아래부터 표시
            text = "준비 중…"
        }
        root.addView(statusText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 48)
            setBackgroundColor(0xCC000000.toInt())
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 17f
            setOnClickListener { onClick() }
        }
        buttons.addView(button("VPS 확인") { checkVpsAvailability() }, LinearLayout.LayoutParams(0, 160, 1f))
        recordButton = button("기록 시작") { toggleRecording() }
        buttons.addView(recordButton, LinearLayout.LayoutParams(0, 160, 1f))
        buttons.addView(button("지점 표시") { markPoint() }, LinearLayout.LayoutParams(0, 160, 1f))
        root.addView(buttons, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        return root
    }

    private companion object {
        const val STATS_WINDOW_MS = 30_000L
        const val CSV_HEADER = "time,mark,earth_state,tracking,vps_lat,vps_lon,vps_h_acc_m,vps_alt_m,vps_v_acc_m," +
                "vps_heading_deg,vps_yaw_acc_deg,gps_lat,gps_lon,gps_acc_m,gps_speed_mps,compass_heading_deg," +
                "vps_gps_pos_diff_m,vps_compass_heading_diff_deg,vps_availability"
    }
}
