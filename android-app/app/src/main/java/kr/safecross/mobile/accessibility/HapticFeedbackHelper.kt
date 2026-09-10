package kr.safecross.mobile.accessibility

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kr.safecross.mobile.guidance.HapticFeedbackType

/**
 * 진동 세기 설정.
 */
enum class VibrationIntensity(val factor: Float, val label: String) {
    LOW(0.6f, "약하게"),
    MEDIUM(1.0f, "보통"),
    HIGH(1.2f, "강하게")
}

/**
 * 햅틱 진동 피드백 헬퍼 (SR-F-073, TRD 4.8 준수).
 *
 * 사용자 진동 설정(켜기/끄기, 세기)을 반영하며,
 * 진동이 꺼져 있어도 음성 및 화면 정보에 영향 없이 안전하게 동작합니다.
 */
class HapticFeedbackHelper(
    context: Context,
    var isEnabled: Boolean = true,
    var intensity: VibrationIntensity = VibrationIntensity.MEDIUM
) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * 햅틱 피드백을 실행합니다.
     */
    fun vibrate(type: HapticFeedbackType) {
        if (!isEnabled || vibrator == null || !vibrator.hasVibrator()) {
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 사용자 진동 세기(factor)를 반영하여 진폭 조절
                val adjustedAmplitudes = type.amplitudes.map { amp ->
                    if (amp == 0) 0 else ((amp * intensity.factor).toInt().coerceIn(1, 255))
                }.toIntArray()

                val effect = VibrationEffect.createWaveform(type.patternMs, adjustedAmplitudes, -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(type.patternMs, -1)
            }
        } catch (_: Exception) {
            // 진동 실패 시에도 앱 중단 없이 조용히 무시
        }
    }

    /**
     * 진행 중인 진동을 즉시 중지합니다.
     */
    fun cancel() {
        try {
            vibrator?.cancel()
        } catch (_: Exception) {}
    }
}
