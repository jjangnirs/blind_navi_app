package kr.safecross.mobile.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ARCore Geospatial(VPS) 방향 샘플.
 */
data class VpsHeadingSample(
    val headingDegrees: Double,
    val yawAccuracyDegrees: Double,
    val isTopAxis: Boolean,
    val elapsedRealtimeMs: Long
)

/**
 * 화면 표시 없이 ARCore Geospatial을 돌려 VPS 방향을 제공하는 공급자 (ADR-0037).
 *
 * - 전용 스레드에서 1x1 오프스크린 EGL 컨텍스트를 만들어 ARCore 카메라 텍스처로 사용한다.
 * - 약 10Hz로 `Session.update()`를 호출하고 Earth가 TRACKING이면 방향 샘플을 내보낸다.
 * - 카메라를 점유하므로 횡단 보조(CameraX) 화면으로 가기 전에 반드시 [pauseBlocking]으로 놓아준다.
 * - Google Play 서비스 AR 미설치, 카메라 권한 없음, 인증 실패 등에서는 조용히 비활성(status로 사유 노출).
 * - 주의: VPS 위치 확인 시 카메라 영상 특징이 Google 서버로 전송된다(설정에서 끌 수 있음).
 */
class GeospatialHeadingProvider(private val context: Context) {

    private val _sample = MutableStateFlow<VpsHeadingSample?>(null)
    val sample: StateFlow<VpsHeadingSample?> = _sample.asStateFlow()

    private val _status = MutableStateFlow("VPS 대기")
    val status: StateFlow<String> = _status.asStateFlow()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var session: Session? = null
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var cameraTexture = 0
    @Volatile private var running = false

    /** 시작 또는 재개. 이미 실행 중이면 아무것도 하지 않는다. */
    fun start() {
        if (running) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _status.value = "VPS 꺼짐: 카메라 권한 없음"
            return
        }
        val availability = try {
            ArCoreApk.getInstance().checkAvailability(context)
        } catch (_: Exception) {
            null
        }
        if (availability != ArCoreApk.Availability.SUPPORTED_INSTALLED) {
            _status.value = "VPS 꺼짐: Google Play 서비스 AR 사용 불가($availability)"
            return
        }

        val t = thread ?: HandlerThread("vps-heading").also { it.start(); thread = it }
        val h = handler ?: Handler(t.looper).also { handler = it }
        running = true
        h.post {
            try {
                if (session == null) createSessionOnThread()
                session?.resume()
                _status.value = "VPS 위치 확인 중"
                h.post(updateLoop)
            } catch (e: Exception) {
                running = false
                _status.value = "VPS 꺼짐: ${e.javaClass.simpleName}"
            }
        }
    }

    /** 카메라를 놓아주고 멈출 때까지 최대 1.5초 대기 (CameraX가 곧바로 카메라를 써야 할 때 사용) */
    fun pauseBlocking() {
        if (!running) return
        running = false
        val h = handler ?: return
        val latch = CountDownLatch(1)
        h.removeCallbacks(updateLoop)
        h.post {
            try {
                session?.pause()
            } catch (_: Exception) {
            }
            _sample.value = null
            _status.value = "VPS 일시정지"
            latch.countDown()
        }
        latch.await(1500, TimeUnit.MILLISECONDS)
    }

    /** 완전 종료 (세션·EGL·스레드 해제) */
    fun release() {
        pauseBlocking()
        val h = handler ?: return
        val t = thread
        h.post {
            try {
                session?.close()
            } catch (_: Exception) {
            }
            session = null
            destroyEgl()
            t?.quitSafely()
        }
        handler = null
        thread = null
    }

    private val updateLoop = object : Runnable {
        override fun run() {
            if (!running) return
            val s = session ?: return
            try {
                s.update()
                val earth = s.earth
                if (earth != null && earth.trackingState == TrackingState.TRACKING) {
                    val pose = earth.cameraGeospatialPose
                    val q = pose.eastUpSouthQuaternion
                    val nav = GeospatialHeadingMath.navigationHeading(q[0], q[1], q[2], q[3])
                    _sample.value = VpsHeadingSample(
                        headingDegrees = nav.headingDegrees,
                        yawAccuracyDegrees = pose.orientationYawAccuracy,
                        isTopAxis = nav.isTopAxis,
                        elapsedRealtimeMs = SystemClock.elapsedRealtime()
                    )
                    _status.value = "VPS 방향 오차 ${"%.0f".format(pose.orientationYawAccuracy)}°"
                } else if (earth != null) {
                    _status.value = "VPS ${earth.earthState.name.lowercase()} / ${earth.trackingState.name.lowercase()}"
                }
            } catch (e: Exception) {
                _status.value = "VPS 오류: ${e.javaClass.simpleName}"
            }
            handler?.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    private fun createSessionOnThread() {
        initEgl()
        val newSession = Session(context)
        try {
            if (!newSession.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                throw IllegalStateException("GeospatialNotSupported")
            }
            newSession.configure(newSession.config.apply {
                geospatialMode = Config.GeospatialMode.ENABLED
                focusMode = Config.FocusMode.AUTO
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            })
            // 세로 화면 기준 자세(윗변 = +Y)로 GeospatialPose를 받기 위해 표시 방향을 세로로 고정
            newSession.setDisplayGeometry(Surface.ROTATION_0, 1080, 2340)
            newSession.setCameraTextureName(cameraTexture)
        } catch (e: Exception) {
            newSession.close()
            throw e
        }
        session = newSession
    }

    private fun initEgl() {
        if (eglContext != EGL14.EGL_NO_CONTEXT) return
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, count, 0)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTexture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }

    private fun destroyEgl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 100L
    }
}
