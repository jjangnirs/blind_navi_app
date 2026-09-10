package kr.safecross.mobile.domain.repository

import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.model.PedestrianRoute

interface RouteRepository {
    suspend fun getPedestrianRoute(
        origin: LocationPoint,
        destination: LocationPoint,
        originName: String = "출발지",
        destinationName: String = "목적지",
        excludeStairs: Boolean = true
    ): Result<PedestrianRoute>
}
