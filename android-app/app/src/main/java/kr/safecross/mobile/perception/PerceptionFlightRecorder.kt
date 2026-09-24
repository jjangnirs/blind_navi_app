package kr.safecross.mobile.perception

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 실환경 보행신호 인지 및 의사결정 과정을 단말기 로컬 파일 및 Logcat에 실시간 순환 기록하는 비행 기록기 (Flight Recorder).
 *
 * [보안 및 개인정보 보호 불변식]
 * 1. 사용자의 GPS 위경도 좌표 및 카메라 원본 픽셀은 절대 기록하지 않는다.
 * 2. 신호등 인지 중간값(색상 판정, 블롭 크기, 바운딩 박스, Track ID, 자세 각도, 판정 코드)만 비식별화하여 기록한다.
 * 3. 단말기 내부 파일 크기는 최대 2MB로 제한되며 초과 시 롤링(Rolling)된다.
 */
object PerceptionFlightRecorder {

    private const val TAG = "SafeCrossFlight"
    private const val LOG_FILE_NAME = "perception_flight.log"
    private const val MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024L // 2 MB

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.KOREA)

    private val _latestSummary = MutableStateFlow<String>("")
    val latestSummary: StateFlow<String> = _latestSummary.asStateFlow()

    @Volatile
    private var logDir: File? = null

    fun init(context: Context) {
        if (logDir == null) {
            val externalDir = context.getExternalFilesDir("logs")
            logDir = externalDir ?: File(context.filesDir, "logs")
            logDir?.mkdirs()
        }
    }

    /**
     * 화면 상단/하단 실시간 디버그 HUD용 요약 정보 갱신
     */
    fun updateSummary(summary: String) {
        _latestSummary.value = summary
    }

    /**
     * 인지/의사결정 상세 진단 로그 기록 (Logcat + 단말기 텍스트 파일 동시 기록)
     */
    fun record(category: String, message: String) {
        val now = Date()
        val ts = synchronized(timeFormat) { timeFormat.format(now) }
        val logLine = "[$ts] [$category] $message\n"

        // 1. Android Logcat 출력 (PC adb logcat 실시간 모니터링용)
        try {
            Log.d(TAG, "[$category] $message")
        } catch (_: Exception) {
            // JVM 단위 테스트 환경 등 android.util.Log 미지원 환경 방어
        }

        // 2. 단말기 로컬 파일 비동기 추가 기록 (야외 현장 테스트용)
        val dir = logDir ?: return
        scope.launch {
            try {
                val file = File(dir, LOG_FILE_NAME)
                if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) {
                    val backup = File(dir, "$LOG_FILE_NAME.1")
                    if (backup.exists()) backup.delete()
                    file.renameTo(backup)
                }

                FileOutputStream(file, true).use { fos ->
                    OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                        writer.write(logLine)
                        writer.flush()
                    }
                }
            } catch (_: Exception) {
                // I/O 오류 시 메인 스레드에 영향 없도록 무시
            }
        }
    }

    /**
     * 현재 기록된 로그 파일 객체 반환
     */
    fun getLogFile(context: Context): File {
        init(context)
        return File(logDir ?: context.filesDir, LOG_FILE_NAME)
    }

    /**
     * 최근 N개 라인의 로그 텍스트를 읽어옵니다 (클립보드 복사 또는 화면 표시용).
     */
    fun readRecentLogs(context: Context, maxLines: Int = 120): String {
        return try {
            val file = getLogFile(context)
            if (!file.exists()) return "기록된 진단 로그가 없습니다."
            val lines = file.readLines(Charsets.UTF_8)
            val takeCount = lines.size.coerceAtMost(maxLines)
            lines.takeLast(takeCount).joinToString("\n")
        } catch (e: Exception) {
            "로그 읽기 실패: ${e.message}"
        }
    }

    /**
     * 로그 파일 초기화
     */
    fun clearLogs(context: Context) {
        init(context)
        try {
            val dir = logDir ?: return
            File(dir, LOG_FILE_NAME).delete()
            File(dir, "$LOG_FILE_NAME.1").delete()
            _latestSummary.value = ""
        } catch (_: Exception) {
        }
    }
}
