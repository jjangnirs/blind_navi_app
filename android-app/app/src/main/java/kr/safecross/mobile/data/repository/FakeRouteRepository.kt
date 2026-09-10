package kr.safecross.mobile.data.repository

import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.model.Maneuver
import kr.safecross.mobile.domain.model.PedestrianRoute
import kr.safecross.mobile.domain.model.ROUTE_DISCLAIMER_TEXT
import kr.safecross.mobile.domain.model.RouteSegment
import kr.safecross.mobile.domain.repository.RouteRepository

class FakeRouteRepository(
    var shouldFail: Boolean = false
) : RouteRepository {

    override suspend fun getPedestrianRoute(
        origin: LocationPoint,
        destination: LocationPoint,
        originName: String,
        destinationName: String,
        excludeStairs: Boolean
    ): Result<PedestrianRoute> {
        if (shouldFail) {
            return Result.failure(Exception("네트워크 연결 또는 외부 경로 공급자 장애가 발생했습니다."))
        }

        val maneuvers = listOf(
            Maneuver(
                index = 0,
                pointIndex = 0,
                location = origin,
                instruction = "$originName 출발. 서쪽 100m 직진 후 횡단보도 이용",
                turnType = 200
            ),
            Maneuver(
                index = 1,
                pointIndex = 1,
                location = LocationPoint(lat = (origin.lat + destination.lat) / 2, lon = (origin.lon + destination.lon) / 2),
                instruction = "신호등 있는 횡단보도 건너기 (점자블록 미보장 주의)",
                turnType = 211,
                facilityType = "횡단보도"
            ),
            Maneuver(
                index = 2,
                pointIndex = 2,
                location = destination,
                instruction = "$destinationName 도착",
                turnType = 201
            )
        )

        val segments = listOf(
            RouteSegment(
                index = 1,
                name = "상무시민로 보행로",
                distanceMeters = 250,
                durationSeconds = 210,
                geometry = listOf(origin, LocationPoint(lat = (origin.lat + destination.lat) / 2, lon = (origin.lon + destination.lon) / 2)),
                facilityType = "보행로"
            ),
            RouteSegment(
                index = 2,
                name = "평화공원 진입로",
                distanceMeters = 250,
                durationSeconds = 210,
                geometry = listOf(LocationPoint(lat = (origin.lat + destination.lat) / 2, lon = (origin.lon + destination.lon) / 2), destination),
                facilityType = "보행로"
            )
        )

        val fullGeometry = listOf(
            origin,
            LocationPoint(lat = (origin.lat + destination.lat) / 2, lon = (origin.lon + destination.lon) / 2),
            destination
        )

        return Result.success(
            PedestrianRoute(
                provider = "TMAP",
                totalDistanceMeters = 500,
                totalDurationSeconds = 420,
                excludeStairs = excludeStairs,
                fullGeometry = fullGeometry,
                maneuvers = maneuvers,
                segments = segments,
                disclaimer = ROUTE_DISCLAIMER_TEXT
            )
        )
    }

    /**
     * 테스트 편의용 기본 모의 경로 반환.
     */
    fun getSampleRoute(): PedestrianRoute {
        return kotlinx.coroutines.runBlocking {
            getPedestrianRoute(
                origin = LocationPoint(35.1595, 126.8526),
                destination = LocationPoint(35.1610, 126.8550)
            ).getOrThrow()
        }
    }
}
