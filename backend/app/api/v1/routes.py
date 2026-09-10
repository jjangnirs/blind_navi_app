from fastapi import APIRouter, Depends

from app.adapters.routing.factory import get_pedestrian_router
from app.adapters.routing.port import PedestrianRouter, RouteRequest
from app.core.config import DATA_VERSION
from app.core.exceptions import CommonErrorEnvelope
from app.domain.route_schemas import (
    PedestrianRouteRequest,
    PedestrianRouteResponse,
    RouteManeuverSchema,
    RouteSegmentSchema,
)

COMMON_RESPONSES = {
    400: {
        "model": CommonErrorEnvelope,
        "description": "잘못된 요청 또는 유효성 검증 실패",
    },
    404: {"model": CommonErrorEnvelope, "description": "요청한 리소스를 찾을 수 없음"},
    429: {"model": CommonErrorEnvelope, "description": "요청 허용량 초과 (Rate Limit)"},
    500: {"model": CommonErrorEnvelope, "description": "서버 내부 처리 오류"},
    502: {"model": CommonErrorEnvelope, "description": "외부 공급자 연계 오류"},
    503: {
        "model": CommonErrorEnvelope,
        "description": "외부 공급자 서킷 브레이커 동작 중",
    },
    504: {
        "model": CommonErrorEnvelope,
        "description": "요청 처리 제한시간 초과 (Timeout)",
    },
}

router = APIRouter(
    prefix="/v1/routes",
    tags=["routes"],
    responses=COMMON_RESPONSES,
)


@router.post(
    "/pedestrian",
    response_model=PedestrianRouteResponse,
    summary="보행자 경로 탐색 (계단 제외 옵션 지원)",
    description=(
        "출발지와 목적지 좌표를 기반으로 TMAP 보행자 경로 안내 API를 연계하여 정규화된 경로를 반환합니다. "
        "기본적으로 계단 제외(searchOption=30)가 적용되며, 휠체어 단차 및 편의시설 완전성을 보장하는 '안전 경로'가 아님을 유의해야 합니다."
    ),
)
async def get_pedestrian_route(
    request: PedestrianRouteRequest,
    routing_adapter: PedestrianRouter = Depends(get_pedestrian_router),  # noqa: B008
) -> PedestrianRouteResponse:
    domain_request = RouteRequest(
        origin=request.origin,
        destination=request.destination,
        origin_name=request.originName,
        destination_name=request.destinationName,
        exclude_stairs=request.excludeStairs,
        pass_points=request.passPoints,
    )

    normalized = await routing_adapter.route(domain_request)

    return PedestrianRouteResponse(
        dataVersion=DATA_VERSION,
        provider=normalized.provider,
        totalDistanceMeters=normalized.total_distance_m,
        totalDurationSeconds=normalized.total_duration_sec,
        excludeStairs=normalized.exclude_stairs,
        fullGeometry=normalized.full_geometry,
        maneuvers=[
            RouteManeuverSchema(
                index=m.index,
                pointIndex=m.point_index,
                location=m.location,
                instruction=m.instruction,
                turnType=m.turn_type,
                facilityType=m.facility_type,
            )
            for m in normalized.maneuvers
        ],
        segments=[
            RouteSegmentSchema(
                index=s.index,
                name=s.name,
                distanceMeters=s.distance_m,
                durationSeconds=s.duration_sec,
                geometry=s.geometry,
                facilityType=s.facility_type,
            )
            for s in normalized.segments
        ],
        disclaimer=normalized.disclaimer,
    )
