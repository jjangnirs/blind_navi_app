package kr.safecross.mobile.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * 후면 카메라 화각 조회 (높이 추정용, ADR-0040).
 */
object CameraFov {
    /** 일반적인 24 mm 환산 광각 메인 카메라의 세로 화면 기준 세로 화각 */
    const val DEFAULT_PORTRAIT_VERTICAL_FOV_DEG = 74f

    /**
     * 세로로 든 휴대폰에서 화면 세로 방향 화각.
     * 센서 긴 변(가로 방향 센서)이 세로 화면의 세로가 되므로 센서 너비와 초점거리로 계산한다.
     */
    fun portraitVerticalFovDegrees(context: Context): Float = try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val chars = backId?.let { manager.getCameraCharacteristics(it) }
        val size = chars?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        if (size != null && focal != null && focal > 0f) {
            val longSide = maxOf(size.width, size.height)
            Math.toDegrees(2.0 * kotlin.math.atan(longSide / (2.0 * focal))).toFloat().coerceIn(40f, 110f)
        } else {
            DEFAULT_PORTRAIT_VERTICAL_FOV_DEG
        }
    } catch (_: Exception) {
        DEFAULT_PORTRAIT_VERTICAL_FOV_DEG
    }
}
