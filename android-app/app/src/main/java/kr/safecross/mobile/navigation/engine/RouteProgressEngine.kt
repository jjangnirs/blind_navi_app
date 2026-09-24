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
    val isFinished: Boolean = false
)

/**
 * 순수 Kotlin 기반 실시간 경로 진행 및 이탈 추적 엔진.
 */
class RouteProgressEngine(
    private val route: PedestrianRoute,
    private val offRouteThresholdMeters: Double = 35.0,
    private val maneuverAdvanceDistanceMeters: Double = 15.0,
    private val destinationArrivalDistanceMeters: Double = 10.0,
    private val minConsecutiveOffRoute: Int = 2
) {

    private var currentManeuverIndex = 0
    private var offRouteConsecutiveCount = 0

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

        // 전체 경로 선분 중 최단 투영점 탐색
        var minCrossTrack = Double.MAX_VALUE
        var bestAlongTrack = 0.0

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

            if (proj.crossTrackDistanceMeters < minCrossTrack) {
                minCrossTrack = proj.crossTrackDistanceMeters
                bestAlongTrack = cumulativeDistances[i] + proj.alongTrackDistanceMeters
            }
        }

        // 이탈 여부 판단: GPS 정확도가 25m 이내로 유효한 상태에서 연속 임계값 초과 시에만 인정 (도심 난반사 보호)
        val isAccuracyReliable = sample.accuracyMeters <= 25.0f
        val isCurrentSampleOff = isAccuracyReliable && (minCrossTrack > offRouteThresholdMeters)
        if (isCurrentSampleOff) {
            offRouteConsecutiveCount++
        } else {
            offRouteConsecutiveCount = 0
        }
        val isOffRoute = offRouteConsecutiveCount >= minConsecutiveOffRoute

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

        return RouteProgressState(
            currentManeuverIndex = currentManeuverIndex,
            distanceAlongRouteMeters = bestAlongTrack,
            remainingDistanceMeters = remainingDist,
            crossTrackErrorMeters = minCrossTrack,
            isOffRoute = isOffRoute,
            currentManeuver = curManeuver,
            nextManeuver = nxtManeuver,
            distanceToNextManeuverMeters = distToNextM,
            isFinished = false
        )
    }

    /**
     * 상태 초기화 (재탐색 또는 프로세스 복구 시).
     */
    fun reset() {
        currentManeuverIndex = 0
        offRouteConsecutiveCount = 0
    }
}
