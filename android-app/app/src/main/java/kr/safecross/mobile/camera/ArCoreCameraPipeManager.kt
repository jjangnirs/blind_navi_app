package kr.safecross.mobile.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.Surface
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import kr.safecross.mobile.perception.PerceptionFlightRecorder
import kr.safecross.mobile.sensor.GeospatialHeadingMath
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ARCore 기반 횡단 보조 카메라 파이프라인 (ADR-0041).
 *
 * CameraX 대신 ARCore 세션으로 카메라를 열어
 * - 미리보기: GLSurfaceView에 카메라 영상 표시
 * - 분석 프레임: CPU 영상(약 640×480)을 RGBA 세로 버퍼로 바꿔 기존 신호 인식 파이프라인에 전달(최대 약 10 Hz)
 * - 공간 정보([ArFrameContext]): 6자유도 자세(흔들림 추적), VPS 위치·방향, 깊이·장면 라벨(기록용)
 * 를 함께 제공한다. ARCore를 쓸 수 없으면 MainActivity가 CameraX 구현([ProductionCameraPipeManager])을 쓴다.
 */
class ArCoreCameraPipeManager(
    private val context: Context,
    private val enableGeospatial: Boolean
) : CameraPipeManager {

    private var session: Session? = null
    private var surfaceView: GLSurfaceView? = null
    private val backgroundRenderer = ArBackgroundRenderer()
    private var onFrame: ((FrameRef) -> Unit)? = null
    @Volatile private var bound = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var geometryDirty = true
    private var lastAnalysisMs = 0L
    private var sensorRotation = 90
    private var depthEnabled = false
    private var semanticEnabled = false
    // ARCore 세션을 열지 못하면 CameraX로 대체
    private var fallback: ProductionCameraPipeManager? = null

    /** ARCore 세션 시작에 실패했을 때만 CameraX 대체 경로로 쓰인다. 정상 경로는 [createSurfaceView]. */
    override fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView?, onFrame: (FrameRef) -> Unit) {
        val cameraX = fallback ?: ProductionCameraPipeManager(context).also { fallback = it }
        PerceptionFlightRecorder.record("AR", "FALLBACK_CAMERAX")
        cameraX.bind(lifecycleOwner, previewView, onFrame)
    }

    /**
     * 미리보기 겸 분석용 GLSurfaceView를 만들고 ARCore 세션을 시작한다.
     * 세션을 열지 못하면 null을 돌려주며, 화면은 PreviewView + [bind](CameraX)로 대체한다.
     */
    fun createSurfaceView(viewContext: Context, onFrame: (FrameRef) -> Unit): GLSurfaceView? {
        this.onFrame = onFrame
        val view = GLSurfaceView(viewContext).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        try {
            val s = session ?: createSession().also { session = it }
            s.resume()
            bound = true
            PerceptionFlightRecorder.record(
                "AR",
                "SESSION_START geospatial=$enableGeospatial depth=$depthEnabled semantic=$semanticEnabled sensorRot=$sensorRotation"
            )
        } catch (e: Exception) {
            bound = false
            try {
                session?.close()
            } catch (_: Exception) {
            }
            session = null
            PerceptionFlightRecorder.record("AR", "SESSION_FAILED ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        surfaceView = view
        return view
    }

    override fun unbind() {
        bound = false
        try {
            surfaceView?.onPause()
        } catch (_: Exception) {
        }
        try {
            session?.pause()
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        surfaceView = null
        fallback?.unbind()
    }

    override fun isBound(): Boolean = bound || fallback?.isBound() == true

    private fun createSession(): Session {
        val s = Session(context)
        try {
            val geospatial = enableGeospatial && s.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
            depthEnabled = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            semanticEnabled = s.isSemanticModeSupported(Config.SemanticMode.ENABLED)
            s.configure(s.config.apply {
                geospatialMode = if (geospatial) Config.GeospatialMode.ENABLED else Config.GeospatialMode.DISABLED
                depthMode = if (depthEnabled) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                semanticMode = if (semanticEnabled) Config.SemanticMode.ENABLED else Config.SemanticMode.DISABLED
                focusMode = Config.FocusMode.AUTO
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            })
            sensorRotation = readSensorRotation(s.cameraConfig.cameraId)
        } catch (e: Exception) {
            s.close()
            throw e
        }
        return s
    }

    private fun readSensorRotation(cameraId: String): Int = try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    } catch (_: Exception) {
        90
    }

    private val renderer = object : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            backgroundRenderer.createOnGlThread()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            viewportWidth = width
            viewportHeight = height
            geometryDirty = true
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val s = session ?: return
            if (!bound) return
            try {
                if (geometryDirty && viewportWidth > 0) {
                    s.setDisplayGeometry(Surface.ROTATION_0, viewportWidth, viewportHeight)
                    geometryDirty = false
                }
                s.setCameraTextureName(backgroundRenderer.textureId)
                val frame = s.update()
                backgroundRenderer.draw(frame)

                val now = SystemClock.elapsedRealtime()
                if (now - lastAnalysisMs >= ANALYSIS_INTERVAL_MS) {
                    lastAnalysisMs = now
                    emitAnalysisFrame(s, frame)
                }
            } catch (e: Exception) {
                PerceptionFlightRecorder.record("AR", "FRAME_ERROR ${e.javaClass.simpleName}")
            }
        }
    }

    private fun emitAnalysisFrame(s: Session, frame: Frame) {
        val callback = onFrame ?: return
        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            return
        }
        val (rgba, width, height) = image.use { img ->
            val landscape = YuvToRgba.convert(img)
            ImageBufferRotator.rotateOrCopyRgbaBuffer(landscape, img.width, img.height, sensorRotation)
        }
        val arContext = buildArContext(s, frame, width, height)
        callback(
            FrameRef(
                width = width,
                height = height,
                rotationDegrees = 0,
                timestampNanos = System.nanoTime(),
                sensorTimestampNanos = frame.timestamp,
                rgbaBuffer = rgba,
                arContext = arContext
            )
        )
    }

    private fun buildArContext(s: Session, frame: Frame, width: Int, height: Int): ArFrameContext? {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return null
        val pose = camera.displayOrientedPose
        val rotation = ArFrameContext.rotationFromQuaternion(pose.qx(), pose.qy(), pose.qz(), pose.qw())
        val focal = camera.imageIntrinsics.focalLength[0]

        val geo = s.earth?.takeIf { it.trackingState == TrackingState.TRACKING }?.let { earth ->
            val gp = earth.cameraGeospatialPose
            val q = gp.eastUpSouthQuaternion
            val (heading, pitch) = GeospatialHeadingMath.cameraForward(q[0], q[1], q[2], q[3])
            ArFrameContext.GeoCamera(
                latitude = gp.latitude,
                longitude = gp.longitude,
                horizontalAccuracyM = gp.horizontalAccuracy,
                headingDeg = heading,
                pitchDeg = pitch,
                yawAccuracyDeg = gp.orientationYawAccuracy
            )
        }

        var depth: ShortArray? = null
        var depthW = 0
        var depthH = 0
        if (depthEnabled) {
            try {
                frame.acquireDepthImage16Bits().use { img ->
                    val plane = img.planes[0]
                    val buf = plane.buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    depthW = img.width
                    depthH = img.height
                    val arr = ShortArray(depthW * depthH)
                    for (y in 0 until depthH) for (x in 0 until depthW) {
                        arr[y * depthW + x] = buf.getShort(y * plane.rowStride + x * plane.pixelStride)
                    }
                    depth = arr
                }
            } catch (_: Exception) {
            }
        }

        var labels: ByteArray? = null
        var semW = 0
        var semH = 0
        if (semanticEnabled) {
            try {
                frame.acquireSemanticImage().use { img ->
                    val plane = img.planes[0]
                    semW = img.width
                    semH = img.height
                    val arr = ByteArray(semW * semH)
                    for (y in 0 until semH) for (x in 0 until semW) {
                        arr[y * semW + x] = plane.buffer.get(y * plane.rowStride + x * plane.pixelStride)
                    }
                    labels = arr
                }
            } catch (_: Exception) {
            }
        }

        return ArFrameContext(
            rotation = rotation,
            focalLengthPx = focal,
            portraitWidth = width,
            portraitHeight = height,
            geo = geo,
            depthMm = depth,
            depthWidth = depthW,
            depthHeight = depthH,
            semanticLabels = labels,
            semanticWidth = semW,
            semanticHeight = semH,
            sensorRotationDegrees = sensorRotation
        )
    }

    companion object {
        private const val ANALYSIS_INTERVAL_MS = 100L

        /** Google Play 서비스 AR이 설치되어 있고 기기가 지원하면 true (설치 요청은 하지 않음) */
        fun isAvailable(context: Context): Boolean = try {
            ArCoreApk.getInstance().checkAvailability(context) == ArCoreApk.Availability.SUPPORTED_INSTALLED
        } catch (_: Exception) {
            false
        }
    }
}
