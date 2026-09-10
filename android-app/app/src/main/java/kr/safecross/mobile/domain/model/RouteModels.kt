package kr.safecross.mobile.domain.model

const val ROUTE_DISCLAIMER_TEXT =
    "이 경로는 TMAP 보행자 경로 안내(계단 제외 옵션)를 기반으로 제공되며, " +
    "휠체어 단차(연석 2cm 이하)나 시각장애인 편의시설(음향신호기, 점자블록)의 완전성을 보장하는 안전 경로가 아닙니다. " +
    "주변 시설 데이터와 현장 상황을 반드시 확인하며 보행하세요."

data class LocationPoint(
    val lat: Double,
    val lon: Double
)

data class Maneuver(
    val index: Int,
    val pointIndex: Int,
    val location: LocationPoint,
    val instruction: String,
    val turnType: Int? = null,
    val facilityType: String? = null
)

data class RouteSegment(
    val index: Int,
    val name: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val geometry: List<LocationPoint>,
    val facilityType: String? = null
)

data class PedestrianRoute(
    val provider: String = "TMAP",
    val totalDistanceMeters: Int,
    val totalDurationSeconds: Int,
    val excludeStairs: Boolean = true,
    val fullGeometry: List<LocationPoint>,
    val maneuvers: List<Maneuver>,
    val segments: List<RouteSegment>,
    val disclaimer: String = ROUTE_DISCLAIMER_TEXT
)
