package kr.safecross.mobile.navigation.engine

import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.model.Maneuver
import kr.safecross.mobile.domain.model.PedestrianRoute
import kr.safecross.mobile.location.LocationSample

/**
 * 경로 진행 추적 상태.
 */
data class RouteProgressState(
    val currentManeuverIndex: Int = 0,
    val distanceAlongRouteMeters: Double = 0.0,
    val remainingDistanceMeters: Double = 0.0,
    val crossTrackErrorMeters: Double = 0.0,
    val isOffRoute: Boolean = false,
    val currentManeuver: Maneuver? = null,
    val nextManeuver: Maneuver? = null,
    val distanceToNextManeuverMeters: Double = 0.0,
    val isFinished: Boolean = false,
    val offRouteConsecutiveCount: Int = 0,
    // 이탈 판정 근거: HARD(35 m 초과 연속) / SUSTAINED(20 m 초과 10초 지속), 이탈 아니면 null
    val offRouteReason: String? = null,
    // 경로상 위치 기준으로 아직 도달하지 않은 다음 분기점과 그곳까지의 경로상 거리 (화면 표시용)
    val upcomingManeuverIndex: Int? = null,
    val distanceToUpcomingManeuverMeters: Double = 0.0
)

/**
 * 순수 Kotlin 기반 실시간 경로 진행 및 이탈 추적 엔진.
 */
class RouteProgressEngine(
    private val route: PedestrianRoute,
    private val offRouteThresholdMeters: Double = 35.0,
    private val maneuverAdvanceDistanceMeters: Double = 15.0,
    private val destinationArrivalDistanceMeters: Double = 10.0,
    private val minConsecutiveOffRoute: Int = 2,
    // 지속 이탈: GPS가 정확(15 m 이내)할 때 max(20 m, 정확도×2)를 넘는 상태가 10초 이상 이어지면 이탈
    // (10/03 현장: 정확도 3~9 m에서 경로와 23~27 m 떨어진 채 2분간 머물렀지만 35 m 기준에 못 미쳐 재탐색 안 됨)
    private val sustainedOffRouteThresholdMeters: Double = 20.0,
    private val sustainedOffRouteDurationMs: Long = 10_000L,
    private val sustainedMaxAccuracyMeters: Float = 15.0f
) {

    private var currentManeuverIndex = 0
    private var offRouteConsecutiveCount = 0
    private var sustainedOffSinceMs: Long? = null

    // 직전 경로상 위치 (투영 점프 억제용)
    private var lastAlongTrackMeters: Double? = null
    private var lastSampleTimeMs: Long = 0L

    // 경로 지오메트리 점 목록
    private val pathPoints: List<LocationPoint> = if (route.fullGeometry.isNotEmpty()) {
        route.fullGeometry
    } else {
        route.maneuvers.map { it.location }
    }

    // 각 세그먼트의 누적 거리 테이블
    private val segmentLengths: List<Double>
    private val cumulativeDistances: List<Double>
    val totalRouteDistanceMeters: Double

    init {
        val lengths = mutableListOf<Double>()
        val cumuls = mutableListOf<Double>()
        var sum = 0.0
        cumuls.add(0.0)

        for (i in 0 until pathPoints.size - 1) {
            val p1 = pathPoints[i]
            val p2 = pathPoints[i + 1]
            val len = GeoMath.distanceMeters(p1.lat, p1.lon, p2.lat, p2.lon)
            lengths.add(len)
            sum += len
            cumuls.add(sum)
        }

        segmentLengths = lengths
        cumulativeDistances = cumuls
        totalRouteDistanceMeters = sum
    }

    /**
     * 새로운 위치 샘플을 수신하여 경로 진행 상태를 갱신합니다.
     */
    fun updateProgress(sample: LocationSample): RouteProgressState {
        if (pathPoints.isEmpty()) {
            return RouteProgressState(isFinished = true)
        }

        // 목적지 도착 판정
        val lastPoint = pathPoints.last()
        val distToDestination = GeoMath.distanceMeters(sample.lat, sample.lon, lastPoint.lat, lastPoint.lon)
        if (distToDestination <= destinationArrivalDistanceMeters) {
            return RouteProgressState(
                currentManeuverIndex = route.maneuvers.size - 1,
                distanceAlongRouteMeters = totalRouteDistanceMeters,
                remainingDistanceMeters = 0.0,
                crossTrackErrorMeters = distToDestination,
                isOffRoute = false,
                currentManeuver = route.maneuvers.lastOrNull(),
                nextManeuver = null,
                distanceToNextManeuverMeters = 0.0,
                isFinished = true
            )
        }

        // 전체 경로 선분 중 투영점 탐색.
        // 경로가 지그재그로 꺾여 뒤쪽 구간이 현재 위치 바로 옆을 지나는 경우, 단순 최단거리만 쓰면
        // 경로상 위치가 수십 m 건너뛴다 (10/02 현장: 1m 이동에 91m → 124m 점프, 횡단 완료 오판).
        // 직전 위치에서 물리적으로 갈 수 없는 거리(15m + 3m/s × 경과시간)를 넘는 후보에는 초과 거리만큼 벌점을 준다.
        val sampleTimeMs = sample.timestampEpochMs
        val elapsedSec = if (lastSampleTimeMs > 0L) ((sampleTimeMs - lastSampleTimeMs) / 1000.0).coerceIn(0.0, 120.0) else 0.0
        val allowedJump = MAX_JUMP_BASE_METERS + MAX_PLAUSIBLE_SPEED_MPS * elapsedSec
        val prevAlong = lastAlongTrackMeters

        var minCrossTrack = Double.MAX_VALUE
        var bestAlongTrack = 0.0
        var bestScore = Double.MAX_VALUE

        for (i in 0 until pathPoints.size - 1) {
            val p1 = pathPoints[i]
            val p2 = pathPoints[i + 1]
            val proj = GeoMath.projectPointOnSegment(
                pLat = sample.lat,
                pLon = sample.lon,
                startLat = p1.lat,
                startLon = p1.lon,
                endLat = p2.lat,
                endLon = p2.lon
            )
            val along = cumulativeDistances[i] + proj.alongTrackDistanceMeters
            val jumpPenalty = if (prevAlong != null) (kotlin.math.abs(along - prevAlong) - allowedJump).coerceAtLeast(0.0) else 0.0
            val score = proj.crossTrackDistanceMeters + jumpPenalty

            if (proj.crossTrackDistanceMeters < minCrossTrack) {
                minCrossTrack = proj.crossTrackDistanceMeters
            }
            if (score < bestScore) {
                bestScore = score
                bestAlongTrack = along
            }
        }
        lastAlongTrackMeters = bestAlongTrack
        lastSampleTimeMs = sampleTimeMs

        // 이탈 여부 판단: GPS 정확도가 25m 이내로 유효한 상태에서 연속 임계값 초과 시에만 인정 (도심 난반사 보호)
        val isAccuracyReliable = sample.accuracyMeters <= 25.0f
        val isCurrentSampleOff = isAccuracyReliable && (minCrossTrack > offRouteThresholdMeters)
        if (isCurrentSampleOff) {
            offRouteConsecutiveCount++
        } else {
            offRouteConsecutiveCount = 0
        }
        val isHardOff = offRouteConsecutiveCount >= minConsecutiveOffRoute

        // 지속 이탈 타이머: 조건을 만족하면 시작, 경로 15 m 이내로 확실히 돌아오면 해제
        // (정확도가 나빠진 샘플은 타이머를 멈추지도 늘리지도 않고 유지)
        val sustainedThreshold = maxOf(sustainedOffRouteThresholdMeters, sample.accuracyMeters * 2.0)
        when {
            sample.accuracyMeters <= sustainedMaxAccuracyMeters && minCrossTrack > sustainedThreshold ->
                if (sustainedOffSinceMs == null) sustainedOffSinceMs = sampleTimeMs
            minCrossTrack < sustainedOffRouteThresholdMeters - SUSTAINED_RELEASE_MARGIN_METERS ->
                sustainedOffSinceMs = null
        }
        val isSustainedOff = sustainedOffSinceMs?.let { sampleTimeMs - it >= sustainedOffRouteDurationMs } == true
        val isOffRoute = isHardOff || isSustainedOff
        val offRouteReason = when {
            isHardOff -> "HARD"
            isSustainedOff -> "SUSTAINED"
            else -> null
        }

        // Maneuver 전진 판단: 다음 목표 Maneuver 지점(nextManeuver)에 도달 시 스텝 전진
        val maneuvers = route.maneuvers
        val nextIdx = currentManeuverIndex + 1
        if (nextIdx < maneuvers.size) {
            val nextM = maneuvers[nextIdx]
            val distToNextM = GeoMath.distanceMeters(
                sample.lat,
                sample.lon,
                nextM.location.lat,
                nextM.location.lon
            )

            if (distToNextM <= maneuverAdvanceDistanceMeters) {
                currentManeuverIndex = nextIdx
            }
        }

        val remainingDist = (totalRouteDistanceMeters - bestAlongTrack).coerceAtLeast(0.0)

        val curManeuver = maneuvers.getOrNull(currentManeuverIndex)
        val nxtManeuver = maneuvers.getOrNull(currentManeuverIndex + 1)
        val distToNextM = if (nxtManeuver != null) {
            GeoMath.distanceMeters(sample.lat, sample.lon, nxtManeuver.location.lat, nxtManeuver.location.lon)
        } else {
            distToDestination
        }

        // 화면 표시용: 경로상 위치보다 3m 이상 앞에 있는 첫 분기점 (이미 지난 분기점 문구가 남지 않도록)
        val alongs = maneuverAlongDistances
        val upcomingIdx = alongs.indices.firstOrNull { alongs[it] > bestAlongTrack + PASSED_TOLERANCE_METERS }
        val distToUpcoming = upcomingIdx?.let { (alongs[it] - bestAlongTrack).coerceAtLeast(0.0) } ?: distToDestination

        return RouteProgressState(
            upcomingManeuverIndex = upcomingIdx,
            distanceToUpcomingManeuverMeters = distToUpcoming,
            currentManeuverIndex = currentManeuverIndex,
            distanceAlongRouteMeters = bestAlongTrack,
            remainingDistanceMeters = remainingDist,
            crossTrackErrorMeters = minCrossTrack,
            isOffRoute = isOffRoute,
            currentManeuver = curManeuver,
            nextManeuver = nxtManeuver,
            distanceToNextManeuverMeters = distToNextM,
            isFinished = false,
            offRouteConsecutiveCount = offRouteConsecutiveCount,
            offRouteReason = offRouteReason
        )
    }

    /**
     * 경로 시작점으로부터 [alongMeters]만큼 떨어진 경로 위의 지점 (선분 보간).
     * 현재 위치의 투영점 + 전방 거리로 "앞으로 가야 할 지점"(look-ahead)을 구하거나,
     * 지도 표시용으로 현재 위치를 경로 위에 맞출(map-matching) 때 사용한다.
     */
    fun pointAtDistance(alongMeters: Double): LocationPoint? {
        if (pathPoints.isEmpty()) return null
        if (pathPoints.size == 1 || alongMeters <= 0.0) return pathPoints.first()
        if (alongMeters >= totalRouteDistanceMeters) return pathPoints.last()

        for (i in segmentLengths.indices) {
            val segStart = cumulativeDistances[i]
            val segEnd = cumulativeDistances[i + 1]
            if (alongMeters <= segEnd) {
                val len = segmentLengths[i]
                val t = if (len <= 0.0) 0.0 else ((alongMeters - segStart) / len).coerceIn(0.0, 1.0)
                val a = pathPoints[i]
                val b = pathPoints[i + 1]
                return LocationPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
        }
        return pathPoints.last()
    }

    /**
     * 각 분기점(Maneuver)의 경로상 거리. 분기점 좌표를 경로선에 투영하되, 경로가 같은 길을
     * 되돌아오는 경우를 대비해 앞 분기점보다 뒤쪽(단조 증가)에서만 찾는다.
     */
    val maneuverAlongDistances: List<Double> by lazy {
        val result = mutableListOf<Double>()
        var minAlong = 0.0
        for (m in route.maneuvers) {
            var bestAlong = minAlong
            var bestCte = Double.MAX_VALUE
            for (i in 0 until pathPoints.size - 1) {
                if (cumulativeDistances[i + 1] < minAlong - 1.0) continue
                val p1 = pathPoints[i]
                val p2 = pathPoints[i + 1]
                val proj = GeoMath.projectPointOnSegment(m.location.lat, m.location.lon, p1.lat, p1.lon, p2.lat, p2.lon)
                val along = cumulativeDistances[i] + proj.alongTrackDistanceMeters
                if (along >= minAlong - 1.0 && proj.crossTrackDistanceMeters < bestCte) {
                    bestCte = proj.crossTrackDistanceMeters
                    bestAlong = along
                }
            }
            result.add(bestAlong)
            minAlong = bestAlong
        }
        result
    }

    /**
     * 상태 초기화 (재탐색 또는 프로세스 복구 시).
     */
    fun reset() {
        currentManeuverIndex = 0
        offRouteConsecutiveCount = 0
        sustainedOffSinceMs = null
        lastAlongTrackMeters = null
        lastSampleTimeMs = 0L
    }

    private companion object {
        const val MAX_JUMP_BASE_METERS = 15.0
        const val MAX_PLAUSIBLE_SPEED_MPS = 3.0
        const val PASSED_TOLERANCE_METERS = 3.0
        const val SUSTAINED_RELEASE_MARGIN_METERS = 5.0
    }
}
