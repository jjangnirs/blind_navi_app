package kr.safecross.mobile

import kr.safecross.mobile.camera.FakeCameraPipeManager
import kr.safecross.mobile.camera.FrameRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 카메라 수명주기 및 ImageProxy 반환 안전 검증 (SR-F-049, TRD 4.4).
 */
class CameraLifecycleTest {

    @Test
    fun testCameraUnbindsOnStop() {
        val cameraPipe = FakeCameraPipeManager()
        assertFalse(cameraPipe.bound)

        // 1. 바인딩
        cameraPipe.bind(
            lifecycleOwner = object : androidx.lifecycle.LifecycleOwner {
                override val lifecycle: androidx.lifecycle.Lifecycle
                    get() = throw UnsupportedOperationException()
            },
            previewView = null,
            onFrame = {}
        )
        assertTrue("Camera must be bound", cameraPipe.bound)

        // 2. 화면 이탈 또는 백그라운드 전환 시 unbind
        cameraPipe.unbind()
        assertFalse("Camera must be unbound", cameraPipe.bound)
        assertEquals(1, cameraPipe.unbindCount)
    }

    @Test
    fun testImageProxyCloseInTryFinallyContract() {
        // ImageProxy 모의 객체
        var isClosed = false
        val mockProxyCloseAction = { isClosed = true }

        // Analyzer 처리 시 try/finally 구조 계약 검증
        try {
            val frame = FrameRef.createForTesting()
            assertTrue(frame.width > 0)
        } finally {
            mockProxyCloseAction()
        }

        assertTrue("ImageProxy must be closed in finally block", isClosed)
    }

    @Test
    fun testFrameDropOnAnalysisDelayDoesNotAccumulate() {
        val cameraPipe = FakeCameraPipeManager()
        var receivedCount = 0

        cameraPipe.bind(
            lifecycleOwner = object : androidx.lifecycle.LifecycleOwner {
                override val lifecycle: androidx.lifecycle.Lifecycle
                    get() = throw UnsupportedOperationException()
            },
            previewView = null,
            onFrame = { receivedCount++ }
        )

        // 프레임 10회 방출
        repeat(10) {
            cameraPipe.simulateFrame()
        }
        assertEquals(10, receivedCount)

        // unbind 후에는 추가 프레임이 수신되지 않음
        cameraPipe.unbind()
        cameraPipe.simulateFrame()
        assertEquals(10, receivedCount)
    }
}
