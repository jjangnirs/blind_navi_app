package kr.safecross.mobile.domain.repository

import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.model.PedestrianRoute

interface RouteRepository {
    suspend fun getPedestrianRoute(
        origin: LocationPoint,
        destination: LocationPoint,
        originName: String = "출발지",
        destinationName: String = "목적지",
        excludeStairs: Boolean = true,
        // 출발 시 보행 방향(0~359°, 북쪽 0). 재탐색 시 되돌아가는 경로를 줄이기 위해 TMAP angle로 전달
        startHeadingDegrees: Int? = null
    ): Result<PedestrianRoute>
}
