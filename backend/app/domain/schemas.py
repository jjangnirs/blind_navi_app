import math
from typing import Literal

from pydantic import BaseModel, Field, field_validator

from app.core.config import (
    DEFAULT_BUFFER_M,
    DEFAULT_LIMIT,
    MAX_BUFFER_M,
    MAX_CORRIDOR_LENGTH_M,
    MAX_CORRIDOR_VERTICES,
    MAX_LAT,
    MAX_LIMIT,
    MAX_LON,
    MIN_BUFFER_M,
    MIN_CORRIDOR_VERTICES,
    MIN_LAT,
    MIN_LIMIT,
    MIN_LON,
)


class LocationPoint(BaseModel):
    lat: float = Field(
        ..., ge=MIN_LAT, le=MAX_LAT, description="위도 (WGS84, 32.0~39.0)"
    )
    lon: float = Field(
        ..., ge=MIN_LON, le=MAX_LON, description="경도 (WGS84, 124.0~132.0)"
    )


class SourceMetadata(BaseModel):
    provider: str = Field(..., description="데이터 제공 기관/지자체")
    sourceRecordId: str = Field(..., description="원천 관리번호/식별키")
    referenceDate: str | None = Field(
        None, description="원천 데이터 기준일자 (YYYY-MM-DD)"
    )
    ingestedAt: str | None = Field(None, description="파이프라인 적재 시각 (ISO 8601)")


class ConflictDetails(BaseModel):
    acousticSignal: bool | None = Field(
        None, description="음향신호기 원천 vs 현장 불일치 여부"
    )
    tactilePaving: bool | None = Field(
        None, description="점자블록 원천 vs 현장 불일치 여부"
    )
    curbCut: bool | None = Field(
        None, description="보도턱낮춤 원천 vs 현장 불일치 여부"
    )


class VerificationMetadata(BaseModel):
    status: Literal["FIELD_VERIFIED", "UNVERIFIED"] = Field(
        ..., description="현장 검증 상태 ('FIELD_VERIFIED' 또는 'UNVERIFIED')"
    )
    verifiedAt: str | None = Field(None, description="현장 검증 완료 시각 (ISO 8601)")
    verifiedBy: str | None = Field(None, description="현장 검증자/조사원 식별자")
    hasSourceConflict: bool = Field(
        False, description="원천 데이터와 현장 검증값 간 충돌/불일치 발생 여부"
    )
    conflictDetails: ConflictDetails | None = Field(
        None, description="시설 항목별 원천 vs 현장 불일치 상세"
    )


class RawFacility(BaseModel):
    """
    원천 공공데이터에 기재된 시설 속성 (현장 검증값으로 덮어쓰기 전 원본 보존값)
    null: 원천 미기재, false: 명시적 없음, true: 설치됨
    """

    acousticSignal: bool | None = Field(
        None, description="원천 음향신호기 (null=미기재, false=없음, true=있음)"
    )
    tactilePaving: bool | None = Field(
        None, description="원천 점자블록 (null=미기재, false=없음, true=있음)"
    )
    curbCut: bool | None = Field(
        None, description="원천 보도턱낮춤 (null=미기재, false=없음, true=있음)"
    )
    directionBearingDeg: float | None = Field(
        None, description="원천 진행 방위각 (0~360°)"
    )


class CrossingItemResponse(BaseModel):
    id: str = Field(..., description="횡단보도 고유 식별자 (UUID)")
    location: LocationPoint = Field(..., description="횡단보도 대표 위치 좌표")
    roadName: str | None = Field(None, description="도로명 또는 지번 주소")

    # 시설 속성 (현장 검증값이 있으면 우선 투영, 없으면 원천값 보존, null/false 명확히 구분)
    pedestrianSignal: bool | None = Field(
        None, description="보행자 신호등 설치 여부 (null/false/true)"
    )
    acousticSignal: bool | None = Field(
        None, description="시각장애인용 음향신호기 설치 여부 (null/false/true)"
    )
    tactilePaving: bool | None = Field(
        None, description="점자블록 설치 여부 (null/false/true)"
    )
    curbCut: bool | None = Field(
        None, description="연석 단차/보도 턱낮춤 여부 (null/false/true)"
    )
    directionBearingDeg: float | None = Field(
        None, description="횡단 진행 방위각 (0~360°)"
    )

    laneCount: int | None = Field(None, description="횡단 차로 수")
    greenSeconds: int | None = Field(None, description="보행 신호 녹색 시간 (초)")
    redSeconds: int | None = Field(None, description="보행 신호 적색 시간 (초)")

    source: SourceMetadata = Field(..., description="원천 데이터 출처 및 메타데이터")
    verification: VerificationMetadata = Field(
        ..., description="현장 검증 메타데이터 및 원천 충돌 플래그"
    )
    rawFacility: RawFacility = Field(..., description="투영 전 원본 공공데이터 시설값")


class CrossingsListResponse(BaseModel):
    dataVersion: str = Field(
        ..., description="데이터셋 배포 버전 (예: kr-crossing-20260908.1)"
    )
    generatedAt: str = Field(..., description="응답 생성 시각 (ISO 8601)")
    items: list[CrossingItemResponse] = Field(..., description="조회된 횡단보도 목록")


def haversine_distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = (
        math.sin(dphi / 2.0) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2.0) ** 2
    )
    return 6371000.0 * 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))


class CorridorRequest(BaseModel):
    type: Literal["LineString"] = Field(
        "LineString", description="GeoJSON 형상 타입 (LineString)"
    )
    coordinates: list[list[float]] = Field(
        ...,
        description="경로 좌표열 [[lon1, lat1], ..., [lonN, latN]] (최소 2개, 최대 500개)",
    )
    bufferM: float = Field(
        DEFAULT_BUFFER_M,
        ge=MIN_BUFFER_M,
        le=MAX_BUFFER_M,
        description=f"경로 회랑 버퍼 폭 ({MIN_BUFFER_M}m ~ {MAX_BUFFER_M}m)",
    )
    limit: int = Field(
        DEFAULT_LIMIT,
        ge=MIN_LIMIT,
        le=MAX_LIMIT,
        description=f"최대 반환 개수 ({MIN_LIMIT} ~ {MAX_LIMIT})",
    )

    @field_validator("coordinates")
    @classmethod
    def validate_coordinates(cls, v: list[list[float]]) -> list[list[float]]:
        if len(v) < MIN_CORRIDOR_VERTICES:
            raise ValueError(
                f"회랑 경로는 최소 {MIN_CORRIDOR_VERTICES}개 이상의 좌표가 필요합니다."
            )
        if len(v) > MAX_CORRIDOR_VERTICES:
            raise ValueError(
                f"회랑 경로는 최대 {MAX_CORRIDOR_VERTICES}개 이하의 정점만 허용됩니다."
            )

        total_length_m = 0.0
        for i, coord in enumerate(v):
            if len(coord) != 2:
                raise ValueError(f"인덱스 {i}의 좌표는 [lon, lat] 2개 요소여야 합니다.")
            lon, lat = coord[0], coord[1]
            if not (MIN_LON <= lon <= MAX_LON):
                raise ValueError(
                    f"인덱스 {i}의 경도({lon})가 대한민국 범위({MIN_LON}~{MAX_LON})를 벗어났습니다."
                )
            if not (MIN_LAT <= lat <= MAX_LAT):
                raise ValueError(
                    f"인덱스 {i}의 위도({lat})가 대한민국 범위({MIN_LAT}~{MAX_LAT})를 벗어났습니다."
                )

            if i > 0:
                prev_lon, prev_lat = v[i - 1][0], v[i - 1][1]
                total_length_m += haversine_distance_m(prev_lat, prev_lon, lat, lon)

        if total_length_m > MAX_CORRIDOR_LENGTH_M:
            raise ValueError(
                f"회랑 경로 총 길이({total_length_m:.1f}m)가 최대 허용 상한({MAX_CORRIDOR_LENGTH_M}m)을 초과했습니다."
            )

        return v
