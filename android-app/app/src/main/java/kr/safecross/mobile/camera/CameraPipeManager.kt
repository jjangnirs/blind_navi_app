package kr.safecross.mobile.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX 프레임 분석 파이프라인 수명주기 관리 인터페이스 (SR-F-040, SR-F-049, TRD 4.4).
 */
interface CameraPipeManager {
    /**
     * Preview(선택)와 ImageAnalysis를 특정 LifecycleOwner에 바인딩한다.
     */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView? = null,
        onFrame: (FrameRef) -> Unit
    )

    /**
     * 카메라 세션을 안전하게 해제하고 프레임 분석을 중지한다.
     */
    fun unbind()

    /**
     * 현재 바인딩 상태 여부를 반환한다.
     */
    fun isBound(): Boolean
}

/**
 * CameraX 기반 실제 하드웨어 카메라 파이프라인 구현체
 */
class ProductionCameraPipeManager(
    private val context: Context,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) : CameraPipeManager {

    private var cameraProvider: ProcessCameraProvider? = null
    private var bound: Boolean = false

    override fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        onFrame: (FrameRef) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            try {
                // 이전 바인딩 해제
                provider.unbindAll()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // 1. 선택적 Preview (저시력자 화면 보조용)
                val preview = previewView?.let { view ->
                    Preview.Builder()
                        .build()
                        .also { it.setSurfaceProvider(view.surfaceProvider) }
                }

                // 2. ImageAnalysis (RGBA_8888 직접 출력 및 STRATEGY_KEEP_ONLY_LATEST)
                @Suppress("DEPRECATION")
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetResolution(Size(640, 480))
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy: ImageProxy ->
                    try {
                        val plane = imageProxy.planes.firstOrNull()
                        val directBuffer = plane?.buffer
                        val (finalBuffer, finalWidth, finalHeight) = if (directBuffer != null && directBuffer.remaining() > 0) {
                            ImageBufferRotator.rotateOrCopyRgbaBuffer(
                                directBuffer,
                                imageProxy.width,
                                imageProxy.height,
                                imageProxy.imageInfo.rotationDegrees
                            )
                        } else {
                            Triple(null, imageProxy.width, imageProxy.height)
                        }

                        val frameRef = FrameRef(
                            width = finalWidth,
                            height = finalHeight,
                            rotationDegrees = 0, // 화면 표시 기준 정립(Upright)으로 정규화됨
                            timestampNanos = System.nanoTime(),
                            sensorTimestampNanos = imageProxy.imageInfo.timestamp,
                            rgbaBuffer = finalBuffer
                        )
                        onFrame(frameRef)
                    } finally {
                        // 필수: ImageProxy를 반드시 finally에서 close하여 프레임 버퍼 반환
                        imageProxy.close()
                    }
                }

                // 3. 수명주기 바인딩
                if (preview != null) {
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                } else {
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
                }

                bound = true
            } catch (e: Exception) {
                bound = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun unbind() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        } finally {
            bound = false
        }
    }

    override fun isBound(): Boolean = bound

    fun shutdown() {
        unbind()
        executor.shutdown()
    }
}

/**
 * 단위 테스트 및 에뮬레이터 환경을 위한 모의(Fake) 카메라 파이프라인
 */
class FakeCameraPipeManager : CameraPipeManager {
    var bound: Boolean = false
        private set
    var unbindCount: Int = 0
        private set
    var onFrameCallback: ((FrameRef) -> Unit)? = null
        private set

    override fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        onFrame: (FrameRef) -> Unit
    ) {
        bound = true
        onFrameCallback = onFrame
    }

    override fun unbind() {
        bound = false
        unbindCount++
        onFrameCallback = null
    }

    override fun isBound(): Boolean = bound

    fun simulateFrame(frame: FrameRef = FrameRef.createForTesting()) {
        if (bound) {
            onFrameCallback?.invoke(frame)
        }
    }
}
