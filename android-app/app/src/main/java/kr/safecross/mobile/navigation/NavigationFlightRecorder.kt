package kr.safecross.mobile.navigation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.safecross.mobile.domain.model.PedestrianRoute
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 실시간 보행 내비게이션 경로 진행 상태, GPS 수신 품질, 이탈 오차 및 음성 안내 과정을
 * 단말기 로컬 파일 및 Logcat에 실시간 순환 기록하는 경로 분석 비행 기록기 (Navigation Flight Recorder).
 *
 * [수집 및 분석 데이터]
 * 1. [ROUTE_START]: 출발지, 도착지, 총 거리, 스텝 수, 횡단보도 수
 * 2. [GPS]: 위경도(4자리 정규화), 수신 정확도(acc), 속도(spd), 공급자(provider), 신호 강도
 * 3. [PROGRESS]: 스텝 번호, 누적 진행거리, 잔여 거리, 분기점 거리, 크로스트랙 오차(CTE), 이탈 여부/카운트
 * 4. [POSE]: 기기 헤딩(방위각), 목표 방위각, 방위각 차이, 정대(Aligned) 여부
 * 5. [APPROACH]: 분기점 30m / 15m 사전 접근 알림
 * 6. [STEP_CHANGE]: 분기점 도달 및 다음 단계 전환
 * 7. [REROUTE_TRIGGER]: 재탐색 유발 사유(이탈 거리/카운트 또는 출발점 이격)
 * 8. [REROUTE_RESULT]: 재탐색 성공/실패 여부 및 신규 경로 정보
 * 9. [GUIDANCE]: 음성/햅틱 안내 발화 텍스트, 카테고리, 우선순위
 * 10. [FINISH]: 목적지 도착, 총 보행 거리, 소요 시간
 */
object NavigationFlightRecorder {

    private const val TAG = "SafeCrossNavFlight"
    private const val LOG_FILE_NAME = "navigation_flight.log"
    private const val MAX_FILE_SIZE_BYTES = 3 * 1024 * 1024L // 3 MB (약 2~3만 라인)

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

    fun updateSummary(summary: String) {
        _latestSummary.value = summary
    }

    /**
     * 경로 분석 로그 라인 기록 (Logcat + 단말기 텍스트 파일 동시 기록)
     */
    fun record(category: String, message: String) {
        val now = Date()
        val ts = synchronized(timeFormat) { timeFormat.format(now) }
        val logLine = "[$ts] [$category] $message\n"

        // 1. Logcat 출력
        try {
            Log.d(TAG, "[$category] $message")
        } catch (_: Exception) {}

        // 2. 단말기 로컬 파일 비동기 추가 기록
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
            } catch (_: Exception) {}
        }
    }

    fun recordRouteStart(route: PedestrianRoute, originName: String, destinationName: String) {
        val cwCount = route.maneuvers.count { it.facilityType == "횡단보도" || it.turnType in 211..217 }
        val msg = "출발=\"$originName\" -> 목적=\"$destinationName\" | 총거리=${route.totalDistanceMeters}m | 소요예상=${route.totalDurationSeconds / 60}분 | 단계수=${route.maneuvers.size} | 횡단보도수=$cwCount"
        record("ROUTE_START", msg)
        updateSummary("안내 시작: $destinationName (${route.totalDistanceMeters}m)")
    }

    fun recordGps(
        lat: Double,
        lon: Double,
        accuracyMeters: Float,
        speedMps: Float?,
        bearingDegrees: Float?,
        signalPercent: Int,
        satelliteCount: Int
    ) {
        val latStr = String.format(Locale.US, "%.5f", lat)
        val lonStr = String.format(Locale.US, "%.5f", lon)
        val accStr = String.format(Locale.US, "%.1f", accuracyMeters)
        val spdStr = if (speedMps != null) String.format(Locale.US, "%.1f", speedMps) else "N/A"
        val brgStr = if (bearingDegrees != null) String.format(Locale.US, "%.1f", bearingDegrees) else "N/A"
        val msg = "lat=$latStr lon=$lonStr acc=${accStr}m spd=${spdStr}m/s brg=${brgStr}° sig=${signalPercent}% sats=$satelliteCount"
        record("GPS", msg)
    }

    fun recordProgress(
        stepIndex: Int,
        totalSteps: Int,
        distanceAlongMeters: Double,
        remainingDistanceMeters: Double,
        distanceToNextManeuverMeters: Double,
        crossTrackErrorMeters: Double,
        isOffRoute: Boolean,
        offRouteCount: Int
    ) {
        val distAlongStr = String.format(Locale.US, "%.1f", distanceAlongMeters)
        val remDistStr = String.format(Locale.US, "%.1f", remainingDistanceMeters)
        val nextDistStr = String.format(Locale.US, "%.1f", distanceToNextManeuverMeters)
        val cteStr = String.format(Locale.US, "%.1f", crossTrackErrorMeters)
        val status = if (isOffRoute) "OFF_ROUTE(cnt=$offRouteCount)" else "ON_ROUTE"
        val msg = "step=${stepIndex + 1}/$totalSteps distAlong=${distAlongStr}m remDist=${remDistStr}m nextM=${nextDistStr}m CTE=${cteStr}m status=$status"
        record("PROGRESS", msg)
        updateSummary("${stepIndex + 1}/${totalSteps}단계 | 잔여 ${remDistStr}m | CTE ${cteStr}m | $status")
    }

    fun recordPose(
        headingDeg: Float,
        pitchDeg: Float,
        targetBearingDeg: Double?,
        isAligned: Boolean,
        promptText: String
    ) {
        val hStr = String.format(Locale.US, "%.1f", headingDeg)
        val pStr = String.format(Locale.US, "%.1f", pitchDeg)
        val bStr = if (targetBearingDeg != null) String.format(Locale.US, "%.1f", targetBearingDeg) else "N/A"
        val diffStr = if (targetBearingDeg != null) {
            val d = ((targetBearingDeg - headingDeg.toDouble() + 540.0) % 360.0) - 180.0
            String.format(Locale.US, "%+.1f", d)
        } else "N/A"
        val msg = "heading=${hStr}° targetBearing=${bStr}° diff=${diffStr}° pitch=${pStr}° aligned=$isAligned prompt=\"$promptText\""
        record("POSE", msg)
    }

    fun recordStepChange(fromStep: Int, toStep: Int, instruction: String) {
        val msg = "스텝 전환: ${fromStep + 1}단계 -> ${toStep + 1}단계 | 안내: \"$instruction\""
        record("STEP_CHANGE", msg)
    }

    fun recordApproach(stageMeters: Int, distanceMeters: Double, instruction: String) {
        val dStr = String.format(Locale.US, "%.1f", distanceMeters)
        val msg = "접근 예고(${stageMeters}m): 잔여=${dStr}m | 안내: \"$instruction\""
        record("APPROACH", msg)
    }

    fun recordRerouteTrigger(reason: String, detail: String) {
        val msg = "경로 재탐색 트리거: 사유=$reason | 상세=$detail"
        record("REROUTE_TRIGGER", msg)
    }

    fun recordRerouteSuccess(newDistanceMeters: Int, newStepCount: Int) {
        val msg = "경로 재탐색 성공: 신규 총거리=${newDistanceMeters}m, 스텝수=$newStepCount"
        record("REROUTE_SUCCESS", msg)
    }

    fun recordRerouteFailure(reason: String) {
        val msg = "경로 재탐색 실패: 사유=$reason"
        record("REROUTE_FAIL", msg)
    }

    fun recordGuidance(category: String, priority: String, text: String) {
        val msg = "pri=$priority cat=$category text=\"$text\""
        record("GUIDANCE", msg)
    }

    fun recordFinish(totalWalkedMeters: Double) {
        val wStr = String.format(Locale.US, "%.1f", totalWalkedMeters)
        val msg = "목적지 도착 완료! 총 보행거리: ${wStr}m"
        record("FINISH", msg)
        updateSummary("도착 완료 (총 ${wStr}m 보행)")
    }

    fun getLogFile(context: Context): File {
        init(context)
        return File(logDir ?: context.filesDir, LOG_FILE_NAME)
    }

    fun readRecentLogs(context: Context, maxLines: Int = 150): String {
        return try {
            val file = getLogFile(context)
            if (!file.exists()) return "기록된 경로 분석 로그가 없습니다."
            val lines = file.readLines(Charsets.UTF_8)
            val takeCount = lines.size.coerceAtMost(maxLines)
            lines.takeLast(takeCount).joinToString("\n")
        } catch (e: Exception) {
            "경로 로그 읽기 실패: ${e.message}"
        }
    }

    fun clearLogs(context: Context) {
        init(context)
        try {
            val dir = logDir ?: return
            File(dir, LOG_FILE_NAME).delete()
            File(dir, "$LOG_FILE_NAME.1").delete()
            _latestSummary.value = ""
        } catch (_: Exception) {}
    }
}
