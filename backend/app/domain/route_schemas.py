from pydantic import BaseModel, Field

from app.adapters.routing.port import ROUTE_DISCLAIMER_TEXT
from app.core.config import DATA_VERSION
from app.domain.schemas import LocationPoint


class PedestrianRouteRequest(BaseModel):
    origin: LocationPoint = Field(..., description="출발지 좌표 (WGS84)")
    destination: LocationPoint = Field(..., description="목적지 좌표 (WGS84)")
    originName: str = Field("출발지", max_length=100, description="출발지 명칭")
    destinationName: str = Field("목적지", max_length=100, description="목적지 명칭")
    excludeStairs: bool = Field(True, description="계단 제외 여부 (기본값: True)")
    passPoints: list[LocationPoint] = Field(
        default_factory=list, max_length=5, description="경유지 좌표 목록 (최대 5개)"
    )


class RouteManeuverSchema(BaseModel):
    index: int = Field(..., description="안내 지점 순번")
    pointIndex: int = Field(..., description="포인트 순번")
    location: LocationPoint = Field(..., description="안내 지점 좌표")
    instruction: str = Field(..., description="보행 행동/회전 안내 문구")
    turnType: int | None = Field(None, description="TMAP 회전 코드")
    facilityType: str | None = Field(None, description="시설 유형 (횡단보도, 육교 등)")


class RouteSegmentSchema(BaseModel):
    index: int = Field(..., description="세그먼트 순번")
    name: str = Field(..., description="도로명 또는 구간 명칭")
    distanceMeters: int = Field(..., description="구간 거리 (미터)")
    durationSeconds: int = Field(..., description="구간 소요 시간 (초)")
    geometry: list[LocationPoint] = Field(..., description="구간 상세 좌표열")
    facilityType: str | None = Field(None, description="시설 유형 (보도, 횡단보도 등)")


class PedestrianRouteResponse(BaseModel):
    dataVersion: str = Field(default=DATA_VERSION, description="데이터 버전")
    provider: str = Field(..., description="경로 안내 공급자 (TMAP 등)")
    totalDistanceMeters: int = Field(..., description="총 경로 거리 (미터)")
    totalDurationSeconds: int = Field(..., description="총 소요 시간 (초)")
    excludeStairs: bool = Field(..., description="계단 제외 옵션 적용 여부")
    fullGeometry: list[LocationPoint] = Field(
        ..., description="전체 경로 폴리라인 좌표열 (WGS84)"
    )
    maneuvers: list[RouteManeuverSchema] = Field(
        ..., description="경로 회전/분기점(Maneuver) 목록"
    )
    segments: list[RouteSegmentSchema] = Field(
        ..., description="경로 세그먼트(링크) 목록"
    )
    disclaimer: str = Field(
        default=ROUTE_DISCLAIMER_TEXT,
        description="경로 안내 및 접근성 한계 고지문 (법적/안전 필수 면책)",
    )
