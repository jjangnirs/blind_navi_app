from dataclasses import dataclass, field
from typing import Protocol

from app.domain.schemas import LocationPoint

ROUTE_DISCLAIMER_TEXT = (
    "이 경로는 TMAP 보행자 경로 안내(계단 제외 옵션)를 기반으로 제공되며, "
    "휠체어 단차(연석 2cm 이하)나 시각장애인 편의시설(음향신호기, 점자블록)의 완전성을 보장하는 안전 경로가 아닙니다. "
    "주변 시설 데이터와 현장 상황을 반드시 확인하며 보행하세요."
)


@dataclass
class RouteRequest:
    origin: LocationPoint
    destination: LocationPoint
    origin_name: str = "출발지"
    destination_name: str = "목적지"
    exclude_stairs: bool = True
    pass_points: list[LocationPoint] = field(default_factory=list)


@dataclass
class Maneuver:
    index: int
    point_index: int
    location: LocationPoint
    instruction: str
    turn_type: int | None = None
    facility_type: str | None = None


@dataclass
class RouteSegment:
    index: int
    name: str
    distance_m: int
    duration_sec: int
    geometry: list[LocationPoint]
    facility_type: str | None = None


@dataclass
class NormalizedRoute:
    provider: str
    total_distance_m: int
    total_duration_sec: int
    exclude_stairs: bool
    full_geometry: list[LocationPoint]
    maneuvers: list[Maneuver]
    segments: list[RouteSegment]
    disclaimer: str = ROUTE_DISCLAIMER_TEXT


class PedestrianRouter(Protocol):
    """
    보행자 경로 안내 공급자를 추상화하는 포트(Port) 인터페이스입니다.
    """

    async def route(self, request: RouteRequest) -> NormalizedRoute:
        """출발지와 목적지를 기반으로 정규화된 보행 경로를 반환합니다."""
        ...
