package kr.safecross.mobile.perception

import android.util.Log

/**
 * OpenCV Android 네이티브 라이브러리 연동 브릿지 (ADR-030).
 *
 * S25 Ultra 단말기 환경에서는 OpenCV C++ 네이티브 바이너리(libopencv_java4.so)를 로드하여
 * 고속 이미지 처리(HSV 변환, 모폴로지, 윤곽선, 원형도 검출)를 수행하며,
 * JVM 단위 테스트 환경 등 네이티브 라이브러리가 없는 환경에서는 안전하게 fallback 플래그를 제공합니다.
 */
object OpenCvBridge {

    private const val TAG = "OpenCvBridge"

    @Volatile
    var isInitialized: Boolean = false
        private set

    /**
     * OpenCV 네이티브 라이브러리 초기화 시도
     */
    fun init(): Boolean {
        if (isInitialized) return true
        return try {
            val loaded = org.opencv.android.OpenCVLoader.initDebug()
            isInitialized = loaded
            try {
                if (loaded) {
                    Log.i(TAG, "OpenCV native library loaded successfully! (v${org.opencv.core.Core.VERSION})")
                } else {
                    Log.w(TAG, "OpenCV native library failed to load via OpenCVLoader.initDebug()")
                }
            } catch (_: Throwable) {}
            loaded
        } catch (t: Throwable) {
            // JVM 단위 테스트 환경 등 System.loadLibrary 실패 시 방어
            isInitialized = false
            try {
                Log.w(TAG, "OpenCV init failed (running in JVM test or unsupported env): ${t.message}")
            } catch (_: Throwable) {}
            false
        }
    }
}
