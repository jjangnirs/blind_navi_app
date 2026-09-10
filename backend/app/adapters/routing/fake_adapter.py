import json
import os
from enum import Enum

from app.adapters.routing.port import (
    Maneuver,
    NormalizedRoute,
    PedestrianRouter,
    RouteRequest,
    RouteSegment,
)
from app.core.exceptions import AppException
from app.domain.schemas import LocationPoint


class FakeRouterMode(Enum):
    NORMAL = "NORMAL"
    AUTH_ERROR_401 = "AUTH_ERROR_401"
    RATE_LIMITED_429 = "RATE_LIMITED_429"
    SERVER_ERROR_500 = "SERVER_ERROR_500"
    TIMEOUT = "TIMEOUT"


def parse_tmap_geojson(geojson_dict: dict, exclude_stairs: bool) -> NormalizedRoute:
    """
    TMAP 보행자 경로안내 GeoJSON FeatureCollection을 NormalizedRoute 도메인 모델로 정규화합니다.
    """
    features = geojson_dict.get("features", [])
    if not features:
        return NormalizedRoute(
            provider="TMAP",
            total_distance_m=0,
            total_duration_sec=0,
            exclude_stairs=exclude_stairs,
            full_geometry=[],
            maneuvers=[],
            segments=[],
        )

    # 전체 요약 정보는 통상 0번째 Feature의 properties에 포함됨
    first_props = features[0].get("properties", {})
    total_dist = first_props.get("totalDistance", 0)
    total_time = first_props.get("totalTime", 0)

    maneuvers: list[Maneuver] = []
    segments: list[RouteSegment] = []
    full_coords: list[LocationPoint] = []

    for f in features:
        geom = f.get("geometry", {})
        props = f.get("properties", {})
        geom_type = geom.get("type")

        if geom_type == "Point":
            coords = geom.get("coordinates", [0.0, 0.0])
            loc = LocationPoint(lon=coords[0], lat=coords[1])
            maneuver = Maneuver(
                index=props.get("index", len(maneuvers)),
                point_index=props.get("pointIndex", len(maneuvers)),
                location=loc,
                instruction=props.get("description", props.get("name", "")),
                turn_type=props.get("turnType"),
                facility_type=props.get("facilityType"),
            )
            maneuvers.append(maneuver)

        elif geom_type == "LineString":
            line_coords = geom.get("coordinates", [])
            geom_points = [LocationPoint(lon=c[0], lat=c[1]) for c in line_coords]
            for pt in geom_points:
                if not full_coords or (
                    full_coords[-1].lat != pt.lat or full_coords[-1].lon != pt.lon
                ):
                    full_coords.append(pt)

            segment = RouteSegment(
                index=props.get("index", len(segments)),
                name=props.get("name", ""),
                distance_m=props.get("distance", 0),
                duration_sec=props.get("time", 0),
                geometry=geom_points,
                facility_type=props.get("facilityType"),
            )
            segments.append(segment)

    return NormalizedRoute(
        provider="TMAP",
        total_distance_m=total_dist,
        total_duration_sec=total_time,
        exclude_stairs=exclude_stairs,
        full_geometry=full_coords,
        maneuvers=maneuvers,
        segments=segments,
    )


class FakePedestrianRouter(PedestrianRouter):
    """
    로컬 개발 및 테스트를 위한 모의(Fake) TMAP 보행자 경로 라우터입니다.
    실제 외부 API 키나 네트워크 없이도 계약 테스트 및 다양한 장애 시뮬레이션을 수행할 수 있습니다.
    """

    def __init__(self, mode: FakeRouterMode = FakeRouterMode.NORMAL):
        self.mode = mode

    def set_mode(self, mode: FakeRouterMode) -> None:
        self.mode = mode

    async def route(self, request: RouteRequest) -> NormalizedRoute:
        if self.mode == FakeRouterMode.AUTH_ERROR_401:
            raise AppException(
                status_code=502,
                code="PROVIDER_AUTH_ERROR",
                message="TMAP 공급자 인증에 실패했습니다 (401 Unauthorized).",
                message_key="error.provider_auth",
            )
        if self.mode == FakeRouterMode.RATE_LIMITED_429:
            raise AppException(
                status_code=429,
                code="PROVIDER_RATE_LIMITED",
                message="TMAP 공급자 호출 한도를 초과했습니다 (429 Too Many Requests).",
                message_key="error.provider_rate_limited",
                headers={"Retry-After": "60"},
            )
        if self.mode == FakeRouterMode.SERVER_ERROR_500:
            raise AppException(
                status_code=502,
                code="PROVIDER_UNAVAILABLE",
                message="TMAP 공급자 서버 오류가 발생했습니다 (500 Internal Server Error).",
                message_key="error.provider_unavailable",
            )
        if self.mode == FakeRouterMode.TIMEOUT:
            raise AppException(
                status_code=504,
                code="PROVIDER_TIMEOUT",
                message="TMAP 경로 조회 연결/응답 시간을 초과했습니다.",
                message_key="error.provider_timeout",
            )

        fixture_path = os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(__file__))),
            "tests",
            "fixtures",
            "tmap_sample_route.json",
        )

        def _load_data() -> dict:
            if os.path.exists(fixture_path):
                with open(fixture_path, "r", encoding="utf-8") as f:
                    return json.load(f)
            return {
                "type": "FeatureCollection",
                "features": [
                    {
                        "type": "Feature",
                        "geometry": {
                            "type": "Point",
                            "coordinates": [request.origin.lon, request.origin.lat],
                        },
                        "properties": {
                            "index": 0,
                            "pointIndex": 0,
                            "name": request.origin_name,
                            "description": f"{request.origin_name}에서 출발합니다",
                            "totalDistance": 500,
                            "totalTime": 420,
                            "turnType": 200,
                            "pointType": "SP",
                        },
                    },
                    {
                        "type": "Feature",
                        "geometry": {
                            "type": "LineString",
                            "coordinates": [
                                [request.origin.lon, request.origin.lat],
                                [request.destination.lon, request.destination.lat],
                            ],
                        },
                        "properties": {
                            "index": 1,
                            "lineIndex": 0,
                            "name": "보행로",
                            "distance": 500,
                            "time": 420,
                        },
                    },
                    {
                        "type": "Feature",
                        "geometry": {
                            "type": "Point",
                            "coordinates": [
                                request.destination.lon,
                                request.destination.lat,
                            ],
                        },
                        "properties": {
                            "index": 2,
                            "pointIndex": 1,
                            "name": request.destination_name,
                            "description": f"{request.destination_name}에 도착했습니다",
                            "turnType": 201,
                            "pointType": "EP",
                        },
                    },
                ],
            }

        import anyio.to_thread

        data = await anyio.to_thread.run_sync(_load_data)

        return parse_tmap_geojson(data, exclude_stairs=request.exclude_stairs)
